#!/usr/bin/env python3
"""扩展手环输入法字库：GB2312 6763 → 通用规范汉字表 8105 + 网络常用字
数据源: iDvel/The-Table-of-General-Standard-Chinese-Characters (8105字+全部读音)
输出: 覆盖 band-qq/src/components/InputMethod/assets/dic.js (保持原格式与候选顺序)
"""
import json, re, unicodedata, sys

DICC = '/home/z/my-project/study-band-qq-assistant/band-qq/src/components/InputMethod/assets/dic.js'
TABLE2 = '/tmp/The-Table-of-General-Standard-Chinese-Characters/2-汉字+多种发音（带声调）.txt'

# ---------- 1. 读取现有词典 ----------
src = open(DICC, encoding='utf-8').read()
m = re.search(r'const dict = (\{.*?\});', src, re.S)
old_dict = json.loads(m.group(1))
old_keys = list(old_dict.keys())          # 保持原 key 顺序
existing_chars = set(''.join(old_dict.values()))
print(f'现有: {len(old_keys)} 音节 / {len(existing_chars)} 字')

# ---------- 2. 读音处理 ----------
def strip_tone(s):
    """去声调 + ü→v（匹配现有词典约定）"""
    nfd = unicodedata.normalize('NFD', s.strip())
    out = ''.join(c for c in nfd if not unicodedata.combining(c))
    out = out.replace('ü', 'v').replace('Ǜ', 'v').lower()
    return out

def valid_key(k):
    return re.fullmatch(r'[a-z]{1,6}', k) is not None

# ---------- 3. 解析 8105 字表（全部读音，按表序=常用度） ----------
entries = []  # [(char, [py1, py2...]), ...]
for line in open(TABLE2, encoding='utf-8'):
    parts = [p.strip() for p in line.split('\t') if p.strip()]
    if len(parts) < 3:
        continue
    ch, readings = parts[1], parts[2].split()
    keys = []
    for r in readings:
        k = strip_tone(r)
        if k == 'ê':
            k = 'ei'          # 欸 的 ê 归入 ei
        if valid_key(k) and k not in keys:
            keys.append(k)
    if ch and keys:
        entries.append((ch, keys))
print(f'字表: {len(entries)} 字')

# ---------- 4. 计算新增并合并 ----------
new_dict = {k: list(old_dict[k]) for k in old_keys}   # value 用 list 便于去重
new_keys_order = []

def add_char(ch, key):
    if key not in new_dict:
        new_dict[key] = []
        new_keys_order.append(key)
    if ch not in new_dict[key]:
        new_dict[key].append(ch)

added = 0
for ch, keys in entries:
    if ch in existing_chars:
        continue
    for k in keys:
        add_char(ch, k)
    added += 1

# ---------- 5. 网络常用字补充（不在规范字表内的流行字） ----------
net_extra = {
    '囧': ['jiong'], '烎': ['yin'], '槑': ['mei'], '氼': ['ni'],
    '嫑': ['biao'], '嘦': ['jiao'], '兲': ['tian'], '巭': ['gu'],
    '悈': ['jian'], '虋': ['men'], '乜': ['mie', 'nie'], '嘂': ['jiao'],
    '叧': ['gua'], '乩': ['ji'], '哏': ['gen', 'hen'],
}
for ch, keys in net_extra.items():
    if ch not in existing_chars:
        for k in keys:
            add_char(ch, k)
        added += 1

# 欸 读音别名（表内主音可能生僻）
for ch, alias_keys in [('欸', ['ai', 'ei', 'e']), ('啰', ['luo', 'lo']),
                       ('喔', ['wo', 'o']), ('噢', ['o', 'yo']),
                       ('嗯', ['en', 'ng']), ('哦', ['o', 'e'])]:
    if ch not in existing_chars:
        for k in alias_keys:
            add_char(ch, k)

# ---------- 6. 序列化（原 key 顺序 + 新 key 按首现顺序） ----------
final = {}
for k in old_keys:
    final[k] = ''.join(new_dict[k])
for k in new_keys_order:
    final[k] = ''.join(new_dict[k])

total_chars = len(set(''.join(final.values())))
print(f'新增 {added} 字 → 共 {len(final)} 音节 / {total_chars} 字')

# ---------- 7. 写回 dic.js（保持原文件结构） ----------
header = '''/**
 * 收录常用汉字（GB2312 6763 字 + 《通用规范汉字表》扩补 + 网络常用字），
 * 不支持声调，支持多音字，同一拼音下按使用频率排序
 */
'''
json_str = json.dumps(final, ensure_ascii=False, separators=(',', ':'))
out = header + 'const dict = ' + json_str + ';\n\nexport { dict }\n'
open(DICC, 'w', encoding='utf-8').write(out)
print(f'写入 {DICC} ({len(out)} bytes)')

# ---------- 8. 抽查验证 ----------
spot = ['囧', '尬', '怼', '欸', '啰', '嗯', '塆', '畀']
inv = {}
for k, v in final.items():
    for c in v:
        inv.setdefault(c, k)
for c in spot:
    print(f'  {c} -> {inv.get(c, "缺失!")}')
