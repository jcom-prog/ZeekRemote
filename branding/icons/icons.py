"""Five launcher-icon designs for ZeekRemote, all derived from the 7GT render.

One geometry model feeds two outputs, so the preview shows what Android draws:
  * a Pillow renderer (previews), and
  * an Android VectorDrawable writer (adaptive-icon layers).
Design 5 ("Foto") uses the render itself as a bitmap foreground.
Coordinates: adaptive-icon viewport of 108 x 108 dp (safe zone = circle r 33 around 54,54).

Run:  python3 branding/icons/trace.py && python3 branding/icons/icons.py
      (needs numpy, scipy, scikit-image, pillow). Writes the app resources
      (res/drawable*, res/mipmap-anydpi-v26) and preview sheets in branding/icons/out/.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'cars', 'car_7gt.png')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
OUT = os.path.join(HERE, 'out')
os.makedirs(OUT, exist_ok=True)
GEO = json.load(open(os.path.join(HERE, 'geo.json')))
S = 16  # render px per dp -> 1728 px canvas


# ---------------------------------------------------------------- geometry helpers
def chaikin(pts, it=2, closed=True):
    for _ in range(it):
        out = []
        n = len(pts)
        rng = range(n) if closed else range(n - 1)
        for i in rng:
            (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % n]
            out += [(0.75 * x0 + 0.25 * x1, 0.75 * y0 + 0.25 * y1), (0.25 * x0 + 0.75 * x1, 0.25 * y0 + 0.75 * y1)]
        if not closed:
            out = [pts[0]] + out + [pts[-1]]
        pts = out
    return pts


def dedupe(pts, eps=0.05):
    out = [pts[0]]
    for p in pts[1:]:
        if abs(p[0] - out[-1][0]) > eps or abs(p[1] - out[-1][1]) > eps:
            out.append(p)
    return out


def rdp(pts, eps):
    if len(pts) < 3:
        return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy) or 1e-9
    dmax, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        d = abs(dy * pts[i][0] - dx * pts[i][1] + x1 * y0 - y1 * x0) / L
        if d > dmax:
            dmax, idx = d, i
    if dmax <= eps:
        return [pts[0], pts[-1]]
    return rdp(pts[:idx + 1], eps)[:-1] + rdp(pts[idx:], eps)


class Car:
    """The traced car, placed by scale s and the dp position of its bbox centre."""
    def __init__(self, s, ox, oy, smooth=2, simplify=0.0):
        self.simplify = simplify
        x0, y0, x1, y1 = GEO['bbox']
        y1 = min(y1, 686)
        self.cx, self.cy = (x0 + x1) / 2, (y0 + y1) / 2
        self.s, self.ox, self.oy, self.smooth = s, ox, oy, smooth

    def p(self, x, y):
        return ((x - self.cx) * self.s + self.ox, (y - self.cy) * self.s + self.oy)

    def poly(self, pts):
        pts = [tuple(q) for q in pts]
        if self.simplify:
            # closed shape: split at the point farthest from the start, simplify both halves
            x0, y0 = pts[0]
            k = max(range(len(pts)), key=lambda i: (pts[i][0] - x0) ** 2 + (pts[i][1] - y0) ** 2)
            pts = rdp(pts[:k + 1], self.simplify)[:-1] + rdp(pts[k:] + [pts[0]], self.simplify)[:-1]
        return dedupe([self.p(x, y) for x, y in chaikin(pts, self.smooth)])

    def body(self):
        return [self.poly(p) for p in GEO['body']]

    def glass(self):
        return [self.poly(p) for p in GEO['glass']]

    def band(self):
        return [self.poly(p) for p in GEO['band'] if len(p) > 12]

    def wheels(self):
        return [dict(cx=self.p(w['cx'], w['cy'])[0], cy=self.p(w['cx'], w['cy'])[1],
                     rx=w['rx'] * self.s, ry=w['ry'] * self.s) for w in GEO['wheels']]

    def light_line(self):
        """The thin LED line along the lower edge of the front band."""
        pts = [tuple(q) for q in max(GEO['band'], key=len)]
        ys = [y for _, y in pts]
        mid = (min(ys) + max(ys)) / 2
        low = sorted([(x, y) for x, y in pts if y > mid], key=lambda q: q[0])
        # thin out and lift slightly into the band
        step = max(1, len(low) // 10)
        line = [(x, y - 7) for x, y in low[::step]]
        return dedupe([self.p(x, y) for x, y in chaikin(line, 2, closed=False)])

    def ground_y(self):
        return self.p(0, 686)[1]


# ---------------------------------------------------------------- paint model
def solid(hexc, a=1.0):
    return dict(kind='solid', c=hexc, a=a)


def linear(x1, y1, x2, y2, stops):
    return dict(kind='linear', x1=x1, y1=y1, x2=x2, y2=y2, stops=stops)  # stops: (offset, '#rrggbb', alpha)


def radial(cx, cy, r, stops):
    return dict(kind='radial', cx=cx, cy=cy, r=r, stops=stops)


def poly(pts, fill):
    return dict(op='poly', pts=pts, fill=fill)


def ellipse(cx, cy, rx, ry, fill):
    return dict(op='ellipse', cx=cx, cy=cy, rx=rx, ry=ry, fill=fill)


def rect(fill, x=0, y=0, w=108, h=108):
    return dict(op='poly', pts=[(x, y), (x + w, y), (x + w, y + h), (x, y + h)], fill=fill)


def stroke(pts, width, paint, closed=False):
    return dict(op='stroke', pts=pts, width=width, paint=paint, closed=closed)


def arc(cx, cy, r, a0, a1, width, paint):
    """Circular arc from angle a0 to a1 (degrees, 0 = east, clockwise positive like screen coords)."""
    return dict(op='arc', cx=cx, cy=cy, r=r, a0=a0, a1=a1, width=width, paint=paint)


def bitmap(path, x, y, w, h):
    return dict(op='bitmap', path=path, x=x, y=y, w=w, h=h)


# ---------------------------------------------------------------- renderer (previews)
def _hex(c):
    c = c.lstrip('#')
    return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], float)


def _paint_layer(paint, size):
    n = size
    if paint['kind'] == 'solid':
        col = np.zeros((n, n, 3)) + _hex(paint['c'])
        alp = np.zeros((n, n)) + paint['a']
        return col, alp
    yy, xx = np.mgrid[0:n, 0:n].astype(float) / S + 0.5 / S
    if paint['kind'] == 'linear':
        dx, dy = paint['x2'] - paint['x1'], paint['y2'] - paint['y1']
        t = ((xx - paint['x1']) * dx + (yy - paint['y1']) * dy) / (dx * dx + dy * dy)
    else:
        t = np.hypot(xx - paint['cx'], yy - paint['cy']) / paint['r']
    t = np.clip(t, 0, 1)
    stops = paint['stops']
    offs = np.array([s[0] for s in stops])
    cols = np.array([_hex(s[1]) for s in stops])
    alps = np.array([s[2] for s in stops])
    col = np.stack([np.interp(t, offs, cols[:, k]) for k in range(3)], -1)
    alp = np.interp(t, offs, alps)
    return col, alp


def render(ops, size_dp=108):
    n = size_dp * S
    out = np.zeros((n, n, 4))
    for op in ops:
        if op['op'] == 'bitmap':
            im = Image.open(op['path']).convert('RGBA').resize((int(op['w'] * S), int(op['h'] * S)), Image.LANCZOS)
            layer = Image.new('RGBA', (n, n), (0, 0, 0, 0))
            layer.paste(im, (int(op['x'] * S), int(op['y'] * S)), im)
            arr = np.array(layer).astype(float) / 255
            a = arr[..., 3:4]
            out[..., :3] = out[..., :3] * (1 - a) + arr[..., :3] * 255 * a
            out[..., 3:4] = out[..., 3:4] + a * (1 - out[..., 3:4])
            continue
        m = Image.new('L', (n, n), 0)
        d = ImageDraw.Draw(m)
        if op['op'] == 'poly':
            d.polygon([(x * S, y * S) for x, y in op['pts']], fill=255)
            paint = op['fill']
        elif op['op'] == 'ellipse':
            d.ellipse([(op['cx'] - op['rx']) * S, (op['cy'] - op['ry']) * S,
                       (op['cx'] + op['rx']) * S, (op['cy'] + op['ry']) * S], fill=255)
            paint = op['fill']
        elif op['op'] == 'stroke':
            pts = [(x * S, y * S) for x, y in op['pts']]
            if op['closed']:
                pts = pts + [pts[0]]
            w = op['width'] * S
            d.line(pts, fill=255, width=int(round(w)), joint='curve')
            r = w / 2
            for x, y in (pts[0], pts[-1]):
                d.ellipse([x - r, y - r, x + r, y + r], fill=255)
            paint = op['paint']
        elif op['op'] == 'arc':
            c, r, w = op['cx'] * S, op['r'] * S, op['width'] * S
            d.arc([c - r, op['cy'] * S - r, c + r, op['cy'] * S + r], op['a0'], op['a1'], fill=255, width=int(round(w)))
            for ang in (op['a0'], op['a1']):
                px, py = c + (r - w / 2) * math.cos(math.radians(ang)), op['cy'] * S + (r - w / 2) * math.sin(math.radians(ang))
                d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=255)
            paint = op['paint']
        mask = np.array(m).astype(float) / 255
        col, alp = _paint_layer(paint, n)
        a = (mask * alp)[..., None]
        out[..., :3] = out[..., :3] * (1 - a) + col * a
        out[..., 3:4] = out[..., 3:4] + a * (1 - out[..., 3:4])
    out[..., 3] *= 255
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGBA')


# ---------------------------------------------------------------- VectorDrawable writer
def _f(v):
    return ('%.2f' % v).rstrip('0').rstrip('.')


def _argb(hexc, a):
    return '#%02X%s' % (int(round(a * 255)), hexc.lstrip('#').upper())


def _gradient_xml(attr, paint, ind):
    if paint['kind'] == 'linear':
        head = ('<gradient android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s">'
                % (_f(paint['x1']), _f(paint['y1']), _f(paint['x2']), _f(paint['y2'])))
    else:
        head = ('<gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s">'
                % (_f(paint['cx']), _f(paint['cy']), _f(paint['r'])))
    items = ''.join('%s    <item android:offset="%s" android:color="%s" />\n' % (ind, _f(o), _argb(c, a)) for o, c, a in paint['stops'])
    return ('%s<aapt:attr name="android:%s">\n%s  %s\n%s%s  </gradient>\n%s</aapt:attr>\n'
            % (ind, attr, ind, head, items, ind, ind))


def _path_d(op):
    if op['op'] in ('poly',):
        pts = op['pts']
        return 'M' + ' L'.join('%s,%s' % (_f(x), _f(y)) for x, y in pts) + ' Z'
    if op['op'] == 'stroke':
        pts = op['pts']
        return 'M' + ' L'.join('%s,%s' % (_f(x), _f(y)) for x, y in pts) + (' Z' if op['closed'] else '')
    if op['op'] == 'ellipse':
        cx, cy, rx, ry = op['cx'], op['cy'], op['rx'], op['ry']
        return ('M%s,%s A%s,%s 0 1,1 %s,%s A%s,%s 0 1,1 %s,%s Z'
                % (_f(cx - rx), _f(cy), _f(rx), _f(ry), _f(cx + rx), _f(cy), _f(rx), _f(ry), _f(cx - rx), _f(cy)))
    if op['op'] == 'arc':
        r = op['r'] - op['width'] / 2  # Pillow draws the arc inside its bbox; keep the same centre line
        a0, a1 = math.radians(op['a0']), math.radians(op['a1'])
        x0, y0 = op['cx'] + r * math.cos(a0), op['cy'] + r * math.sin(a0)
        x1, y1 = op['cx'] + r * math.cos(a1), op['cy'] + r * math.sin(a1)
        large = 1 if (op['a1'] - op['a0']) % 360 > 180 else 0
        return 'M%s,%s A%s,%s 0 %d,1 %s,%s' % (_f(x0), _f(y0), _f(r), _f(r), large, _f(x1), _f(y1))
    raise ValueError(op['op'])


def vector_xml(ops):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Generated from the 7GT render by branding/icons/icons.py; do not edit by hand. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="108dp" android:height="108dp"',
             '    android:viewportWidth="108" android:viewportHeight="108">']
    for op in ops:
        if op['op'] == 'bitmap':
            raise ValueError('bitmap op in a vector')
        d = _path_d(op)
        is_stroke = op['op'] in ('stroke', 'arc')
        paint = op['paint'] if is_stroke else op['fill']
        attrs = ['android:pathData="%s"' % d]
        inner = ''
        if is_stroke:
            attrs += ['android:strokeWidth="%s"' % _f(op['width']), 'android:strokeLineCap="round"',
                      'android:strokeLineJoin="round"', 'android:fillColor="#00000000"']
            if paint['kind'] == 'solid':
                attrs.append('android:strokeColor="%s"' % _argb(paint['c'], paint['a']))
            else:
                inner = _gradient_xml('strokeColor', paint, '        ')
        else:
            if paint['kind'] == 'solid':
                attrs.append('android:fillColor="%s"' % _argb(paint['c'], paint['a']))
            else:
                inner = _gradient_xml('fillColor', paint, '        ')
        if inner:
            lines.append('    <path\n        ' + '\n        '.join(attrs) + '>\n' + inner + '    </path>')
        else:
            lines.append('    <path\n        ' + '\n        '.join(attrs) + ' />')
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------- the designs
WHITE_BODY = None


def flat_car(car, body_paint, glass='#0E141B', band='#101820', tyre='#0A0C0E', rim='#525C64', caliper='#FF4D3D',
             light='#E8F4FF', shadow=True):
    ops = []
    if shadow:
        gy = car.ground_y()
        w = (GEO['bbox'][2] - GEO['bbox'][0]) * car.s
        ops.append(ellipse(car.ox, gy - 0.2 * car.s * 10, w * 0.47, max(1.2, 3.2 * car.s * 10),
                           radial(car.ox, gy, w * 0.47, [(0, '#000000', 0.55), (1, '#000000', 0.0)])))
    for p in car.body():
        ops.append(poly(p, body_paint))
    for p in car.glass():
        ops.append(poly(p, solid(glass)))
    for p in car.band():
        ops.append(poly(p, solid(band)))
    ll = car.light_line()
    if len(ll) > 1:
        ops.append(stroke(ll, max(0.35, 2.2 * car.s), solid(light)))
    for w in car.wheels():
        ops.append(ellipse(w['cx'], w['cy'], w['rx'], w['ry'], solid(tyre)))
        ops.append(ellipse(w['cx'], w['cy'], w['rx'] * 0.68, w['ry'] * 0.68, solid(rim)))
        ops.append(ellipse(w['cx'], w['cy'], w['rx'] * 0.22, w['ry'] * 0.22, solid('#14181C')))
        # brake caliper peeking through the spokes (red, as on the render)
        ops.append(arc(w['cx'], w['cy'], w['rx'] * 0.52, 150, 215, max(0.5, w['rx'] * 0.16), solid(caliper)))
    return ops


def car_mono(car):
    ops = [poly(p, solid('#000000')) for p in car.body()]
    return ops


def design_silhouet():
    car = Car(68 / 1056, 54, 57)
    bg = [rect(linear(0, 0, 108, 108, [(0, '#1C4FB0', 1), (1, '#5AA9FF', 1)])),
          rect(radial(54, 62, 52, [(0, '#FFFFFF', 0.20), (1, '#FFFFFF', 0.0)]))]
    fg = flat_car(car, linear(0, 40, 0, 70, [(0, '#FFFFFF', 1), (1, '#D4DCE3', 1)]))
    return dict(key='silhouet', name='Silhouet', bg=bg, fg=fg, mono=car_mono(car))


def design_closeup():
    s = 0.115
    car = Car(s, 0, 0)
    # front wheel centre at (64, 72): nose, light line, front wheel and windscreen fill the icon
    wx, wy = GEO['wheels'][0]['cx'], GEO['wheels'][0]['cy']
    car.ox = 64 - (wx - car.cx) * s
    car.oy = 72 - (wy - car.cy) * s
    bg = [rect(linear(0, 0, 108, 108, [(0, '#F6B23E', 1), (1, '#E8641E', 1)]))]
    gy = car.ground_y()
    fg = [ellipse(50, gy, 46, 3.2, radial(50, gy, 46, [(0, '#5A2A00', 0.45), (1, '#5A2A00', 0.0)]))]
    fg += flat_car(car, linear(0, 30, 0, 100, [(0, '#FFFFFF', 1), (1, '#DCE2E7', 1)]), shadow=False)
    mono = [stroke(p, 2.4, solid('#000000'), closed=True) for p in car.body()]
    mono += [poly(p, solid('#000000')) for p in car.band()]
    w = car.wheels()[0]
    mono.append(ellipse(w['cx'], w['cy'], w['rx'], w['ry'], solid('#000000')))
    return dict(key='closeup', name='Close-up', bg=bg, fg=fg, mono=mono)


def design_lichtlijn():
    bg = [rect(solid('#07090B')),
          rect(radial(54, 58, 46, [(0, '#1E3A5C', 0.95), (1, '#07090B', 0.0)]))]

    def curve(y_end, y_mid, x0=17, x1=91, n=24):
        pts = []
        for i in range(n + 1):
            t = i / n
            x = x0 + (x1 - x0) * t
            y = y_end + (y_mid - y_end) * (1 - (2 * t - 1) ** 2)
            pts.append((x, y))
        return pts
    hood = curve(54, 41)
    bar = curve(59, 55.5, 19, 89)
    fade = lambda c, a: linear(19, 0, 89, 0, [(0, c, 0.0), (0.18, c, a), (0.5, c, a), (0.82, c, a), (1, c, 0.0)])
    fg = [stroke(hood, 1.6, fade('#AEB8C0', 0.85)),
          stroke(bar, 7.0, fade('#5AA9FF', 0.35)),
          stroke(bar, 3.4, fade('#9FD0FF', 0.6)),
          stroke(bar, 1.6, fade('#FFFFFF', 1.0)),
          # the corner intakes (vertical 'fangs' of the 7GT nose)
          stroke([(27, 64), (25.5, 70), (27, 77)], 1.6, solid('#AEB8C0', 0.85)),
          stroke([(81, 64), (82.5, 70), (81, 77)], 1.6, solid('#AEB8C0', 0.85)),
          stroke([(40, 71), (68, 71)], 1.2, solid('#AEB8C0', 0.5))]
    mono = [stroke(hood, 2.0, solid('#000000')), stroke(bar, 3.0, solid('#000000')),
            stroke([(27, 64), (25.5, 70), (27, 77)], 2.0, solid('#000000')),
            stroke([(81, 64), (82.5, 70), (81, 77)], 2.0, solid('#000000'))]
    return dict(key='lichtlijn', name='Lichtlijn', bg=bg, fg=fg, mono=mono)


def design_sleutel():
    car = Car(64 / 1056, 54, 63, smooth=3, simplify=7.0)
    bg = [rect(linear(0, 0, 0, 108, [(0, '#16201C', 1), (1, '#0C0F12', 1)]))]
    fg = []
    for p in car.body():
        fg.append(stroke(p, 2.2, solid('#FFFFFF'), closed=True))
    for w in car.wheels():
        r = (w['rx'] + w['ry']) / 2
        fg.append(ellipse(w['cx'], w['cy'], r * 1.02, r * 1.02, solid('#111815')))
        fg.append(ellipse(w['cx'], w['cy'], r * 0.80, r * 0.80, solid('#FFFFFF')))
        fg.append(ellipse(w['cx'], w['cy'], r * 0.55, r * 0.55, solid('#111815')))
        fg.append(ellipse(w['cx'], w['cy'], r * 0.20, r * 0.20, solid('#FFFFFF')))
    # digital key: a dot on the roof with three radio waves
    roof_y = car.p(0, GEO['bbox'][1])[1]
    kx, ky = 56, roof_y - 4.5
    green = '#46D8A0'
    fg.append(ellipse(kx, ky, 2.1, 2.1, solid(green)))
    for i, r in enumerate((7.0, 11.5, 16.0)):
        fg.append(arc(kx, ky, r, 225, 315, 2.2, solid(green, 1.0 - 0.18 * i)))
    mono = [stroke(p, 2.6, solid('#000000'), closed=True) for p in car.body()]
    for w in car.wheels():
        r = (w['rx'] + w['ry']) / 2
        mono.append(ellipse(w['cx'], w['cy'], r * 0.80, r * 0.80, solid('#000000')))
    mono.append(ellipse(kx, ky, 2.4, 2.4, solid('#000000')))
    for r in (7.0, 11.5, 16.0):
        mono.append(arc(kx, ky, r, 225, 315, 2.4, solid('#000000')))
    return dict(key='sleutel', name='Digitale sleutel', bg=bg, fg=fg, mono=mono)


def design_foto():
    x0, y0, x1, y1 = GEO['bbox']
    pad = 6
    crop = Image.open(SRC).convert('RGBA').crop((x0 - pad, y0 - pad, x1 + pad, y1 + pad))
    path = os.path.join(OUT, 'foto_crop.png')
    crop.save(path)
    w = 70.0
    h = w * crop.height / crop.width
    bg = [rect(solid('#0C0F12')),
          ellipse(54, 50, 56, 44, radial(54, 50, 56, [(0, '#46525E', 1), (0.6, '#1A2128', 1), (1, '#0C0F12', 1)])),
          ellipse(54, 69.5, 38, 4.5, radial(54, 69.5, 38, [(0, '#AFC7DD', 0.35), (1, '#AFC7DD', 0.0)]))]
    fg = [bitmap(path, 54 - w / 2, 57 - h / 2, w, h)]
    car = Car(w / (x1 - x0 + 2 * pad), 54, 57)
    return dict(key='foto', name='Foto', bg=bg, fg=fg, mono=car_mono(car), raster=(path, w, h))


DESIGNS = [design_silhouet(), design_closeup(), design_lichtlijn(), design_sleutel(), design_foto()]


# ---------------------------------------------------------------- previews
def superellipse_mask(n_px, k=4.0, scale=1.0):
    yy, xx = np.mgrid[0:n_px, 0:n_px].astype(float)
    c = (n_px - 1) / 2
    r = c * scale
    v = (np.abs((xx - c) / r) ** k + np.abs((yy - c) / r) ** k)
    return np.clip((1.0 - v) * n_px / 6 + 0.5, 0, 1)


def circle_mask(n_px):
    yy, xx = np.mgrid[0:n_px, 0:n_px].astype(float)
    c = (n_px - 1) / 2
    d = np.hypot(xx - c, yy - c)
    return np.clip(c - d + 0.5, 0, 1)


def masked(full, mask_fn, px):
    """The visible part of an adaptive icon: the central 72/108 of the layers, masked."""
    inner = full.crop((int(18 * S), int(18 * S), int(90 * S), int(90 * S))).resize((px, px), Image.LANCZOS)
    arr = np.array(inner).astype(float)
    arr[..., 3] *= mask_fn(px)
    return Image.fromarray(arr.astype(np.uint8), 'RGBA')


def compose(d):
    full = render(d['bg'] + d['fg'])
    return full


def themed(d):
    """Android 13 'themed icon': monochrome layer tinted on a pastel background."""
    mono = render([rect(solid('#DCE6F2'))] + [dict(o, **({'fill': solid('#24405E')} if 'fill' in o else {'paint': solid('#24405E')})) for o in d['mono']])
    return mono


def main():
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 26)
    fontb = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 30)
    current = Image.open(os.path.join(ROOT, 'branding', 'export', 'ic_launcher_512.png')).convert('RGBA')
    drawable = os.path.join(RES, 'drawable')
    nodpi = os.path.join(RES, 'drawable-nodpi')
    anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
    for dname in (drawable, nodpi, anydpi):
        os.makedirs(dname, exist_ok=True)
    fulls = []
    for i, d in enumerate(DESIGNS, 1):
        full = compose(d)
        fulls.append(full)
        full.resize((432, 432), Image.LANCZOS).save(os.path.join(OUT, '%d_%s_full.png' % (i, d['key'])))
        name = 'ic_launcher_%s' % d['key']
        if 'raster' not in d:
            open(os.path.join(drawable, name + '_foreground.xml'), 'w').write(vector_xml(d['fg']))
        else:
            # bitmap foreground: 432 px = 108 dp at xxxhdpi
            layer = render(d['fg'])
            layer.resize((432, 432), Image.LANCZOS).save(os.path.join(nodpi, name + '_foreground.png'), optimize=True)
        open(os.path.join(drawable, name + '_background.xml'), 'w').write(vector_xml(d['bg']))
        open(os.path.join(drawable, name + '_monochrome.xml'), 'w').write(vector_xml(d['mono']))
        open(os.path.join(anydpi, name + '.xml'), 'w').write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by branding/icons/icons.py ("%s"). -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/%s_background" />\n'
            '    <foreground android:drawable="@drawable/%s_foreground" />\n'
            '    <monochrome android:drawable="@drawable/%s_monochrome" />\n'
            '</adaptive-icon>\n' % (d['name'], name, name, name))

    # Sheet 1: the five designs large, squircle (Samsung) + circle + themed + real size
    W, rowh = 1400, 300
    sheet = Image.new('RGBA', (W, 120 + rowh * len(DESIGNS)), (18, 22, 26, 255))
    dr = ImageDraw.Draw(sheet)
    dr.text((40, 30), 'ZeekRemote – 5 nieuwe app-iconen (op basis van de 7GT)', font=fontb, fill=(234, 238, 241))
    dr.text((40, 75), 'vorm Samsung      ·      rond      ·      Android-thema      ·      ware grootte', font=font, fill=(139, 151, 158))
    for i, (d, full) in enumerate(zip(DESIGNS, fulls)):
        y = 120 + i * rowh
        sheet.alpha_composite(masked(full, lambda n: superellipse_mask(n), 240), (40, y + 20))
        sheet.alpha_composite(masked(full, circle_mask, 240), (310, y + 20))
        sheet.alpha_composite(masked(themed(d), lambda n: superellipse_mask(n), 240), (580, y + 20))
        small = masked(full, lambda n: superellipse_mask(n), 64)
        sheet.alpha_composite(small, (870, y + 108))
        dr.text((970, y + 100), '%d. %s' % (i + 1, d['name']), font=fontb, fill=(234, 238, 241))
    sheet.convert('RGB').save(os.path.join(OUT, 'iconen-overzicht.png'))

    # Sheet 2: home-screen mock-up at phone size, with the current icon for comparison
    wall = np.zeros((900, 1080, 3))
    yy = np.linspace(0, 1, 900)[:, None]
    for k, c0, c1 in ((0, 30, 10), (1, 46, 14), (2, 74, 18)):
        wall[..., k] = (c0 * (1 - yy) + c1 * yy)
    hs = Image.fromarray(wall.astype(np.uint8)).convert('RGBA')
    dr = ImageDraw.Draw(hs)
    dr.text((60, 50), 'Zo ziet het eruit op je startscherm', font=fontb, fill=(234, 238, 241))
    labels = ['Huidig'] + [d['name'] for d in DESIGNS]
    cur = np.array(current.resize((150, 150), Image.LANCZOS)).astype(float)
    icons = [Image.fromarray(cur.astype(np.uint8), 'RGBA')]  # the current icon is already shaped
    icons += [masked(f, lambda n: superellipse_mask(n), 150) for f in fulls]
    for j, (ic, lab) in enumerate(zip(icons, labels)):
        cx = 120 + (j % 3) * 320
        cy = 170 + (j // 3) * 340
        hs.alpha_composite(ic, (cx, cy))
        tw = dr.textlength(lab, font=font)
        dr.text((cx + 75 - tw / 2, cy + 165), lab, font=font, fill=(234, 238, 241))
    hs.convert('RGB').save(os.path.join(OUT, 'iconen-startscherm.png'))
    print('ok', [d['key'] for d in DESIGNS])


if __name__ == '__main__':
    main()
