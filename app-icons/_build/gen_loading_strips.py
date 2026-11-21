# -*- coding: utf-8 -*-
"""
从 5 张 App 截图生成「加载中」滚动海报素材。
每张截图上下裁掉（去标题栏 / 分类栏 / 底部按钮栏），只留中间海报区，
缩放成统一宽度的条带，再拼成两张顺序不同、亮度不同的长胶卷图。

运行时 App 用 BitmapShader(REPEAT_Y) 让两张图以不同速度上下循环滚动并叠加，
形成「正在加载」的动态背景，避免空白等待。

输出：
  source/app/src/main/res/drawable-nodpi/loading_strip_a.jpg
  source/app/src/main/res/drawable-nodpi/loading_strip_b.jpg
  app-icons/_build/loading_effect_preview.png   （静态效果预览，不进 APK）
"""
import os
import glob
from PIL import Image, ImageDraw, ImageFont, ImageEnhance, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(KIT, "source", "app", "src", "main", "res", "drawable-nodpi")
SHOTS = sorted(glob.glob(os.path.join(
    os.path.expanduser("~"), ".workbuddy", "clipboard-images",
    "clipboard-2026-11-16T04-59-1*.jpg")))

W = 960                # 条带宽度（App 端会等比放大铺满屏）
STRIP_H = 356          # 每条统一高度
TOP_R, BOT_R = 0.24, 0.90   # 上下裁掉的比例
FADE = 26              # 上下渐变淡出像素，消除平铺接缝
BG = (10, 20, 36)      # #0A1424 深蓝黑，与 App 背景一致

ORDER_A = [0, 2, 4, 1, 3]
ORDER_B = [3, 1, 0, 4, 2]


def fade_edges(img):
    """上下 FADE 像素渐变到背景色，平铺循环时接缝不突兀。"""
    top = Image.linear_gradient("L").resize((W, FADE))
    bot = top.transpose(Image.FLIP_TOP_BOTTOM)
    mask = Image.new("L", (W, STRIP_H), 255)
    mask.paste(top, (0, 0))
    mask.paste(bot, (0, STRIP_H - FADE))
    bg = Image.new("RGB", (W, STRIP_H), BG)
    return Image.composite(img, bg, mask)


def make_strips():
    if len(SHOTS) < 5:
        raise RuntimeError("找不到 5 张截图，实际找到 %d 张：%s" % (len(SHOTS), SHOTS))
    out = []
    for p in SHOTS:
        im = Image.open(p).convert("RGB")
        w, h = im.size
        c = im.crop((0, int(h * TOP_R), w, int(h * BOT_R)))
        th = int(round(c.height * W / float(c.width)))
        c = c.resize((W, th), Image.LANCZOS)
        # 居中裁到统一高度
        y = max(0, (c.height - STRIP_H) // 2)
        c = c.crop((0, y, W, y + STRIP_H))
        out.append(fade_edges(c))
        print("  crop:", os.path.basename(p), im.size, "->", (W, STRIP_H))
    return out


def roll(strips, order, bright, blur):
    img = Image.new("RGB", (W, STRIP_H * len(order)), BG)
    for i, idx in enumerate(order):
        s = strips[idx]
        if bright != 1.0:
            s = ImageEnhance.Brightness(s).enhance(bright)
        if blur:
            s = s.filter(ImageFilter.GaussianBlur(blur))
        img.paste(s, (0, i * STRIP_H))
    return img


def preview(a, b):
    """静态模拟一帧：两层不同位移叠加 + 暗罩 + 加载文案。"""
    w, h = 960, 540
    pa = a.crop((0, 120, W, 120 + h)).resize((w, h), Image.LANCZOS)
    pb = b.crop((0, 700, W, 700 + h)).resize((w, h), Image.LANCZOS)
    base = Image.blend(Image.blend(pa, pb, 0.42), Image.new("RGB", (w, h), BG), 0.52)
    d = ImageDraw.Draw(base)
    # 中央胶囊标签
    bw, bh = 330, 74
    bx, by = (w - bw) // 2, (h - bh) // 2
    d.rounded_rectangle((bx, by, bx + bw, by + bh), radius=16,
                        fill=(9, 18, 34, 235), outline=(34, 211, 238, 220), width=2)
    fp = r"C:\Windows\Fonts\msyhbd.ttc"
    f = ImageFont.truetype(fp, 28) if os.path.isfile(fp) else ImageFont.load_default()
    txt = "正在加载影片…"
    bb = d.textbbox((0, 0), txt, font=f)
    d.text((bx + (bw - (bb[2] - bb[0])) // 2, by + (bh - (bb[3] - bb[1])) // 2 - bb[1]),
           txt, font=f, fill=(235, 245, 255))
    # 底部青色进度条
    d.rounded_rectangle((120, h - 34, w - 120, h - 28), radius=3, fill=(255, 255, 255, 60))
    d.rounded_rectangle((120, h - 34, 120 + int((w - 240) * 0.38), h - 28),
                        radius=3, fill=(34, 211, 238))
    return base


def main():
    os.makedirs(OUT, exist_ok=True)
    strips = make_strips()
    a = roll(strips, ORDER_A, 1.00, 0)
    b = roll(strips, ORDER_B, 0.78, 0.7)
    pa = os.path.join(OUT, "loading_strip_a.jpg")
    pb = os.path.join(OUT, "loading_strip_b.jpg")
    a.save(pa, "JPEG", quality=82, optimize=True)
    b.save(pb, "JPEG", quality=82, optimize=True)
    print("  ->", pa, os.path.getsize(pa) // 1024, "KB", a.size)
    print("  ->", pb, os.path.getsize(pb) // 1024, "KB", b.size)
    pp = os.path.join(HERE, "loading_effect_preview.png")
    preview(a, b).save(pp)
    print("  ->", pp)
    print("DONE")


if __name__ == "__main__":
    main()
