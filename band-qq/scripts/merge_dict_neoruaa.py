#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.13.0 键盘换装 NEORUAA/Vela_input_method 后的字库合并脚本。

背景：换装后键盘组件改用 NEORUAA 上游词库体系（assets/dictionary/*.txt，
file.readText + JSON.parse，.txt 扩展名规避 Vela 运行时 202: invalid file type）：
  - cn.txt        {"chars": {音节: 字符串(频序)}, "syllables": [音节...]}
  - words-<l>.txt {"words": {拼音: 词条串}}（dicUtil 按需分片加载）
上游 cn.txt 仅 404 音节约 7.4k 字；BandQQ v2.12.0 已合并出 419 音节 27398 字全量字库
（src/components/InputMethod/assets/dic.js，字符串格式）。

本脚本把 dic.js 全量字库合入 cn.txt：
  - chars：上游字符在前（频序拱顶，决定候选排序），dic.js 补充字符追加去重；
  - syllables：上游与 dic.js 音节并集（上游序在前，新增按字母序）。
words-*.txt 沿用上游词条分片，不做改动（BandQQ dic.js 只有单字无词条）。

用法：python3 scripts/merge_dict_neoruaa.py （在 band-qq/ 目录下执行）
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DICT_TXT = os.path.join(ROOT, 'src', 'components', 'InputMethod', 'assets', 'dictionary', 'cn.txt')
DIC_JS = os.path.join(ROOT, 'scripts', 'dic-legacy-source.js')


def load_dic_js(path):
    """从 dic.js 提取 const dict = {...} 对象。"""
    src = open(path, encoding='utf-8').read()
    m = re.search(r'const\s+dict\s*=\s*(\{.*\})\s*;', src, re.S)
    if not m:
        raise SystemExit('dic.js 中未找到 const dict 定义')
    return json.loads(m.group(1))


def main():
    upstream = json.load(open(DICT_TXT, encoding='utf-8'))
    up_chars = upstream.get('chars', {})
    up_syll = list(upstream.get('syllables', []))

    extra = load_dic_js(DIC_JS)
    print('上游 chars 音节数: %d, syllables: %d' % (len(up_chars), len(up_syll)))
    print('dic.js  音节数: %d' % len(extra))

    merged = {}
    order = []
    for k, v in up_chars.items():
        merged[k] = v
        order.append(k)
    added_syll, added_chars = 0, 0
    for k in sorted(extra.keys()):
        s = extra[k]
        if k in merged:
            seen = set(merged[k])
            add = ''.join(c for c in s if c not in seen and not c.isspace())
            if add:
                merged[k] += add
                added_chars += len(add)
        else:
            merged[k] = s
            order.append(k)
            added_syll += 1
            added_chars += len(s)

    # syllables 并集：上游序在前，新音节按字母序追加
    syll = list(up_syll)
    have = set(syll)
    for k in sorted(merged.keys()):
        if k not in have:
            syll.append(k)

    out = {'chars': {k: merged[k] for k in order}, 'syllables': syll}
    with open(DICT_TXT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))

    total = sum(len(v) for v in out['chars'].values())
    print('合并完成: 音节 %d（新增 %d）, 总字数 %d（新增 %d）'
          % (len(out['chars']), added_syll, total, added_chars))
    # 自校验：重新解析 + 频序拱顶抽查
    chk = json.load(open(DICT_TXT, encoding='utf-8'))
    assert chk['chars']['a'].startswith('阿啊'), '频序拱顶丢失'
    assert len(chk['syllables']) >= len(chk['chars']), 'syllables 应覆盖全部音节'
    for s in list(chk['chars'])[:50]:
        assert s in chk['syllables'], '音节表缺 %s' % s
    print('自校验 PASS（JSON 可解析/频序拱顶保留/音节表覆盖）')


if __name__ == '__main__':
    sys.exit(main())
