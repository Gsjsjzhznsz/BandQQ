#!/usr/bin/env python3
"""图标重建 v2.4.5：根治「白色描边环」白边问题。

问题溯源：母版蓝圆外围有一圈设计用的白色描边环（宽 ~4px + 抗锯齿过渡带），
v2.1.0 的洪水填充只处理「与画布边缘连通的白色背景」，白色闭环环带不在连通域内，
导致手环圆形蒙版边缘与 Android 自适应图标前景都残留一圈白边。

修复：以蓝圆为基准做圆形裁切（半径取到环带以内），羽化边缘后重新合成到
#0D1015 全出血深底。输出三端产物：
- icon_preview.png（512 母版，RGB 深底全出血）
- band-qq/src/common/icon.png（108 手环，RGB 全出血）
- android mipmap-xxxhdpi/ic_launcher_foreground.png（432 前景，图形 66% 安全区）

用法：python3 scripts/rebuild-icon.py  （依赖 Pillow）
"""
from PIL import Image, ImageDraw, ImageFilter

MASTER = "/home/z/band-qq-v2/icon_preview.png"
DST_WATCH = "/home/z/band-qq-v2/band-qq/src/common/icon.png"
DST_ANDROID = (
    "/home/z/band-qq-v2/android-sync/app/src/main/res/"
    "mipmap-xxxhdpi/ic_launcher_foreground.png"
)
DARK = (13, 16, 21)  # #0D1015

SRC_SIZE = 512          # 工作画布
OUT_CIRCLE_RATIO = 0.87  # 蓝圆半径 / 半画布（边缘留深色安全环，白边无从产生）
FEATHER_PX = 3           # 蓝圆边缘羽化（SRC_SIZE 坐标系）

im = Image.open(MASTER).convert("RGB")

# ---- 1. 检测蓝圆几何（蓝像素 bbox；白环/深底/企鹅眼白都被排除）----
w, h = im.size
px = im.load()
min_x, min_y, max_x, max_y = w, h, 0, 0
for y in range(h):
    for x in range(w):
        r, g, b = px[x, y]
        if b - r > 25 and b > 90:  # 蓝色系（渐变浅蓝→蓝都满足）
            min_x = min(min_x, x)
            max_x = max(max_x, x)
            min_y = min(min_y, y)
            max_y = max(max_y, y)
cx, cy = (min_x + max_x) / 2, (min_y + max_y) / 2
r_blue = max(max_x - min_x, max_y - min_y) / 2 - 4.5  # 内缩，跳过白环及其过渡带

# ---- 2. 圆形羽化裁切（去白环），放大到工作画布 ----
mask = Image.new("L", (w, h), 0)
ImageDraw.Draw(mask).ellipse(
    (cx - r_blue, cy - r_blue, cx + r_blue, cy + r_blue), fill=255
)
mask = mask.filter(ImageFilter.GaussianBlur(1.2))  # 256 坐标系轻羽化
glyph = Image.new("RGB", (w, h), DARK)
glyph.paste(im, (0, 0), mask)
glyph = glyph.resize((SRC_SIZE, SRC_SIZE), Image.LANCZOS)

# ---- 3. 合成：深底 + 缩放后的圆内容（统一留出安全环）----
canvas = Image.new("RGB", (SRC_SIZE, SRC_SIZE), DARK)
target_r = SRC_SIZE / 2 * OUT_CIRCLE_RATIO
scale = target_r / (r_blue / w * SRC_SIZE)
new_size = int(SRC_SIZE * scale)
content = glyph.resize((new_size, new_size), Image.LANCZOS)
canvas.paste(content, ((SRC_SIZE - new_size) // 2, (SRC_SIZE - new_size) // 2))
canvas.save(MASTER, "PNG")

# ---- 4. 手环 108 全出血 ----
canvas.resize((108, 108), Image.LANCZOS).save(DST_WATCH, "PNG")

# ---- 5. Android 前景：图形=圆内容（深底→透明），432 画布 66% 安全区 ----
alpha = Image.new("L", (SRC_SIZE, SRC_SIZE), 0)
d = ImageDraw.Draw(alpha)
d.ellipse(
    (
        SRC_SIZE / 2 - target_r,
        SRC_SIZE / 2 - target_r,
        SRC_SIZE / 2 + target_r,
        SRC_SIZE / 2 + target_r,
    ),
    fill=255,
)
alpha = alpha.filter(ImageFilter.GaussianBlur(FEATHER_PX))
fg = canvas.convert("RGBA")
fg.putalpha(alpha)
bbox = fg.getbbox()
glyph_fg = fg.crop(bbox)
target = 285
ratio = target / max(glyph_fg.size)
new_size_fg = (int(glyph_fg.size[0] * ratio), int(glyph_fg.size[1] * ratio))
glyph_fg = glyph_fg.resize(new_size_fg, Image.LANCZOS)
fg_canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
fg_canvas.paste(
    glyph_fg, ((432 - new_size_fg[0]) // 2, (432 - new_size_fg[1]) // 2), glyph_fg
)
fg_canvas.save(DST_ANDROID, "PNG")
print("saved:", MASTER, DST_WATCH, DST_ANDROID, "fg:", new_size_fg)
