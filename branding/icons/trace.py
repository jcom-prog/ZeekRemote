"""Trace the Zeekr 7GT render into simple vector shapes (original pixel coordinates).

Run from anywhere:  python3 branding/icons/trace.py   (needs numpy, scipy, scikit-image, pillow)
Writes branding/icons/geo.json, which icons.py turns into the launcher icons.
"""
import json, os, sys
import numpy as np
from PIL import Image
from scipy import ndimage as ndi
from skimage import measure

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'cars', 'car_7gt.png')
OUT = os.path.join(HERE, 'geo.json')
im = np.array(Image.open(SRC).convert('RGBA')).astype(float)
H, W = im.shape[:2]
r, g, b, a = im[..., 0], im[..., 1], im[..., 2], im[..., 3]
lum = 0.299 * r + 0.587 * g + 0.114 * b
yy, xx = np.mgrid[0:H, 0:W]

body = a > 128
body = ndi.binary_fill_holes(ndi.binary_closing(body, iterations=3))
lab, n = ndi.label(body)
sizes = ndi.sum(body, lab, range(1, n + 1))
body = lab == (np.argmax(sizes) + 1)
body &= yy <= 686  # cut the ground shadow below the tyres

def polys(mask, min_area, tol):
    mask = np.pad(mask, 1)
    out = []
    for c in measure.find_contours(mask.astype(float), 0.5):
        c = c - 1  # undo pad
        p = measure.approximate_polygon(c, tolerance=tol)
        xy = [(float(x), float(y)) for y, x in p]
        # shoelace area
        A = 0.5 * abs(sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(xy, xy[1:] + xy[:1])))
        if A >= min_area:
            out.append(xy)
    return out

# Greenhouse: black roof + tinted glass, above the belt line.
glass = body & ((lum < 75) | (((b - r) > 15) & (lum < 175))) & (yy < 468)
glass = ndi.binary_opening(glass, iterations=2)
glass = ndi.binary_closing(glass, iterations=4)
glass = ndi.binary_fill_holes(glass)
# Front light band (dark blue-black strip across the nose) and lower dark trim.
band = body & (lum < 95) & (yy > 470) & (yy < 540) & (xx < 600)
band = ndi.binary_closing(ndi.binary_opening(band, iterations=1), iterations=3)
skirt = body & (lum < 70) & (yy > 585) & (xx > 700) & (xx < 1130)
skirt = ndi.binary_closing(ndi.binary_opening(skirt, iterations=1), iterations=3)

# Wheels: dark pixels in the lower part, two horizontal windows.
dark_low = body & (lum < 80) & (yy > 500)
wheels = []
for (xa, xb) in [(520, 780), (1080, 1300)]:
    m = dark_low & (xx >= xa) & (xx <= xb)
    ys, xs = np.where(m)
    # keep the dense core (tyre) - percentiles to ignore trim
    x0, x1 = np.percentile(xs, 1), np.percentile(xs, 99)
    y0, y1 = np.percentile(ys, 1), np.percentile(ys, 99.5)
    wheels.append(dict(cx=float((x0 + x1) / 2), cy=float((y0 + y1) / 2), rx=float((x1 - x0) / 2), ry=float((y1 - y0) / 2)))

ys, xs = np.where(body)
geo = dict(
    bbox=[int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())],
    body=polys(body, 5000, 1.2),
    glass=polys(glass, 1500, 1.2),
    band=polys(band, 400, 1.0),
    wheels=[dict(cx=641.0, cy=596.0, rx=90.0, ry=89.0), dict(cx=1201.0, cy=576.0, rx=75.0, ry=79.0)],
)
json.dump(geo, open(OUT, 'w'))
print({k: (len(v) if isinstance(v, list) else v) for k, v in geo.items()})
print('points', [len(p) for p in geo['body']], [len(p) for p in geo['glass']], [len(p) for p in geo['band']])
print('wheels', wheels)
