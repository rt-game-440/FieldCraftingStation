"""生成场域合成站的占位贴图（16x16，每档一套顶/侧/底）。

用法：python scripts/gen_textures.py
输出：src/main/resources/assets/fieldcrafting/textures/blocks/station_<tier>_{top,side,bottom}.png
占位贴图以木质工作台为底，按档位的电压颜色着色，后续可由正式美术替换。
"""

import os
import random

from PIL import Image

TIERS = {
    "lv": (0xDC, 0xDC, 0xDC),
    "mv": (0xFF, 0x64, 0x1E),
    "hv": (0xFF, 0xFF, 0x1E),
    "ev": (0x80, 0x80, 0x80),
    "iv": (0xF0, 0xF0, 0xF5),
}
WOOD = (0x9C, 0x7A, 0x45)
WOOD_DARK = (0x6E, 0x52, 0x2C)
OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "src",
    "main",
    "resources",
    "assets",
    "fieldcrafting",
    "textures",
    "blocks",
)


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def planks(rng, tint):
    img = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            base = WOOD_DARK if y % 4 == 3 else WOOD
            n = rng.randint(-8, 8)
            c = tuple(max(0, min(255, v + n)) for v in base)
            img.putpixel((x, y), mix(c, tint, 0.18))
    return img


def frame(img, tint):
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
            img.putpixel((x, y), mix(WOOD_DARK, tint, 0.6))


def make(tier, tint):
    rng = random.Random(tier)
    top = planks(rng, tint)
    frame(top, tint)
    for i in range(3, 13):  # 3x3 合成网格线
        for k in (5, 10):
            top.putpixel((i, k), mix(WOOD_DARK, (0, 0, 0), 0.3))
            top.putpixel((k, i), mix(WOOD_DARK, (0, 0, 0), 0.3))
    for x in range(5, 11):  # 中央着色标记
        for y in range(5, 11):
            top.putpixel((x, y), mix(top.getpixel((x, y)), tint, 0.55))

    side = planks(rng, tint)
    frame(side, tint)
    for x in range(2, 14):  # 底部色带标识档位
        side.putpixel((x, 13), tint)
        side.putpixel((x, 12), mix(tint, WOOD_DARK, 0.4))

    bottom = planks(rng, tint)
    frame(bottom, tint)
    return top, side, bottom


def main():
    os.makedirs(OUT, exist_ok=True)
    for tier, tint in TIERS.items():
        top, side, bottom = make(tier, tint)
        top.save(os.path.join(OUT, "station_%s_top.png" % tier))
        side.save(os.path.join(OUT, "station_%s_side.png" % tier))
        bottom.save(os.path.join(OUT, "station_%s_bottom.png" % tier))
    print("生成完成：%s" % os.path.normpath(OUT))


if __name__ == "__main__":
    main()
