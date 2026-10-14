"""Local logging proxy for the Qoder custom endpoint (troubleshooting aid).

Listens on 127.0.0.1:8899 and forwards every request to the upstream you pass on the
command line, dumping request/response bodies to a capture log
(%TEMP%/qoder-proxy-capture.log).

usage:  python proxy.py [port] [upstream-base-url]
        python proxy.py 8899 https://your-upstream.example
"""
import http.server
import json
import os
import socketserver
import sys
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
if len(sys.argv) < 3:
    raise SystemExit('usage: python proxy.py [port] <upstream-base-url>   e.g. python proxy.py 8899 https://your-upstream.example')
UPSTREAM = sys.argv[2]
LOG = os.path.join(os.environ.get('TEMP', '.'), 'qoder-proxy-capture.log')

SKIP_REQ_HEADERS = {'host', 'content-length', 'connection', 'accept-encoding', 'transfer-encoding'}
SKIP_RESP_HEADERS = {'transfer-encoding', 'content-length', 'connection', 'content-encoding'}


def log_write(text):
    with open(LOG, 'a', encoding='utf-8', errors='replace') as f:
        f.write(text)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def _capture(self, body):
        try:
            text = body.decode('utf-8', errors='replace')
            pretty = text
            try:
                pretty = json.dumps(json.loads(text), ensure_ascii=False, indent=1)
            except Exception:
                pass
            return pretty
        except Exception:
            return '<binary %d bytes>' % len(body or b'')

    def _handle(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        interesting = any(kwd in body for kwd in (b'"image', b'base64', b'input_image', b'multi_content'))
        log_write('\n' + '=' * 90 + '\n')
        log_write('%s %s   [image-ish payload: %s]\n' % (self.command, self.path, interesting))
        for k, v in self.headers.items():
            if k.lower() in ('authorization', 'content-type', 'accept', 'user-agent'):
                log_write('%s: %s\n' % (k, v[:80] + ('...' if len(v) > 80 else '')))
        if body:
            log_write('--- REQUEST BODY ---\n' + self._capture(body)[:200000] + '\n')

        req = urllib.request.Request(UPSTREAM + self.path, data=body if body else None, method=self.command)
        for k, v in self.headers.items():
            if k.lower() in SKIP_REQ_HEADERS:
                continue
            req.add_header(k, v)
        req.add_header('Accept-Encoding', 'identity')
        try:
            resp = urllib.request.urlopen(req, timeout=600)
        except urllib.error.HTTPError as e:
            resp = e
        except Exception as e:
            log_write('!! upstream error: %r\n' % (e,))
            self.send_error(502, str(e))
            return

        self.send_response(resp.status)
        for k, v in resp.headers.items():
            if k.lower() in SKIP_RESP_HEADERS:
                continue
            self.send_header(k, v)
        self.send_header('Connection', 'close')
        self.end_headers()

        total = 0
        collected = b''
        while True:
            read1 = getattr(resp, 'read1', None)
            chunk = read1(8192) if read1 else resp.read(8192)
            if not chunk:
                break
            if len(collected) < 20000:
                collected += chunk
            total += len(chunk)
            try:
                self.wfile.write(chunk)
                self.wfile.flush()
            except Exception as e:
                log_write('!! client write failed: %r\n' % (e,))
                break
        log_write('--- RESPONSE %d bytes (status %s) ---\n' % (total, resp.status))
        try:
            text = collected.decode('utf-8', errors='replace')
            try:
                text = json.dumps(json.loads(text), ensure_ascii=False, indent=1)
            except Exception:
                pass
            log_write(text[:4000] + '\n')
        except Exception:
            log_write('<undecodable response>\n')
        try:
            resp.close()
        except Exception:
            pass

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle
    do_DELETE = _handle


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    log_write('proxy started on 127.0.0.1:%d -> %s\n' % (PORT, UPSTREAM))
    with Server(('127.0.0.1', PORT), Handler) as httpd:
        print('proxy listening on 127.0.0.1:%d -> %s (capture: %s)' % (PORT, UPSTREAM, LOG), flush=True)
        httpd.serve_forever()
