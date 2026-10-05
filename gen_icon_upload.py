# -*- coding: utf-8 -*-
"""
把项目里的旧图标（Android Studio 默认模板：紫底白 ¥）原样渲染成位图，
仅用于华为应用市场后台的「应用图标」上传，不写入 res/、不改动应用内图标。

原矢量（app/src/main/res/drawable/ic_launcher_foreground.xml）：
  圆 r=44 居中 (54,54)，fill #FF6200EE
  ¥ 笔画：M42,28 L54,46 L66,28 / M54,46 L54,80 / M42,54 L66,54 / M42,64 L66,64
         stroke #FFFFFFFF width 6 round cap

华为要求：216x216 或 1024x1024 PNG（3MB 内），尺寸需精确。

⚠️ 输出为 **无透明通道的 RGB，白底**。
   应用市场审核要求图标四周留白，且上传平台会把透明区填充成黑色
   （RGBA 图在华为后台上传预览会出现黑圈），因此这里用白底方形承载，
   紫底图形画在其上，不做任何 Alpha 裁切。
"""
import os
from PIL import Image, ImageDraw

PURPLE = (0x62, 0x00, 0xEE)   # #FF6200EE，与矢量一致
WHITE  = (0xFF, 0xFF, 0xFF)
BG     = (0xFF, 0xFF, 0xFF)   # 图标底色：白
SS = 8                          # 超采样倍数


def make_fullbleed(size):
    """
    满幅实心圆：紫色铺满整个画布，圆心在正中、半径=半边长。
    华为 AGC 上传时会强制套一层圆形 Mask 蒙层（预览图里那句「+ Mask蒙层」），
    圆外区域被填成黑色。若图形四周留白，黑边就会显示成「黑圈」；
    让紫底满幅铺满canvas，黑边被推到画布外由蒙层裁掉，就不会有黑圈。
    「+"号"」小圆点提示是预览 UI 叠加层，不在图内。
    """
    W = size * SS
    img = Image.new("RGB", (W, W), PURPLE)
    d = ImageDraw.Draw(img)

    def s(v):
        return v * W / 108.0

    def S(v):
        return max(1, int(round(v * W / 108.0)))

    # ¥ 笔画等比放大 1.18 倍（满幅圆比留白版视觉更大，需补偿视觉重心）
    k = 1.18
    ox, oy = 54.0, 54.0   # 视口中心

    def P(x, y):
        return (s(ox + (x - ox) * k), s(oy + (y - oy) * k))

    w = S(6 * k)
    d.line([P(42, 28), P(54, 46), P(66, 28)], fill=WHITE, width=w, joint="curve")
    d.line([P(54, 46), P(54, 80)], fill=WHITE, width=w)
    d.line([P(42, 54), P(66, 54)], fill=WHITE, width=w)
    d.line([P(42, 64), P(66, 64)], fill=WHITE, width=w)
    for x, y in [(42, 28), (54, 46), (66, 28), (54, 80), (42, 54), (66, 54), (42, 64), (66, 64)]:
        px, py = P(x, y)
        r = w / 2.0
        d.ellipse([px - r, py - r, px + r, py + r], fill=WHITE)

    return img.resize((size, size), Image.LANCZOS)


def make(size, round_shape):
    """
    按 108x108 视口 1:1 映射绘制。
    round_shape=True → 紫底为圆形（对应手机桌面 adaptive icon 的圆形裁切观感）
    round_shape=False → 紫底为圆角方形
    两者都画在白色方形底上，四周留白约 6%（108 视口 6 单位）。
    """
    W = size * SS
    # 白底：始终不透明，避免上传后透明区被填黑
    img = Image.new("RGB", (W, W), BG)
    d = ImageDraw.Draw(img)

    def s(v):          # 108 视口坐标 → 像素
        return v * W / 108.0

    def S(v):          # 描边宽度换算
        return max(1, int(round(v * W / 108.0)))

    pad = 6            # 四周留白（视口单位）
    lo, hi = s(pad), s(108 - pad)

    # 紫底
    if round_shape:
        d.ellipse([lo, lo, hi, hi], fill=PURPLE)
    else:
        d.rounded_rectangle([lo, lo, hi, hi], radius=int((hi - lo) * 0.22), fill=PURPLE)

    # ¥ 笔画，逐条照搬矢量 pathData（原样，不缩放不改比例）
    w = S(6)
    d.line([(s(42), s(28)), (s(54), s(46)), (s(66), s(28))],
           fill=WHITE, width=w, joint="curve")
    d.line([(s(54), s(46)), (s(54), s(80))], fill=WHITE, width=w)
    d.line([(s(42), s(54)), (s(66), s(54))], fill=WHITE, width=w)
    d.line([(s(42), s(64)), (s(66), s(64))], fill=WHITE, width=w)
    # round line cap 的补圆（StrokeLineCap=round）
    for x, y in [(42, 28), (54, 46), (66, 28), (54, 80), (42, 54), (66, 54), (42, 64), (66, 64)]:
        r = w / 2.0
        d.ellipse([s(x) - r, s(y) - r, s(x) + r, s(y) + r], fill=WHITE)

    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out = "D:/project/Bookkeeping/.workbuddy/artifacts"
    os.makedirs(out, exist_ok=True)
    jobs = [
        ("icon_old_1024.png",         1024, False),
        ("icon_old_1024_round.png",   1024, True),
        ("icon_old_216.png",           216, False),
        ("icon_old_216_round.png",     216, True),
    ]
    for name, sz, rnd in jobs:
        p = os.path.join(out, name)
        im = make(sz, rnd)
        assert im.mode == "RGB", "必须无透明通道，实际 %s" % im.mode
        im.save(p, "PNG", optimize=True)
        print("  %-28s %4dpx  mode=%s  %6.1f KB" % (name, sz, im.mode,
                                                   os.path.getsize(p) / 1024))

    # 满幅圆（推荐上传，规避 AGC 圆形 Mask 的黑圈）
    print("满幅圆版（推荐上传）：")
    for name, sz in [("icon_full_1024.png", 1024), ("icon_full_216.png", 216)]:
        p = os.path.join(out, name)
        im = make_fullbleed(sz)
        assert im.mode == "RGB"
        im.save(p, "PNG", optimize=True)
        print("  %-28s %4dpx  mode=%s  %6.1f KB" % (name, sz, im.mode,
                                                   os.path.getsize(p) / 1024))