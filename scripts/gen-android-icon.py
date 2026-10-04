#!/usr/bin/env python3
"""Android adaptive icon 前景 PNG：蓝圆+气泡+企鹅（去深色外底→透明），深色背景由 vector 提供。

安全区规范：108dp 画布，前景图形须在中心 66dp 内 → 432px 画布上图形直径 ~285px。
"""
from PIL import Image

SRC = "/home/z/band-qq-v2/icon_preview.png"  # 已是深色底版本
OUT = "/home/z/band-qq-v2/android-sync/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
DARK = (13, 16, 21)

im = Image.open(SRC).convert("RGBA")
w, h = im.size
px = im.load()

# 深色底 → 透明（保留蓝圆、气泡、企鹅；企鹅眼白等浅色保留）
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if abs(r - DARK[0]) <= 6 and abs(g - DARK[1]) <= 6 and abs(b - DARK[2]) <= 6:
            px[x, y] = (0, 0, 0, 0)

# 裁掉透明边，得到图形包围盒
bbox = im.getbbox()
glyph = im.crop(bbox)

# 432 画布，图形占 66%（285px）
canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
target = 285
ratio = target / max(glyph.size)
new_size = (int(glyph.size[0] * ratio), int(glyph.size[1] * ratio))
glyph = glyph.resize(new_size, Image.LANCZOS)
canvas.paste(glyph, ((432 - new_size[0]) // 2, (432 - new_size[1]) // 2), glyph)
canvas.save(OUT, "PNG")
print("saved", OUT, canvas.size, "glyph:", new_size)
