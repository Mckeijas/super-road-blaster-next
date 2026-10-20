import numpy as np
from PIL import Image, ImageFilter
src = Image.open('src.png').convert('RGB')
W = 1024
H = round(src.height * W / src.width)          # 574
img = np.asarray(src.resize((W, H), Image.LANCZOS)).astype(np.float32)
S = W                                           # square canvas
TOP = 300                                       # rows of sky added on top
BOT = S - TOP - H
out = np.zeros((S, S, 3), np.float32)
out[TOP:TOP + H] = img

# ---------- bottom: continue the road in perspective (lines keep diverging)
vpx, vpy = 0.29 * W, 0.42 * H                   # vanishing point (sun at the end of the road)
ys = np.arange(H - 1, H - 40, -1)
for yo in range(BOT):
    t = yo + 1
    ysrc = H - 1 - min(t * 0.12, 30)            # sample just above the bottom edge
    y_out = H - 1 + t                           # virtual row below the photo
    s = (y_out - vpy) / (ysrc - vpy)            # perspective scale factor
    xs = vpx + (np.arange(W) - vpx) / s
    xs = np.clip(xs, 0, W - 1)
    y0 = int(ysrc); fy = ysrc - y0
    row = img[y0] * (1 - fy) + img[min(y0 + 1, H - 1)] * fy
    x0 = np.floor(xs).astype(int); fx = (xs - x0)[:, None]; x1 = np.minimum(x0 + 1, W - 1)
    out[TOP + H + yo] = row[x0] * (1 - fx) + row[x1] * fx

# ---------- top: reflect only the sky + palm tops (first R rows), stretched upward,
# progressively blurred and fading into a violet night sky with a few stars
R = 120
mir = Image.fromarray(np.clip(img[:R], 0, 255).astype(np.uint8))
layers = [np.asarray(mir.filter(ImageFilter.GaussianBlur(r))).astype(np.float32) for r in (1.2, 3.5, 8)]
night_top = np.array([28, 8, 58], np.float32)
night_mid = np.array([96, 40, 140], np.float32)
rng = np.random.default_rng(7)
for i in range(TOP):                      # i = distance above the photo
    d = i / TOP
    sy = min(R - 1, i * (R / TOP) * 0.9)    # stretched reflection row
    y0 = int(sy); fy = sy - y0; y1 = min(y0 + 1, R - 1)
    def L(n): return layers[n][y0] * (1 - fy) + layers[n][y1] * fy
    if d < 0.35: c = L(0) * (1 - d / 0.35) + L(1) * (d / 0.35)
    elif d < 0.7: c = L(1) * (1 - (d - 0.35) / 0.35) + L(2) * ((d - 0.35) / 0.35)
    else: c = L(2)
    sky = night_mid * (1 - d) + night_top * d
    a = np.clip((d - 0.05) / 0.95, 0, 1) ** 0.9 * 0.92
    out[TOP - 1 - i] = c * (1 - a) + sky * a
# stars in the darkest part
for _ in range(90):
    x = rng.integers(0, W); y = rng.integers(0, int(TOP * 0.55))
    b = rng.uniform(120, 255) * (1 - y / (TOP * 0.55))
    out[y, x] = np.maximum(out[y, x], [b, b * 0.9, b])

# soften the seams with a small vertical blur band
res = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
for seam in (TOP, TOP + H):
    box = (0, seam - 6, S, seam + 6)
    res.paste(res.crop(box).filter(ImageFilter.GaussianBlur(2.2)), box)
# bottom vignette / darker asphalt near the viewer
o = np.asarray(res).astype(np.float32)
yy = np.arange(S)[:, None]
g = np.clip((yy - (TOP + H)) / BOT, 0, 1) ** 1.5 * 0.45
o = o * (1 - g[..., None])
Image.fromarray(np.clip(o, 0, 255).astype(np.uint8)).save('square_noText.png')
print(S, TOP, H, BOT)
