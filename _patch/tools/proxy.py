"""Local logging proxy for the Qoder custom endpoint (troubleshooting aid).

Listens on 127.0.0.1:8899 and forwards every request to the upstream you pass on the
command line, dumping request/response bodies to a capture log
(%TEMP%/qoder-proxy-capture.log).

usage:  python proxy.py [port] <upstream-base-url>
        python proxy.py 8899 https://your-upstream.example

响应按 HTTP 规范重新分帧：上游给了 Content-Length 就原样转发，否则用 chunked，
避免 JetBrains / Java 客户端因"无长度响应"报 http1_0 content 错误。
"""
import http.client
import http.server
import json
import os
import socket
import socketserver
import sys
import urllib.parse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
if len(sys.argv) < 3:
    raise SystemExit('usage: python proxy.py [port] <upstream-base-url>   e.g. python proxy.py 8899 https://your-upstream.example')
UPSTREAM = sys.argv[2].rstrip('/')
LOG = os.path.join(os.environ.get('TEMP', '.'), 'qoder-proxy-capture.log')

SKIP_REQ_HEADERS = {'host', 'content-length', 'connection', 'accept-encoding', 'transfer-encoding', 'proxy-connection'}
SKIP_RESP_HEADERS = {'transfer-encoding', 'content-length', 'connection', 'content-encoding', 'keep-alive'}


def log_write(text):
    with open(LOG, 'a', encoding='utf-8', errors='replace') as f:
        f.write(text)


def pretty(body, limit):
    if not body:
        return ''
    try:
        text = body.decode('utf-8', errors='replace')
        try:
            text = json.dumps(json.loads(text), ensure_ascii=False, indent=1)
        except Exception:
            pass
        return text[:limit]
    except Exception:
        return '<%d bytes binary>' % len(body)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'qoder-proxy/2'
    sys_version = ''

    def log_message(self, *args):
        pass

    def _upstream(self):
        u = urllib.parse.urlsplit(UPSTREAM)
        cls = http.client.HTTPSConnection if u.scheme == 'https' else http.client.HTTPConnection
        port = u.port or (443 if u.scheme == 'https' else 80)
        return cls(u.hostname, port, timeout=900), u.path.rstrip('/')

    def _handle(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        has_img = any(k in body for k in (b'"image_url"', b'input_image', b'base64,', b'multi_content'))

        buf = ['\n' + '=' * 90,
               '%s %s   [image-ish: %s]  body=%d bytes' % (self.command, self.path, has_img, len(body))]
        for k, v in self.headers.items():
            if k.lower() in ('authorization', 'content-type', 'accept', 'user-agent', 'x-request-id', 'openai-beta'):
                buf.append('%s: %s' % (k, v[:100]))
        if body:
            buf.append('--- REQUEST BODY ---')
            buf.append(pretty(body, 300000))
        log_write('\n'.join(buf) + '\n')

        conn, base_path = self._upstream()
        path = base_path + self.path
        headers = {k: v for k, v in self.headers.items() if k.lower() not in SKIP_REQ_HEADERS}
        headers['Accept-Encoding'] = 'identity'
        headers['Connection'] = 'close'
        try:
            conn.request(self.command, path, body=body if body else None, headers=headers)
            resp = conn.getresponse()
        except Exception as e:
            log_write('!! upstream error: %r\n' % (e,))
            try:
                self.send_error(502, 'upstream error: %s' % e)
            except Exception:
                pass
            return

        cl = resp.getheader('Content-Length')
        chunked = cl is None and resp.status not in (204, 304)
        self.send_response(resp.status)
        for k, v in resp.headers.items():
            if k.lower() in SKIP_RESP_HEADERS:
                continue
            self.send_header(k, v)
        if chunked:
            self.send_header('Transfer-Encoding', 'chunked')
        else:
            self.send_header('Content-Length', cl or '0')
        self.send_header('Connection', 'close')
        self.end_headers()

        collected = b''
        total = 0
        try:
            while True:
                piece = resp.read(8192)
                if not piece:
                    break
                if len(collected) < 20000:
                    collected += piece
                total += len(piece)
                if chunked:
                    self.wfile.write(b'%x\r\n' % len(piece) + piece + b'\r\n')
                else:
                    self.wfile.write(piece)
                self.wfile.flush()
            if chunked:
                self.wfile.write(b'0\r\n\r\n')
                self.wfile.flush()
        except Exception as e:
            log_write('!! client write failed: %r\n' % (e,))
        finally:
            try:
                conn.close()
            except Exception:
                pass
        log_write('--- RESPONSE %s, %d bytes ---\n%s\n' % (resp.status, total, pretty(collected, 4000)))

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle
    do_DELETE = _handle


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def get_request(self):
        sock, addr = super().get_request()
        try:
            sock.settimeout(1.5)
            peek = sock.recv(32, socket.MSG_PEEK)
            sock.settimeout(None)
            if peek[:1] == b'\x16':
                kind = 'TLS-ClientHello'
            elif peek[:4] in (b'POST', b'GET ', b'HEAD', b'PUT ', b'DELE', b'OPTI'):
                kind = 'plain-http'
            elif peek[:1] == b'\x05':
                kind = 'socks5?'
            else:
                kind = 'unknown'
            log_write('[tcp] connect from %s first-bytes=%r kind=%s\n' % (addr, peek[:16], kind))
        except Exception as e:
            log_write('[tcp] connect from %s (peek failed: %r)\n' % (addr, e))
            try:
                sock.settimeout(None)
            except Exception:
                pass
        return sock, addr


if __name__ == '__main__':
    log_write('proxy started on 127.0.0.1:%d -> %s\n' % (PORT, UPSTREAM))
    print('proxy listening on 127.0.0.1:%d -> %s (capture: %s)' % (PORT, UPSTREAM, LOG), flush=True)
    with Server(('127.0.0.1', PORT), Handler) as httpd:
        httpd.serve_forever()
