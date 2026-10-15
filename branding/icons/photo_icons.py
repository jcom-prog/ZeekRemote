"""Five launcher-icon designs cut from two 7GT studio photos (0.3.3).

The photos themselves are not part of the repository; only the layers cut from them are. To
regenerate, pass the folder that holds them:
  zijkant.webp  - side view of the nose, front wheel with the orange caliper (1439 x 1800)
  voorkant.webp - front three-quarter view, light bar and "ZEEKR 7GT" plate  (1439 x 1800)

Each design is a full-bleed photo layer (432 x 432 px = 108 dp at xxxhdpi, lossy WebP) as the
adaptive-icon background, an empty foreground, and a vector monochrome layer that is drawn by hand
on the photo's shapes (themed icons and, via status_icons.py, the status-bar icon).

Run:  python3 branding/icons/photo_icons.py <photo-folder>   (needs numpy and pillow)
      python3 branding/icons/status_icons.py                   (status-bar icons + previews)
Without a folder the script only rewrites the vector layers and the adaptive-icon files.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import icons as I

NODPI = os.path.join(I.RES, 'drawable-nodpi')
LAYER_PX = 432

# Wheel in zijkant.webp: centre and tyre radius in photo pixels (Hough fit on the rim + ring scan).
WHEEL_X, WHEEL_Y, TYRE_R = 590, 1140, 203


def _crop(img, cx, cy, size, px=LAYER_PX):
    """Square crop of [size] photo px around (cx, cy), scaled to [px]; outside the photo the edge
    pixels are repeated (plain studio wall)."""
    half = size / 2
    box = [int(round(cx - half)), int(round(cy - half)), int(round(cx + half)), int(round(cy + half))]
    pad = max(0, -box[0], -box[1], box[2] - img.width, box[3] - img.height)
    if pad:
        img = Image.fromarray(np.pad(np.array(img), ((pad, pad), (pad, pad), (0, 0)), mode='edge'))
        box = [v + pad for v in box]
    return img.crop(tuple(box)).resize((px, px), Image.LANCZOS)


def _sharpen(img):
    return img.filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=2))


def layer_velg(side, front):
    # Tyre radius -> 34 dp: the whole wheel fills the icon, a little fender in the corners.
    return _sharpen(_crop(side, WHEEL_X, WHEEL_Y, TYRE_R / 34.0 * 108))


def layer_wiel(side, front):
    """The wheel cut out along the tyre (31 dp) on the orange of the brake caliper."""
    size = TYRE_R / 31.0 * 108
    photo = np.array(_crop(side, WHEEL_X, WHEEL_Y, size)).astype(float)
    n = LAYER_PX
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    c = (n - 1) / 2
    r = TYRE_R * n / size
    alpha = np.clip(r - np.hypot(xx - c, yy - c) + 0.5, 0, 1)[..., None]
    t = np.clip((xx + yy) / (2 * n), 0, 1)[..., None]
    bg = (1 - t) * np.array([0xF6, 0xB2, 0x3E]) + t * np.array([0xE8, 0x64, 0x1E])
    shade = np.clip(1 - np.hypot(xx - c, yy - c - r * 0.06) / (r * 1.08), 0, 1) ** 0.6
    bg = bg * (1 - 0.45 * shade[..., None])
    out = photo * alpha + bg * (1 - alpha)
    return _sharpen(Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)))


def layer_front(side, front):
    return _sharpen(_crop(front, 590, 935, 980))


def layer_plaat(side, front):
    return _sharpen(_crop(front, 408, 925, 520))


def layer_profiel(side, front):
    return _sharpen(_crop(side, 600, 1040, 1300))


# ---------------------------------------------------------------- monochrome layers (108 dp)
BLACK = I.solid('#000000')


def _circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def mono_wheel():
    """Tyre ring, five spokes, hub and the caliper - the 7GT wheel in one colour."""
    cx = cy = 54.0
    ops = [I.stroke(_circle(cx, cy, 28.0), 6.0, BLACK, closed=True)]   # tyre: r 25..31
    for k in range(5):
        a = math.radians(-90 + 72 * k)
        ops.append(I.stroke([(cx + 7 * math.cos(a), cy + 7 * math.sin(a)),
                             (cx + 24.5 * math.cos(a), cy + 24.5 * math.sin(a))], 4.2, BLACK))
    ops.append(I.ellipse(cx, cy, 6.5, 6.5, BLACK))                       # hub
    # the caliper behind the spokes (left): a solid block between r 14 and 22
    band = [(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)))
            for r, angles in ((22.0, range(146, 215, 4)), (14.0, range(214, 145, -4))) for a in angles]
    ops.append(I.poly(band, BLACK))
    return ops


def mono_front():
    """Front view: hood line, light bar, plate, the two corner intakes and the bumper lip."""
    def curve(y_end, y_mid, x0, x1, n=24):
        return [(x0 + (x1 - x0) * i / n, y_end + (y_mid - y_end) * (1 - (2 * i / n - 1) ** 2)) for i in range(n + 1)]
    return [I.stroke(curve(52, 40, 17, 91), 2.2, BLACK),
            I.stroke(curve(58, 54.5, 19, 89), 3.4, BLACK),
            I.poly([(40, 63), (68, 63), (68, 71), (40, 71)], BLACK),
            I.stroke([(27, 63), (25.5, 69), (27, 76)], 2.4, BLACK),
            I.stroke([(81, 63), (82.5, 69), (81, 76)], 2.4, BLACK),
            I.stroke([(33, 81), (75, 81)], 2.2, BLACK)]


def _scaled(ops, k, cx=54.0, cy=54.0):
    """[ops] scaled by [k] around the icon centre (strokes keep their look: widths scale too)."""
    f = lambda x, y: (cx + (x - cx) * k, cy + (y - cy) * k)
    out = []
    for o in ops:
        o = dict(o)
        if 'pts' in o:
            o['pts'] = [f(x, y) for x, y in o['pts']]
        if 'width' in o:
            o['width'] *= k
        if o['op'] in ('ellipse', 'arc'):
            o['cx'], o['cy'] = f(o['cx'], o['cy'])
            for key in ('rx', 'ry', 'r'):
                if key in o:
                    o[key] *= k
        out.append(o)
    return out


def mono_plaat():
    """The number plate: rounded outline with "7GT" in it."""
    def rounded(x0, y0, x1, y1, r, n=6):
        pts = []
        for (cx, cy, a0) in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
            pts += [(cx + r * math.cos(math.radians(a0 + 90 * i / n)), cy + r * math.sin(math.radians(a0 + 90 * i / n)))
                    for i in range(n + 1)]
        return pts
    w = 3.2
    ops = [I.stroke(rounded(14, 36, 94, 72, 6), w, BLACK, closed=True)]   # scaled into the safe zone below
    # 7
    ops.append(I.stroke([(25, 46), (39, 46), (30, 62)], w, BLACK))
    # G: open on the upper right, bar into the middle
    gx, gy, gr = 54.0, 54.0, 8.0
    ops.append(I.arc(gx, gy, gr + w / 2, 20, 320, w, BLACK))
    ops.append(I.stroke([(gx + 1.5, gy + 1.0), (gx + gr, gy + 1.0), (gx + gr, gy + 3.5)], w, BLACK))
    # T
    ops.append(I.stroke([(68, 46), (84, 46)], w, BLACK))
    ops.append(I.stroke([(76, 46), (76, 62)], w, BLACK))
    return _scaled(ops, 0.74)


def _traced(paint_ops, eps=0.12):
    """Raster [paint_ops] ((shape, on) pairs: on = add, off = cut away) at the preview resolution
    and trace the outline back into even-odd polygons: one-colour art with holes."""
    from skimage import measure
    n = 108 * I.S
    mask = Image.new('L', (n + 2, n + 2), 0)
    d = ImageDraw.Draw(mask)
    for kind, args, on in paint_ops:
        if kind == 'poly':
            d.polygon([(x * I.S + 1, y * I.S + 1) for x, y in args], fill=255 if on else 0)
        else:
            cx, cy, r = args
            d.ellipse([(cx - r) * I.S + 1, (cy - r) * I.S + 1, (cx + r) * I.S + 1, (cy + r) * I.S + 1], fill=255 if on else 0)
    polys = []
    for c in measure.find_contours(np.array(mask).astype(float) / 255, 0.5):
        pts = [((x - 1) / I.S, (y - 1) / I.S) for y, x in c][:-1]   # closed: last point = first
        # Simplify a closed outline in two halves, split at the point farthest from the start.
        x0, y0 = pts[0]
        k = max(range(len(pts)), key=lambda i: (pts[i][0] - x0) ** 2 + (pts[i][1] - y0) ** 2)
        pts = I.rdp(pts[:k + 1], eps)[:-1] + I.rdp(pts[k:] + [pts[0]], eps)[:-1]
        if len(pts) >= 3:
            polys.append(pts)
    return [I.compound(polys, BLACK)]


def mono_profiel():
    """Side view of the nose and front wheel, placed like the photo crop: a filled body with the
    wheel cut free as a ring (Close-up draws the same view in outline)."""
    s = 0.0935
    car = I.Car(s, 0, 0)
    wx, wy = I.GEO['wheels'][0]['cx'], I.GEO['wheels'][0]['cy']
    car.ox = 50 - (wx - car.cx) * s
    car.oy = 68 - (wy - car.cy) * s
    w = car.wheels()[0]
    cx, cy, r = w['cx'], w['cy'], (w['rx'] + w['ry']) / 2
    ops = [('poly', p, True) for p in car.body()]
    ops += [('disc', (cx, cy, r * 1.14), False), ('disc', (cx, cy, r), True),
            ('disc', (cx, cy, r * 0.58), False), ('disc', (cx, cy, r * 0.24), True)]
    return _traced(ops)


PHOTO_DESIGNS = [
    dict(key='velg', name='Velg', layer=layer_velg, mono=mono_wheel()),
    dict(key='wiel', name='Oranje wiel', layer=layer_wiel, mono=mono_wheel()),
    dict(key='front', name='Front', layer=layer_front, mono=_scaled(mono_front(), 0.86)),
    dict(key='plaat', name='7GT', layer=layer_plaat, mono=mono_plaat()),
    dict(key='profiel', name='Profiel', layer=layer_profiel, mono=mono_profiel()),
]
for _d in PHOTO_DESIGNS:
    _d['layer_path'] = os.path.join(NODPI, 'ic_launcher_%s_background.webp' % _d['key'])
    # For the shared renderer (previews, status_icons.py): the photo layer as a bitmap op.
    _d['bg'] = [I.bitmap(_d['layer_path'], 0, 0, 108, 108)]
    _d['fg'] = []


# ---------------------------------------------------------------- previews (branding/icons/out)
def _font(size, bold=False):
    from PIL import ImageFont
    return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size)


def _shaped(d, px):
    """The design as the launcher shows it (Samsung squircle); None = the original OpenZeekr mark."""
    if d is None:
        return Image.open(os.path.join(I.ROOT, 'branding', 'export', 'ic_launcher_512.png')).convert('RGBA').resize((px, px), Image.LANCZOS)
    return I.masked(I.compose(d), lambda n: I.superellipse_mask(n), px)


def previews():
    from PIL import ImageDraw
    fontb, font, fonts = _font(30, True), _font(26), _font(24)
    # Sheet 1: the five photo designs (squircle, circle, Android theme, real size)
    W, rowh = 1400, 300
    sheet = Image.new('RGBA', (W, 120 + rowh * len(PHOTO_DESIGNS)), (18, 22, 26, 255))
    dr = ImageDraw.Draw(sheet)
    dr.text((40, 30), 'ZeekRemote – 5 nieuwe app-iconen uit de 7GT-foto\'s', font=fontb, fill=(234, 238, 241))
    dr.text((40, 75), 'vorm Samsung      ·      rond      ·      Android-thema      ·      ware grootte', font=font, fill=(139, 151, 158))
    for i, d in enumerate(PHOTO_DESIGNS):
        y = 120 + i * rowh
        full = I.compose(d)
        sheet.alpha_composite(I.masked(full, lambda n: I.superellipse_mask(n), 240), (40, y + 20))
        sheet.alpha_composite(I.masked(full, I.circle_mask, 240), (310, y + 20))
        sheet.alpha_composite(I.masked(I.themed(d), lambda n: I.superellipse_mask(n), 240), (580, y + 20))
        sheet.alpha_composite(I.masked(full, lambda n: I.superellipse_mask(n), 64), (870, y + 108))
        dr.text((970, y + 100), '%d. %s' % (i + 6, d['name']), font=fontb, fill=(234, 238, 241))
    sheet.convert('RGB').save(os.path.join(I.OUT, 'iconen-foto-overzicht.png'))

    # Sheet 2: all eleven choices as in Settings > App-icoon (3 per row) on a home-screen backdrop
    rows = [('Origineel', None)] + [(d['name'], d) for d in I.DESIGNS + PHOTO_DESIGNS]
    cols, cell_w, cell_h = 3, 330, 300
    H = 140 + cell_h * ((len(rows) + cols - 1) // cols)
    wall = np.zeros((H, 1080, 3))
    yy = np.linspace(0, 1, H)[:, None]
    for k, c0, c1 in ((0, 30, 10), (1, 46, 14), (2, 74, 18)):
        wall[..., k] = c0 * (1 - yy) + c1 * yy
    hs = Image.fromarray(wall.astype(np.uint8)).convert('RGBA')
    dr = ImageDraw.Draw(hs)
    dr.text((60, 50), 'Alle keuzes in Instellingen › App-icoon (11)', font=fontb, fill=(234, 238, 241))
    for j, (name, d) in enumerate(rows):
        cx = 75 + (j % cols) * cell_w + 15
        cy = 130 + (j // cols) * cell_h
        hs.alpha_composite(_shaped(d, 150), (cx + 30, cy))
        tw = dr.textlength(name, font=font)
        dr.text((cx + 105 - tw / 2, cy + 165), name, font=font, fill=(234, 238, 241))
    hs.convert('RGB').save(os.path.join(I.OUT, 'iconen-alle-keuzes.png'))


def notification_preview(fixed_keys):
    """The ongoing notification as One UI draws it: the card shows the application icon."""
    from PIL import ImageDraw
    fontb, font, fonts = _font(30, True), _font(27), _font(24)
    by_key = {d['key']: d for d in I.DESIGNS + PHOTO_DESIGNS}
    rows = [('Nu (0.3.2): app-icoon = origineel', None)] + [('0.3.3 met vast app-icoon "%s"' % by_key[k]['name'], by_key[k]) for k in fixed_keys]
    rowh = 190
    img = Image.new('RGBA', (1100, 110 + rowh * len(rows)), (40, 40, 44, 255))
    dr = ImageDraw.Draw(img)
    dr.text((40, 34), 'Melding "digital key active" (Samsung, meldingenpaneel)', font=fontb, fill=(234, 238, 241))
    for i, (label, d) in enumerate(rows):
        y = 110 + i * rowh
        dr.text((52, y), label, font=fonts, fill=(170, 176, 182))
        dr.rounded_rectangle([40, y + 36, 1060, y + 166], radius=44, fill=(58, 58, 62))
        img.alpha_composite(_shaped(d, 84), (78, y + 59))
        dr.text((196, y + 58), 'ZeekRemote digital key active', font=_font(29, True), fill=(236, 236, 238))
        dr.text((690, y + 62), '09:36', font=fonts, fill=(160, 160, 166))
        dr.text((196, y + 103), 'Keeping your key connected for lock/unlock', font=font, fill=(190, 190, 196))
    img.convert('RGB').save(os.path.join(I.OUT, 'melding-app-icoon.png'))


def main(photo_dir=None):
    os.makedirs(NODPI, exist_ok=True)
    if photo_dir:
        side = Image.open(os.path.join(photo_dir, 'zijkant.webp')).convert('RGB')
        front = Image.open(os.path.join(photo_dir, 'voorkant.webp')).convert('RGB')
        for d in PHOTO_DESIGNS:
            d['layer'](side, front).save(d['layer_path'], 'WEBP', quality=90, method=6)
    drawable = os.path.join(I.RES, 'drawable')
    anydpi = os.path.join(I.RES, 'mipmap-anydpi-v26')
    for d in PHOTO_DESIGNS:
        name = 'ic_launcher_%s' % d['key']
        if not os.path.exists(d['layer_path']):
            sys.exit('missing %s: run with the photo folder first' % d['layer_path'])
        open(os.path.join(drawable, name + '_monochrome.xml'), 'w').write(I.vector_xml(d['mono']).replace(
            'Generated from the 7GT render by branding/icons/icons.py',
            'Monochrome layer of the "%s" photo icon, generated by branding/icons/photo_icons.py' % d['name']))
        open(os.path.join(anydpi, name + '.xml'), 'w').write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by branding/icons/photo_icons.py ("%s"): photo layer, no foreground. -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/%s_background" />\n'
            '    <foreground android:drawable="@android:color/transparent" />\n'
            '    <monochrome android:drawable="@drawable/%s_monochrome" />\n'
            '</adaptive-icon>\n' % (d['name'], name, name))
    previews()
    notification_preview(['sleutel', 'velg', 'wiel', 'front', 'plaat', 'profiel'])
    print('ok', [d['key'] for d in PHOTO_DESIGNS])


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else None)
