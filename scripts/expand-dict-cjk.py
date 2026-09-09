#!/usr/bin/env python3
"""v1.1.1 输入法字库全量扩展：常用字 8241 → CJK 基本区全量（U+4E00–U+9FFF，约 2 万字）

数据源: Unicode 官方 Unihan 数据库 Unihan_Readings.txt 的 kMandarin 字段（普通话读音）
策略:
  1. 保留现有 dic.js 全部内容（8241 字的候选顺序 = 使用频率序，不动）；
  2. 遍历 CJK 基本区 U+4E00–U+9FFF，凡现有字典未收录的汉字，
     按 kMandarin 读音（去声调、ü→v）追加到对应音节候选末尾；
  3. 多音字生成多条读音；无 kMandarin 读音的字跳过并统计。
输出: 覆盖 band-qq/src/components/InputMethod/assets/dic.js（保持原文件结构与格式）
用法: python3 expand-dict-cjk.py <Unihan_Readings.txt路径>
"""
import json
import re
import sys
import unicodedata

DICC = 'band-qq/src/components/InputMethod/assets/dic.js'
UNIHAN = sys.argv[1] if len(sys.argv) > 1 else '/tmp/unihan/Unihan_Readings.txt'

# ---------- 1. 读取现有词典 ----------
src = open(DICC, encoding='utf-8').read()
m = re.search(r'const dict = (\{.*?\});', src, re.S)
old_dict = json.loads(m.group(1))
old_keys = list(old_dict.keys())  # 保持原 key 顺序
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

# ---------- 3. 解析 Unihan kMandarin（CJK 基本区 U+4E00–U+9FFF） ----------
readings = {}  # char -> [py1, py2...]
for line in open(UNIHAN, encoding='utf-8'):
    line = line.rstrip('\n')
    if not line or line.startswith('#'):
        continue
    parts = line.split('\t')
    if len(parts) < 3 or parts[1] != 'kMandarin':
        continue
    cp = parts[0]
    mm = re.fullmatch(r'U\+([0-9A-F]{4,6})', cp)
    if not mm:
        continue
    code = int(mm.group(1), 16)
    # CJK 统一表意文字基本区（含 Ext 块中混排的 9FA6–9FFF 收尾段）
    if not (0x4E00 <= code <= 0x9FFF):
        continue
    ch = chr(code)
    keys = []
    # 兼容两种格式："pīn" 或旧版 "TRADITIONAL pīn1 SIMPLIFIED pīn1"
    val = parts[2]
    sm = re.search(r'SIMPLIFIED\s+(\S+)', val)
    if sm:
        val = sm.group(1)
    for r in val.split():
        k = strip_tone(r)
        if valid_key(k) and k not in keys:
            keys.append(k)
    if ch and keys:
        readings[ch] = keys

print(f'Unihan 基本区带读音: {len(readings)} 字')

# ---------- 4. 合并：现有字不动，新字追加到对应音节末尾 ----------
new_dict = {k: old_dict[k] for k in old_keys}
new_keys_order = []

def add_char(ch, key):
    if key not in new_dict:
        new_dict[key] = []
        new_keys_order.append(key)
    if ch not in new_dict[key]:
        new_dict[key] += ch

added = 0
no_reading = []
for code in range(0x4E00, 0xA000):
    ch = chr(code)
    if ch in existing_chars:
        continue
    keys = readings.get(ch)
    if not keys:
        no_reading.append(ch)
        continue
    for k in keys:
        add_char(ch, k)
    added += 1

print(f'新增 {added} 字；基本区内无普通话读音跳过 {len(no_reading)} 字')

# ---------- 5. 序列化（原 key 顺序 + 新 key 按首现顺序） ----------
final = {}
for k in old_keys:
    v = new_dict[k]
    final[k] = ''.join(v) if isinstance(v, list) else v
for k in new_keys_order:
    final[k] = ''.join(new_dict[k])

total_chars = len(set(''.join(final.values())))
print(f'合计: {len(final)} 音节 / {total_chars} 字')

# ---------- 6. 写回 dic.js（保持原文件结构） ----------
header = '''/**
 * v1.1.1 全量字库：CJK 基本区 U+4E00–U+9FFF 全量汉字
 * （常用字候选序 = 使用频率序；全量字覆盖来自 Unicode Unihan kMandarin 读音），
 * 不支持声调，支持多音字，同一拼音下常用字在前
 */
'''
json_str = json.dumps(final, ensure_ascii=False, separators=(',', ':'))
out = header + 'const dict = ' + json_str + ';\n\nexport { dict }\n'
open(DICC, 'w', encoding='utf-8').write(out)
print(f'写入 {DICC} ({len(out)} bytes)')

# ---------- 7. 抽查验证 ----------
spot = ['囧', '尬', '怼', '欸', '啰', '嗯', '燚', '㙟', '齉', '龘']
inv = {}
for k, v in final.items():
    for c in v:
        inv.setdefault(c, k)
for c in spot:
    print(f'  {c} -> {inv.get(c, "缺失!")}')
