from PIL import Image, ImageFilter, ImageDraw
import numpy as np
base = Image.open('icon_master.png').convert('RGBA')
logo = Image.open('patrulla_hd.png')
logo = logo.crop(logo.getbbox())
W = 330
logo = logo.resize((W, round(logo.height * W / logo.width)), Image.LANCZOS)
import sys
X,B,OUT=(int(sys.argv[1]),int(sys.argv[2]),sys.argv[3]) if len(sys.argv)>3 else (92,935,'icon_master_patrulla.png')
x, y = X, B - logo.height
# sombra oscura + resplandor rojo suave para que se lea sobre la carretera
a = logo.split()[3]
pad = 40
sh = Image.new('L', (logo.width + 2*pad, logo.height + 2*pad), 0); sh.paste(a, (pad, pad))
shadow = Image.new('RGBA', sh.size, (10, 0, 25, 0)); shadow.putalpha(sh.filter(ImageFilter.GaussianBlur(10)).point(lambda v: min(255, int(v*1.6))))
glow = Image.new('RGBA', sh.size, (255, 40, 90, 0)); glow.putalpha(sh.filter(ImageFilter.GaussianBlur(18)).point(lambda v: int(v*0.55)))
out = base.copy()
out.alpha_composite(shadow, (x - pad + 4, y - pad + 6))
out.alpha_composite(glow, (x - pad, y - pad))
out.alpha_composite(logo, (x, y))
out.save(OUT)
# vistas previas con máscara squircle y círculo
def mask(kind, s=1024):
    m = Image.new('L', (s, s), 0); d = ImageDraw.Draw(m)
    if kind == 'sq': d.rounded_rectangle((0, 0, s-1, s-1), radius=int(s*0.18), fill=255)
    else: d.ellipse((0, 0, s-1, s-1), fill=255)
    return m
pv = Image.new('RGBA', (2*1024 + 60, 1024 + 40), (30, 30, 34, 255))
for i, k in enumerate(['sq', 'ci']):
    t = out.copy(); t.putalpha(mask(k)); pv.alpha_composite(t, (20 + i*1044, 20))
pv.resize((pv.width//2, pv.height//2), Image.LANCZOS).save(OUT.replace('.png','_pv.png'))
print(x, y, logo.size)
