"""把 Qoder 设置里指定模型的 Base URL 临时指向本地抓包代理（需先关闭 IDEA）。

用法：
    # 1) 先起代理（默认 127.0.0.1:8899）
    python proxy.py 8899 https://<你的真实中转站>

    # 2) 把两个模型临时指到代理（会自动备份设置文件，并把原始值记到 sidecar）
    python prep-proxy-test.py gpt-5.6-sol gpt-5.6-terra

      第一个模型：保持原接口类型（例如 OpenAI Responses）
      第二个模型：额外把 byokTypeKey 改成 openai-compatible，用来对比服务端认不认 format

    # 3) 测完还原
    python prep-proxy-test.py --revert
"""
import json
import os
import re
import shutil
import sys

XML = os.path.join(os.environ['APPDATA'], 'JetBrains', 'IntelliJIdea2026.2', 'options', 'qoder_setting.xml')
BAK = XML + '.proxy-test.bak'
SIDE = XML + '.proxy-test.json'
PROXY = 'http://127.0.0.1:8899/v1'


def blocks(text):
    return re.split(r'(<ByokModelConfig>.*?</ByokModelConfig>)', text, flags=re.S)


def model_key_of(block):
    m = re.search(r'<option name="modelKey" value="([^"]+)"', block)
    return m.group(1) if m else None


def option_value(block, name):
    m = re.search(r'<option name="%s" value="([^"]*)"' % name, block)
    return m.group(1) if m else None


def rewrite_base_url(block, new_url):
    return re.sub(r'("base_url":")([^"]*)(")', lambda m: m.group(1) + new_url + m.group(3), block)


def main():
    if not os.path.exists(XML):
        raise SystemExit('settings xml not found: %s' % XML)
    text = open(XML, encoding='utf-8', errors='replace').read()

    if '--revert' in sys.argv:
        if not os.path.exists(SIDE):
            raise SystemExit('no sidecar file: %s' % SIDE)
        saved = json.load(open(SIDE, encoding='utf-8'))
        parts = blocks(text)
        for i, part in enumerate(parts):
            if not part.startswith('<ByokModelConfig>'):
                continue
            key = model_key_of(part)
            if key in saved:
                if saved[key].get('base_url'):
                    part = rewrite_base_url(part, saved[key]['base_url'])
                if saved[key].get('byokTypeKey'):
                    part = re.sub(r'<option name="byokTypeKey" value="[^"]*" />',
                                  '<option name="byokTypeKey" value="%s" />' % saved[key]['byokTypeKey'], part)
                parts[i] = part
        open(XML, 'w', encoding='utf-8', newline='').write(''.join(parts))
        print('reverted:', ', '.join(sorted(saved)))
        return

    models = [a for a in sys.argv[1:] if not a.startswith('-')]
    if len(models) < 2:
        raise SystemExit('usage: python prep-proxy-test.py <model-keep-type> <model-change-type>')
    keep_type, change_type = models[0], models[1]

    if not os.path.exists(BAK):
        shutil.copy2(XML, BAK)
        print('backup written: %s' % BAK)

    saved = json.load(open(SIDE, encoding='utf-8')) if os.path.exists(SIDE) else {}
    parts = blocks(text)
    touched = []
    for i, part in enumerate(parts):
        if not part.startswith('<ByokModelConfig>'):
            continue
        key = model_key_of(part)
        if key not in (keep_type, change_type):
            continue
        m = re.search(r'"base_url":"([^"]*)"', part)
        saved[key] = {'base_url': m.group(1) if m else None,
                      'byokTypeKey': option_value(part, 'byokTypeKey')}
        part = rewrite_base_url(part, PROXY)
        if key == change_type and saved[key]['byokTypeKey']:
            part = re.sub(r'<option name="byokTypeKey" value="[^"]*" />',
                          '<option name="byokTypeKey" value="openai-compatible" />', part)
        parts[i] = part
        touched.append(key)

    if not touched:
        raise SystemExit('no target models found in the settings xml')
    open(XML, 'w', encoding='utf-8', newline='').write(''.join(parts))
    json.dump(saved, open(SIDE, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('patched:', ', '.join(touched), '-> base_url =', PROXY)
    print('now start IDEA and send an image with %s first, then with %s' % (keep_type, change_type))


if __name__ == '__main__':
    main()
