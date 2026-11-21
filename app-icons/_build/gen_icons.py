# -*- coding: utf-8 -*-
"""
致强视频 Android TV 三款图标生成器 —— 「强」字版
纯 Pillow 实现，4x 超采样抗锯齿；汉字用系统真实字体渲染，
彻底修正 AI 生成图的错笔画问题。

2026-11-08 改版：品牌符号由「致」统一改为「强」。
「强」字笔画更密、外框更方，同字号下视觉更满，故三款同步下调字号
并重排青色播放三角，避免压笔画 / 挤到品牌名。APP_NAME 仍为「致强视频」。

输出：
  01-square/       正方形 1:1  512 / 256 / 128 + 焦点态 + 直角版
  02-banner-16x9/  横版 16:9   640x360 / 320x180 / 1280x720 + 焦点态
  03-wide-3x1/     超宽 3:1    960x320 / 480x160 / 1920x640 + 焦点态 + NEW 版
  android-res/     可直接拷入 Android 工程的各密度 ic_launcher / banner
  variants/        浅色底备用、透明底带描边
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

# 输出根目录 = 本脚本所在 _build/ 的上一级，脚本放到哪套 app-icons 就重出哪套，
# 不再写死绝对路径（历史上曾误生成到上级 DuShetv/app-icons）。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"   # 微软雅黑 Bold
FONT_MED = r"C:\Windows\Fonts\msyh.ttc"     # 微软雅黑 Regular

# ---------------- 品牌色 ----------------
BG_DEEP = (11, 27, 58)       # #0B1B3A 底色
BG_MAIN = (30, 58, 138)      # #1E3A8A 主色
BG_LIT = (40, 92, 184)       # 左下亮端（仅占小面积，整体仍保持深蓝）
CYAN = (34, 211, 238)        # #22D3EE 辅色
WHITE = (255, 255, 255)
SUBTLE = (163, 194, 230)     # slogan / 辅助文字

APP_NAME = "致强视频"
SLOGAN = "好片随心看"
GLYPH = "强"                  # 品牌符号字（三款 PNG + 三份 SVG 共用此一处）

# 「强」字版字号系数：比原「致」字版小一档，给右下青三角与相邻文字留呼吸区
SQ_GLYPH = 0.45               # 正方形：占画布边长比例
BN_GLYPH = 0.41               # 16:9：占画布高度比例
WD_GLYPH = 0.42               # 3:1 左端方块：占方块边长比例

SS = 4                        # 超采样倍数


# ---------------- 基础工具 ----------------
def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def diag_bg(w, h):
    """45° 对角渐变底：右上深蓝 -> 左下品牌蓝。用平行线绘制，无需 numpy。"""
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    span = w + h
    step = max(1, SS)
    for k in range(-h, w + 1, step):
        t = (k + h) / float(span)
        # 左下(t=0)亮蓝 -> 中间主色 -> 右上(t=1)深蓝：
        # 让右侧文字区更暗，白字对比更强，也贴近参考图的对角分割
        if t < 0.5:
            c = lerp(BG_LIT, BG_MAIN, t / 0.5)
        else:
            c = lerp(BG_MAIN, BG_DEEP, (t - 0.5) / 0.5)
        d.line([(k, 0), (k + h, h)], fill=c, width=step * 2)
    # 一条柔和的斜向高光带，呼应参考图的对角分割但更克制
    band = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    cxb = int(w * 0.42)
    bd.line([(cxb, 0), (cxb - h, h)], fill=(96, 158, 240, 26), width=int(h * 0.16))
    band = band.filter(ImageFilter.GaussianBlur(h * 0.055))
    img = Image.alpha_composite(img.convert("RGBA"), band)
    return img


def round_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
    return m


def font(px, bold=True):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_MED, int(px))


def text_center(img, cx, cy, txt, px, fill=WHITE, bold=True, stroke=0):
    """按视觉外框精确居中绘制文字"""
    f = font(px, bold)
    d = ImageDraw.Draw(img)
    bb = d.textbbox((0, 0), txt, font=f, stroke_width=stroke)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text((cx - tw / 2 - bb[0], cy - th / 2 - bb[1]), txt, font=f, fill=fill,
           stroke_width=stroke, stroke_fill=fill)


def text_left(img, x, cy, txt, px, fill=WHITE, bold=True, stroke=0, spacing=0):
    """左对齐 + 垂直居中，支持字间距（逐字绘制）"""
    f = font(px, bold)
    d = ImageDraw.Draw(img)
    if spacing:
        widths = [d.textlength(ch, font=f) for ch in txt]
        total = sum(widths) + spacing * (len(txt) - 1)
        bb = d.textbbox((0, 0), txt, font=f, stroke_width=stroke)
        th = bb[3] - bb[1]
        cur = x
        for ch, cw in zip(txt, widths):
            d.text((cur, cy - th / 2 - bb[1]), ch, font=f, fill=fill,
                   stroke_width=stroke, stroke_fill=fill)
            cur += cw + spacing
        return total
    bb = d.textbbox((0, 0), txt, font=f, stroke_width=stroke)
    th = bb[3] - bb[1]
    d.text((x, cy - th / 2 - bb[1]), txt, font=f, fill=fill,
           stroke_width=stroke, stroke_fill=fill)
    return d.textlength(txt, font=f)


def play_tri(img, x, y, w, h, color=CYAN):
    """等腰播放三角，尖朝右，左边竖直"""
    ImageDraw.Draw(img).polygon(
        [(x, y), (x + w, y + h / 2.0), (x, y + h)], fill=color)


def apply_round(img, r):
    w, h = img.size
    out = img.convert("RGBA")
    out.putalpha(round_mask(w, h, r))
    return out


def save(img, path, size=None):
    """超采样缩小落盘"""
    out = img
    if size and img.size != size:
        out = img.resize(size, Image.LANCZOS)
    out.convert("RGBA").save(path)
    print("  ->", os.path.relpath(path, ROOT), out.size)


def glow(img, color, radius, strength=0.85):
    """依据 alpha 生成外发光图层"""
    w, h = img.size
    a = img.getchannel("A").filter(ImageFilter.GaussianBlur(radius))
    a = a.point(lambda v: int(v * strength))
    layer = Image.new("RGBA", (w, h), color + (0,))
    layer.putalpha(a)
    return Image.alpha_composite(layer, img)


def focus_square(img, r):
    """正方形焦点态：青色外发光 + 白色内描边"""
    w, h = img.size
    lit = Image.alpha_composite(glow(img, CYAN, w * 0.035, 1.0), img)
    d = ImageDraw.Draw(lit)
    pad = w * 0.012
    d.rounded_rectangle((pad, pad, w - pad, h - pad), radius=r * 0.93,
                        outline=(255, 255, 255, 235), width=max(2, int(w * 0.008)))
    return lit


def focus_rect(img, r, bar=False):
    """横版焦点态：整体提亮 + 品牌色描边（+ 可选的青色进度条）"""
    lit = ImageEnhance.Brightness(img.convert("RGB")).enhance(1.15).convert("RGBA")
    lit.putalpha(img.getchannel("A"))
    d = ImageDraw.Draw(lit)
    d.rounded_rectangle((2, 2, lit.width - 3, lit.height - 3), radius=r,
                        outline=CYAN + (255,), width=max(2, int(lit.height * 0.012)))
    if bar:
        bh = max(3, int(lit.height * 0.022))
        d.rounded_rectangle((lit.width * 0.06, lit.height - bh * 3,
                             lit.width * 0.94, lit.height - bh * 2),
                            radius=bh / 2, fill=CYAN + (255,))
    return lit


# ---------------- 1. 正方形 1:1 ----------------
def square(w, h, rounded=True, focus=False):
    s = w * SS
    img = diag_bg(s, s)
    r = s * 0.22
    # 核心「强」字：真实字体，收在安全区内，避免顶到圆角
    text_center(img, s * 0.50, s * 0.465, GLYPH, s * SQ_GLYPH,
                WHITE, True, stroke=int(s * 0.005))
    # 右下青色播放三角：落在「强」字右下留白，不压笔画、不进圆角裁切区
    play_tri(img, s * 0.74, s * 0.72, s * 0.10, s * 0.12)
    out = apply_round(img, r) if rounded else img.convert("RGBA")
    if focus:
        out = focus_square(out, r)
    return out.resize((w, h), Image.LANCZOS)


# ---------------- 2. 横版 16:9 ----------------
def banner(w, h, focus=False):
    W, H = w * SS, h * SS
    img = diag_bg(W, H)
    r = H * 0.09
    # 左侧 40%：符号区
    text_center(img, W * 0.205, H * 0.50, GLYPH, H * BN_GLYPH,
                WHITE, True, stroke=int(H * 0.006))
    play_tri(img, W * 0.335, H * 0.665, H * 0.075, H * 0.09)
    # 右侧 60%：品牌名 + slogan
    tx = W * 0.445
    text_left(img, tx, H * 0.415, APP_NAME, H * 0.185,
              WHITE, True, stroke=int(H * 0.004))
    text_left(img, tx, H * 0.705, SLOGAN, H * 0.072,
              SUBTLE, False, spacing=H * 0.022)
    out = apply_round(img, r)
    if focus:
        out = focus_rect(out, int(r))
    return out.resize((w, h), Image.LANCZOS)


# ---------------- 3. 超宽 3:1 ----------------
def wide(w, h, show_new=False, focus=False):
    W, H = w * SS, h * SS
    img = diag_bg(W, H)
    r = H * 0.075
    # 左端方形 Logo：用更深的实色底与主体区分，内含「强」+ 小三角
    ls = H * 0.62
    lx, ly = W * 0.045, (H - ls) / 2
    logo = Image.new("RGBA", (int(ls), int(ls)), (7, 20, 46, 255))
    ld = ImageDraw.Draw(logo)
    # 轻微斜向提亮，避免死板
    for k in range(-int(ls), int(ls) + 1, max(1, SS)):
        t = (k + ls) / (2.0 * ls)
        c = lerp((7, 20, 46), BG_MAIN, t * 0.55)
        ld.line([(k, 0), (k + ls, ls)], fill=c + (255,), width=SS * 2)
    text_center(logo, ls * 0.50, ls * 0.465, GLYPH, ls * WD_GLYPH,
                WHITE, True, stroke=int(ls * 0.005))
    play_tri(logo, ls * 0.70, ls * 0.70, ls * 0.10, ls * 0.115)
    logo = apply_round(logo, ls * 0.22)
    img.paste(logo, (int(lx), int(ly)), logo)
    # 细描边，让 logo 从渐变底上浮出来
    ImageDraw.Draw(img).rounded_rectangle(
        (lx - 2, ly - 2, lx + ls + 2, ly + ls + 2), radius=ls * 0.235,
        outline=(108, 158, 224, 255), width=max(2, int(H * 0.006)))
    # 中部品牌名：起点必须落在 logo 右边界(约 0.252W)之外，留 0.03W 呼吸区
    text_left(img, W * 0.285, H * 0.5, APP_NAME, H * 0.24,
              WHITE, True, stroke=int(H * 0.005))
    # 右侧可选 NEW 角标
    if show_new:
        bw, bh = W * 0.105, H * 0.19
        bx, by = W * 0.845, (H - bh) / 2
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((bx, by, bx + bw, by + bh), radius=bh / 2, fill=CYAN)
        text_center(img, bx + bw / 2, by + bh / 2, "NEW", bh * 0.58,
                    BG_DEEP, True)
    out = apply_round(img, r)
    if focus:
        out = focus_rect(out, int(r), bar=True)
    return out.resize((w, h), Image.LANCZOS)


# ---------------- SVG（矢量源文件，字体栈 + 可编辑文字） ----------------
FONT_STACK = "'Microsoft YaHei','Noto Sans CJK SC','Source Han Sans SC','PingFang SC',sans-serif"


def grad_def(gid, x1=0, y1=1, x2=1, y2=0):
    """左下亮 -> 右上深，与 PNG 的 x-y=k 等值线方向一致"""
    return (f'<linearGradient id="{gid}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
            f'<stop offset="0" stop-color="#285CB8"/>'
            f'<stop offset="0.5" stop-color="#1E3A8A"/>'
            f'<stop offset="1" stop-color="#0B1B3A"/>'
            f'</linearGradient>')


def svg_square():
    s = 512
    r = s * 0.22
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{s}" height="{s}" viewBox="0 0 {s} {s}">
  <defs>{grad_def("g")}</defs>
  <rect width="{s}" height="{s}" rx="{r:.0f}" fill="url(#g)"/>
  <text x="{s*0.50:.0f}" y="{s*0.465:.0f}" fill="#FFFFFF" font-family={'"'+FONT_STACK+'"'} font-size="{s*SQ_GLYPH:.0f}" font-weight="700" text-anchor="middle" dominant-baseline="central" stroke="#FFFFFF" stroke-width="{s*0.005:.0f}" stroke-linejoin="round">{GLYPH}</text>
  <polygon points="{s*0.74:.0f},{s*0.72:.0f} {s*0.84:.0f},{s*0.78:.0f} {s*0.74:.0f},{s*0.84:.0f}" fill="#22D3EE"/>
</svg>'''


def svg_banner():
    w, h = 640, 360
    r = h * 0.09
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
  <defs>{grad_def("g")}</defs>
  <rect width="{w}" height="{h}" rx="{r:.0f}" fill="url(#g)"/>
  <text x="{w*0.205:.0f}" y="{h*0.50:.0f}" fill="#FFFFFF" font-family={'"'+FONT_STACK+'"'} font-size="{h*BN_GLYPH:.0f}" font-weight="700" text-anchor="middle" dominant-baseline="central" stroke="#FFFFFF" stroke-width="2" stroke-linejoin="round">{GLYPH}</text>
  <polygon points="{w*0.335:.0f},{h*0.665:.0f} {w*0.335+h*0.075:.0f},{h*0.71:.0f} {w*0.335:.0f},{h*0.755:.0f}" fill="#22D3EE"/>
  <text x="{w*0.445:.0f}" y="{h*0.415:.0f}" fill="#FFFFFF" font-family={'"'+FONT_STACK+'"'} font-size="{h*0.185:.0f}" font-weight="700" dominant-baseline="central" stroke="#FFFFFF" stroke-width="1.5" stroke-linejoin="round">致强视频</text>
  <text x="{w*0.445:.0f}" y="{h*0.705:.0f}" fill="#A3C2E6" font-family={'"'+FONT_STACK+'"'} font-size="{h*0.072:.0f}" font-weight="400" letter-spacing="{h*0.022:.0f}" dominant-baseline="central">好片随心看</text>
</svg>'''


def svg_wide():
    w, h = 960, 320
    r = h * 0.075
    ls = h * 0.62
    lx, ly = w * 0.045, (h - ls) / 2
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
  <defs>{grad_def("g")}</defs>
  <rect width="{w}" height="{h}" rx="{r:.0f}" fill="url(#g)"/>
  <rect x="{lx:.0f}" y="{ly:.0f}" width="{ls:.0f}" height="{ls:.0f}" rx="{ls*0.22:.0f}" fill="#07142E" stroke="#6C9EE0" stroke-width="2"/>
  <text x="{lx+ls*0.50:.0f}" y="{ly+ls*0.465:.0f}" fill="#FFFFFF" font-family={'"'+FONT_STACK+'"'} font-size="{ls*WD_GLYPH:.0f}" font-weight="700" text-anchor="middle" dominant-baseline="central" stroke="#FFFFFF" stroke-width="1" stroke-linejoin="round">{GLYPH}</text>
  <polygon points="{lx+ls*0.70:.0f},{ly+ls*0.70:.0f} {lx+ls*0.80:.0f},{ly+ls*0.7575:.0f} {lx+ls*0.70:.0f},{ly+ls*0.815:.0f}" fill="#22D3EE"/>
  <text x="{w*0.285:.0f}" y="{h*0.5:.0f}" fill="#FFFFFF" font-family={'"'+FONT_STACK+'"'} font-size="{h*0.24:.0f}" font-weight="700" dominant-baseline="central" stroke="#FFFFFF" stroke-width="1.5" stroke-linejoin="round">致强视频</text>
</svg>'''


# ---------------- 主流程 ----------------
def main():
    d1 = os.path.join(ROOT, "01-square")
    d2 = os.path.join(ROOT, "02-banner-16x9")
    d3 = os.path.join(ROOT, "03-wide-3x1")
    dv = os.path.join(ROOT, "variants")
    dr = os.path.join(ROOT, "android-res")

    print("[1/4] 正方形 1:1")
    for n in (512, 256, 128):
        save(square(n, n), os.path.join(d1, f"zq_square_{n}.png"))
    save(square(512, 512, focus=True), os.path.join(d1, "zq_square_512_focus.png"))
    save(square(512, 512, rounded=False), os.path.join(dv, "zq_square_512_nocorner.png"))
    open(os.path.join(d1, "zq_square.svg"), "w", encoding="utf-8").write(svg_square())

    print("[2/4] 横版 16:9")
    for (w, h) in ((640, 360), (320, 180), (1280, 720)):
        save(banner(w, h), os.path.join(d2, f"zq_banner_{w}x{h}.png"))
    save(banner(640, 360, focus=True), os.path.join(d2, "zq_banner_640x360_focus.png"))
    open(os.path.join(d2, "zq_banner_16x9.svg"), "w", encoding="utf-8").write(svg_banner())

    print("[3/4] 超宽 3:1")
    for (w, h) in ((960, 320), (480, 160), (1920, 640)):
        save(wide(w, h), os.path.join(d3, f"zq_wide_{w}x{h}.png"))
    save(wide(960, 320, show_new=True), os.path.join(dv, "zq_wide_960x320_new.png"))
    save(wide(960, 320, focus=True), os.path.join(d3, "zq_wide_960x320_focus.png"))
    open(os.path.join(d3, "zq_wide_3x1.svg"), "w", encoding="utf-8").write(svg_wide())

    print("[4/4] Android res 各密度")
    # launcher icon: 48dp * 密度
    for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72),
                       ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144),
                       ("mipmap-xxxhdpi", 192)):
        p = os.path.join(dr, folder)
        os.makedirs(p, exist_ok=True)
        save(square(px, px), os.path.join(p, "ic_launcher.png"))
    # TV banner: 320x180dp * 密度
    for folder, w, h in (("drawable-mdpi", 320, 180), ("drawable-hdpi", 480, 270),
                         ("drawable-xhdpi", 640, 360), ("drawable-xxhdpi", 960, 540)):
        p = os.path.join(dr, folder)
        os.makedirs(p, exist_ok=True)
        save(banner(w, h), os.path.join(p, "banner.png"))
    # 超宽条：应用内用，放 drawable-nodpi 原尺寸
    p = os.path.join(dr, "drawable-nodpi")
    os.makedirs(p, exist_ok=True)
    save(wide(960, 320), os.path.join(p, "zq_wide_960x320.png"))

    print("DONE")


if __name__ == "__main__":
    main()
