import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
S = 1024
base = Image.open('square_noText.png').convert('RGBA')
F = '/home/claude/srb-port/android/app/src/main/assets/ui/fonts/RussoOne-Regular.ttf'
font = ImageFont.truetype(F, 210)
txt = 'SRBN'
# text mask (big, then skewed for the italic retro look)
mask = Image.new('L', (S, S), 0)
d = ImageDraw.Draw(mask)
cx, cy = S // 2, int(S * 0.215)
d.text((cx, cy), txt, font=font, anchor='mm', fill=255)
skew = 0.22
mask = mask.transform((S, S), Image.AFFINE, (1, skew, -skew * cy, 0, 1, 0), resample=Image.BICUBIC)
bbox = mask.getbbox(); print('bbox', bbox, 'w%', (bbox[2]-bbox[0])/S)
top, bot = bbox[1], bbox[3]
# chrome gradient: sky-white top, sharp horizon line, sunset bottom
grad = np.zeros((S, S, 3), np.float32)
for y in range(S):
    t = (y - top) / max(1, bot - top)
    if t < 0.5:
        u = t / 0.5
        c = np.array([255, 255, 255]) * (1 - u) + np.array([150, 205, 255]) * u
    else:
        u = (t - 0.5) / 0.5
        v = min(1, u * 1.2); c = np.array([255, 214, 110]) * (1 - v) + np.array([255, 45, 140]) * v
        if t < 0.53: c = np.array([60, 20, 90])          # the chrome "horizon" line
    grad[y] = c
chrome = Image.fromarray(grad.astype(np.uint8)).convert('RGBA')
# glow + outline
glow = Image.new('RGBA', (S, S), (255, 40, 160, 0))
glow.putalpha(mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(22)).point(lambda v: int(v * 0.95)))
cyan = Image.new('RGBA', (S, S), (60, 230, 255, 0))
cyan.putalpha(mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(5)).point(lambda v: int(v * 0.9)))
outline = Image.new('RGBA', (S, S), (30, 6, 50, 255))
outline.putalpha(mask.filter(ImageFilter.MaxFilter(7)))
shadow = Image.new('RGBA', (S, S), (20, 0, 30, 0))
shadow.putalpha(ImageChops.offset(mask.filter(ImageFilter.MaxFilter(7)), 0, 10).point(lambda v: int(v * 0.8)))
out = base
for layer in (glow, cyan, shadow, outline):
    out = Image.alpha_composite(out, layer)
fill = chrome.copy(); fill.putalpha(mask)
out = Image.alpha_composite(out, fill)
# specular streak across the letters
streak = Image.new('L', (S, S), 0)
ImageDraw.Draw(streak).polygon([(int(S*0.30), top), (int(S*0.36), top), (int(S*0.30), bot), (int(S*0.24), bot)], fill=170)
streak = ImageChops.multiply(streak.filter(ImageFilter.GaussianBlur(4)), mask)
sw = Image.new('RGBA', (S, S), (255, 255, 255, 0)); sw.putalpha(streak)
out = Image.alpha_composite(out, sw)
out.convert('RGB').save('icon_master.png')
out.resize((600, 600), Image.LANCZOS).save('prev2.png')
