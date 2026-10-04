#!/usr/bin/env python3
"""手环图标深色化：icon_preview.png（白底）→ 深色背景版 src/common/icon.png

原则（v2.1.0 图标白边修复）：
- 输出 108x108 纯 RGB（无 alpha 通道），全出血方形，杜绝 Vela 启动器合成露白
- 只替换「与画布边缘连通的白色背景」，保留蓝圆/白气泡/企鹅前景
"""
from PIL import Image
from collections import deque

SRC = "/home/z/band-qq-v2/icon_preview.png"
DST_WATCH = "/home/z/band-qq-v2/band-qq/src/common/icon.png"
DST_PREVIEW = "/home/z/band-qq-v2/icon_preview.png"

DARK = (13, 16, 21)  # #0D1015 近黑冷灰，AMOLED 手环背景融合

im = Image.open(SRC).convert("RGBA")
w, h = im.size
px = im.load()


def is_whiteish(p):
    r, g, b, a = p
    return a > 200 and r > 235 and g > 235 and b > 235


# BFS flood fill：从四边开始的连通白色区域 → 深色
visited = [[False] * w for _ in range(h)]
q = deque()
for x in range(w):
    for y in (0, h - 1):
        if is_whiteish(px[x, y]) and not visited[y][x]:
            visited[y][x] = True
            q.append((x, y))
for y in range(h):
    for x in (0, w - 1):
        if is_whiteish(px[x, y]) and not visited[y][x]:
            visited[y][x] = True
            q.append((x, y))

# 预填：圆角方形外的透明角也直接变深色（全出血）
for y in range(h):
    for x in range(w):
        if px[x, y][3] < 200:
            px[x, y] = (*DARK, 255)
            visited[y][x] = True

while q:
    x, y = q.popleft()
    px[x, y] = (*DARK, 255)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < w and 0 <= ny < h and not visited[ny][nx] and is_whiteish(px[nx, ny]):
            visited[ny][nx] = True
            q.append((nx, ny))

out = im.convert("RGB")
out.save(DST_PREVIEW)
watch = out.resize((108, 108), Image.LANCZOS)
watch.save(DST_WATCH, "PNG")

# 验证：四角必须全深色且无 alpha
chk = Image.open(DST_WATCH)
print("size:", chk.size, "mode:", chk.mode)
print("corners:", chk.getpixel((0, 0)), chk.getpixel((107, 0)), chk.getpixel((0, 107)), chk.getpixel((107, 107)))
print("center:", chk.getpixel((54, 54)))
