import cv2, numpy as np
from PIL import Image, ImageFilter
src = cv2.imread('patrulla_src.jpg')
# 1) quitar artefactos JPEG
den = cv2.fastNlMeansDenoisingColored(src, None, 5, 5, 5, 15)
# 2) reescalado x4 en dos pasos con realce entre medias
S = 4
up = cv2.resize(den, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
up = cv2.bilateralFilter(up, 5, 30, 5)
up = cv2.resize(up, None, fx=2, fy=2, interpolation=cv2.INTER_LANCZOS4)
up = cv2.bilateralFilter(up, 7, 25, 7)
f = up.astype(np.float32)
blur = cv2.GaussianBlur(f, (0, 0), 3)
f = np.clip(f + 0.8 * (f - blur), 0, 255)
# 3) fondo -> transparencia (distancia al gris oscuro del fondo)
bg = np.median(np.concatenate([src[:3].reshape(-1,3), src[-3:].reshape(-1,3)]), axis=0)
d = np.linalg.norm(f - bg, axis=2)
alpha = np.clip((d - 30) / 40, 0, 1)
alpha = cv2.GaussianBlur(alpha, (0, 0), 0.8)
# 4) color: más saturación y contraste, despremultiplicar el fondo de los bordes
hsv = cv2.cvtColor(f.astype(np.uint8), cv2.COLOR_BGR2HSV).astype(np.float32)
hsv[..., 1] = np.clip(hsv[..., 1] * 1.25, 0, 255)
hsv[..., 2] = np.clip((hsv[..., 2] - 128) * 1.1 + 140, 0, 255)
col = cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR).astype(np.float32)
a3 = np.maximum(alpha, 1e-3)[..., None]
col = np.clip((col - bg * (1 - a3)) / a3, 0, 255)
rgba = np.dstack([col[..., ::-1], alpha * 255]).astype(np.uint8)
Image.fromarray(rgba, 'RGBA').save('patrulla_hd.png')
# vista previa sobre fondo oscuro
pv = Image.new('RGBA', (rgba.shape[1] + 40, rgba.shape[0] + 40), (20, 10, 40, 255))
pv.alpha_composite(Image.fromarray(rgba, 'RGBA'), (20, 20))
pv.save('patrulla_preview.png')
print(rgba.shape)
# 5) nitidez extra en «RETRO»: caja cian sólida y letras blancas limpias
im = np.array(Image.open('patrulla_hd.png')).astype(np.float32)
r, g, b, al = [im[..., i] for i in range(4)]
blue = ((b > r + 25) & (al > 10)).astype(np.float32)
blue = cv2.morphologyEx(blue, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
lum = 0.3*r + 0.59*g + 0.11*b
white = np.clip((lum - 165) / 30, 0, 1)
white = cv2.GaussianBlur(white, (0, 0), 0.7)
box = np.array([58, 178, 232], np.float32); wh = np.array([240, 250, 255], np.float32)
newc = box * (1 - white[..., None]) + wh * white[..., None]
m = cv2.GaussianBlur(blue, (0, 0), 1.0)[..., None]
im[..., :3] = im[..., :3] * (1 - m) + newc * m
Image.fromarray(np.clip(im, 0, 255).astype(np.uint8), 'RGBA').save('patrulla_hd.png')
