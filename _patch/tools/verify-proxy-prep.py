"""打印设置文件里两个模型的当前 base_url / 接口类型（用来确认代理测试的临时改动生效）。"""
import os
import re
import sys

XML = os.path.join(os.environ['APPDATA'], 'JetBrains', 'IntelliJIdea2026.2', 'options', 'qoder_setting.xml')
models = sys.argv[1:] or ['gpt-5.6-sol', 'gpt-5.6-terra']

text = open(XML, encoding='utf-8', errors='replace').read()
for block in re.split(r'(?=<ByokModelConfig>)', text):
    if not block.startswith('<ByokModelConfig>'):
        continue
    mk = re.search(r'<option name="modelKey" value="([^"]+)"', block)
    if not mk or mk.group(1) not in models:
        continue
    url = re.search(r'base_url&quot;:&quot;([^&]*)', block) or re.search(r'"base_url":"([^"]*)"', block)
    typ = re.search(r'<option name="byokTypeKey" value="([^"]*)"', block)
    print('  %-16s type=%-18s base_url=%s' % (mk.group(1), typ.group(1) if typ else '?', url.group(1) if url else '?'))
