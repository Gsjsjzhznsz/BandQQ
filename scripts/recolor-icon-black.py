#!/usr/bin/env python3
"""v2.7.0 手环图标纯黑化：背景 (13,16,21) #0D1015 → (0,0,0) 纯黑。

原因：手环桌面（AMOLED）背景为纯黑，图标底色 #0D1015 偏蓝灰，
仔细对比可见「两个黑色不一样」。直接做通道级重着色：
- 主体像素：与 (13,16,21) 距离 < 26 的视为背景 → 置 (0,0,0)
- 边缘过渡像素（蓝圆→背景的抗锯齿带）：按距背景的接近程度线性压暗，
  避免出现 13→0 的可见色阶环。

产物：icon_preview.png（512 母版）+ band-qq/src/common/icon.png（108 手环）。
Android 自适应图标前景为透明背景（circle alpha），不受影响，不改。
"""
from PIL import Image
import numpy as np

PATHS = [
    "/home/z/band-qq-v2/icon_preview.png",
    "/home/z/band-qq-v2/band-qq/src/common/icon.png",
]
OLD = np.array([13, 16, 21], dtype=np.float64)
NEAR = 26.0   # 距离小于此值视为纯背景
FAR = 90.0    # 距离大于此值完全保留

for path in PATHS:
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.float64)
    dist = np.sqrt(((a - OLD) ** 2).sum(axis=2))
    # t=1 完全是旧背景 → 纯黑；t=0 远离背景 → 原样；中间线性压暗
    t = np.clip((FAR - dist) / (FAR - NEAR), 0.0, 1.0)
    # 背景区完全替换，过渡区按比例向黑压暗（保持色相比例，直接乘 (1-t) 会把
    # 暗部拉向 0，视觉上等价于把过渡带融入纯黑底）
    out = a * (1.0 - (t * t * 0.5)[..., None])  # 过渡带轻压
    full_bg = dist < NEAR
    out[full_bg] = [0.0, 0.0, 0.0]
    Image.fromarray(out.astype(np.uint8), "RGB").save(path, "PNG")
    n = int(full_bg.sum())
    print(f"{path}: recolored {n}px background -> #000000")
print("done")
