#!/usr/bin/env python3
"""Super Road Blaster Next - HD video add-on builder.

Builds the optional "pack HD" from a HD copy of the Road Blaster film (for example the
iOS/iPad release, 1440x1080) and YOUR Super Road Blaster game files. Nothing from the
film ships with the app: everyone builds the pack from their own copy.

Requirements: Python 3, numpy, opencv-python, ffmpeg/ffprobe in PATH.

    make_hdpack.py align   <film.mp4> <game_dir> <work>      # chapter -> film time (audio match)
    make_hdpack.py track   <film.mp4> <work> <start_s> <dur_s> # iOS dashboard presence/offset
    make_hdpack.py patches <film.mp4> <work>                  # clean "?" / pause / screen patches
    make_hdpack.py pack    <film.mp4> <work> <out.zip>         # hd.mp4 + hdpack.json + patches
    make_hdpack.py all     <film.mp4> <game_dir> <work> <out.zip>

How it works
  * align: the game's MSU-1 tracks (SuperRoadBlaster-N.pcm) are the film's own sound. Each
    track is found inside the film's audio by normalised cross-correlation (FFT, 8 kHz), which
    gives the exact start of every chapter. Chapters that are not in the film (crash scenes,
    game over, high scores) keep the original MSU-1 video in the app.
  * track: the iOS release draws its own dashboard over the film. A template of a fixed part
    of it is searched in every frame (20 fps), giving when it is on screen and its offset
    (the dashboard shakes with impacts).
  * patches: from a median of dashboard frames, the iPad "?" and pause buttons and the
    digital screen are painted out (inpainting); the app draws the player's real score and
    lives on that screen.
"""
import base64, json, os, subprocess, sys, zipfile
import numpy as np
import cv2

SR = 8000                       # analysis sample rate
W2, H2 = 720, 540               # tracking resolution (1/2 of 1440x1080)
SC = 2                          # film pixels per tracking pixel
TRACK_FPS = 60
# Dashboard layout in the 1440x1080 film (iOS release)
TPL = (540, 830, 960, 990)      # template: screen frame + bezel around it (x0, y0, x1, y1)
HELP = (520, 828, 46)           # "?" button: centre x, centre y, radius
PAUSE = (980, 825, 46)          # pause button
SCREEN = (566, 856, 936, 974)   # digital screen inner area (score + lives)
LEVER = (1050, 640, 1440, 1080) # gear lever area: covered by the app's own lever (up/mid/down)
HEAD = (1120, 830, 1410, 940)   # lever head in its middle position (search template)


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def chapters_json(game_dir):
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (os.path.join(here, '..', '..', 'android', 'app', 'src', 'main', 'assets', 'game', 'chapters.json'),
              os.path.join(game_dir, 'chapters.json')):
        if os.path.exists(p):
            return json.load(open(p))
    raise SystemExit('chapters.json not found')


def pcm8k(path):
    r = run(['ffmpeg', '-v', 'error', '-f', 's16le', '-ar', '44100', '-ac', '2', '-skip_initial_bytes', '8',
             '-i', path, '-ac', '1', '-ar', str(SR), '-f', 's16le', '-'], stdout=subprocess.PIPE)
    return np.frombuffer(r.stdout, '<i2').astype(np.float32)


# ------------------------------------------------------------------------------- align
def align(film, game_dir, work):
    os.makedirs(work, exist_ok=True)
    r = run(['ffmpeg', '-v', 'error', '-i', film, '-vn', '-ac', '1', '-ar', str(SR), '-f', 's16le', '-'],
            stdout=subprocess.PIPE)
    hd = np.frombuffer(r.stdout, '<i2').astype(np.float32)
    hd /= np.abs(hd).max() + 1
    n = len(hd)
    nfft = 1 << int(np.ceil(np.log2(n + SR * 130)))
    HD = np.fft.rfft(hd, nfft)
    csum = np.concatenate([[0], np.cumsum(hd.astype(np.float64) ** 2)])
    out = {}
    for c in chapters_json(game_dir):
        if c['kind'] == 'crash':
            continue
        i = c['id']
        p = os.path.join(game_dir, 'SuperRoadBlaster-%d.pcm' % i)
        if not os.path.exists(p):
            continue
        t = pcm8k(p)
        t /= np.abs(t).max() + 1
        L = len(t)
        if L < SR or L >= n:
            continue
        corr = np.fft.irfft(HD * np.conj(np.fft.rfft(t, nfft)), nfft)[:n - L + 1]
        e = np.sqrt(np.maximum(csum[L:] - csum[:-L], 1e-9)) * np.sqrt((t.astype(np.float64) ** 2).sum())
        ncc = corr / e[:len(corr)]
        k = int(np.argmax(ncc))
        best = float(ncc[k])
        m = ncc.copy()
        m[max(0, k - SR):k + SR] = -1
        second = float(m.max())
        ok = best > 0.18 and best > second * 2.5
        out[str(i)] = {'start': round(k / SR, 4), 'ncc': round(best, 3), 'second': round(second, 3), 'ok': ok,
                       'dur': round(L / SR, 3), 'name': c['name']}
        print('%3d %-22s %9.3f s  ncc %.3f / %.3f  %s' % (i, c['name'], k / SR, best, second, 'OK' if ok else '--'),
              flush=True)
    json.dump(out, open(os.path.join(work, 'align.json'), 'w'), indent=1)


# ------------------------------------------------------------------------------- track
def template(film, work):
    p = os.path.join(work, 'template.png')
    if os.path.exists(p):
        return cv2.imread(p, cv2.IMREAD_GRAYSCALE)
    al = json.load(open(os.path.join(work, 'align.json')))
    t = al['37']['start'] + 5.0 if '37' in al else 484.6        # a cockpit scene (level 3)
    fr = grab(film, t, W2, H2, gray=True)
    x0, y0, x1, y1 = [v // SC for v in TPL]
    tpl = fr[y0:y1, x0:x1].copy()
    cv2.imwrite(p, tpl)
    return tpl


def grab(film, t, w, h, gray=False):
    r = run(['ffmpeg', '-v', 'error', '-ss', '%.3f' % t, '-i', film, '-frames:v', '1', '-vf', 'scale=%d:%d' % (w, h),
             '-pix_fmt', 'gray' if gray else 'bgr24', '-f', 'rawvideo', '-'], stdout=subprocess.PIPE)
    a = np.frombuffer(r.stdout, np.uint8)
    return a.reshape(h, w) if gray else a.reshape(h, w, 3)


def sub(res, ml):
    """Sub-pixel peak (parabola fit on the correlation surface)."""
    x, y = ml
    h, w = res.shape
    sx = sy = 0.0
    if 0 < x < w - 1:
        a, b, c = res[y, x - 1], res[y, x], res[y, x + 1]
        d = a - 2 * b + c
        sx = 0.5 * (a - c) / d if d != 0 else 0.0
    if 0 < y < h - 1:
        a, b, c = res[y - 1, x], res[y, x], res[y + 1, x]
        d = a - 2 * b + c
        sy = 0.5 * (a - c) / d if d != 0 else 0.0
    return max(-0.5, min(0.5, sx)), max(-0.5, min(0.5, sy))


def track(film, work, start, dur):
    tpl = template(film, work)
    x0, y0 = TPL[0] // SC, TPL[1] // SC
    th, tw = tpl.shape
    pad = 40
    cmd = ['ffmpeg', '-v', 'error', '-ss', '%.3f' % start, '-t', '%.3f' % dur, '-i', film,
           '-vf', 'fps=%d,scale=%d:%d' % (TRACK_FPS, W2, H2), '-pix_fmt', 'gray', '-f', 'rawvideo', '-']
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    rows = []
    idx = 0
    fs = W2 * H2
    while True:
        buf = proc.stdout.read(fs)
        if len(buf) < fs:
            break
        f = np.frombuffer(buf, np.uint8).reshape(H2, W2)
        win = f[max(0, y0 - pad):y0 + th + pad, max(0, x0 - pad):x0 + tw + pad]
        res = cv2.matchTemplate(win, tpl, cv2.TM_CCOEFF_NORMED)
        _, mv, _, ml = cv2.minMaxLoc(res)
        sx, sy = sub(res, ml)
        dx = (ml[0] + sx - min(pad, x0)) * SC
        dy = (ml[1] + sy - min(pad, y0)) * SC
        rows.append((round(start + idx / TRACK_FPS, 4), round(float(mv), 3), round(float(dx), 2), round(float(dy), 2)))
        idx += 1
    proc.wait()
    name = os.path.join(work, 'track_%07.1f.json' % start)
    json.dump(rows, open(name, 'w'))
    on = sum(1 for r in rows if r[1] > 0.6)
    print('track %s: %d frames, dashboard on %d' % (name, len(rows), on))


def load_track(work):
    rows = []
    for f in sorted(os.listdir(work)):
        if f.startswith('track_') and f.endswith('.json'):
            rows += json.load(open(os.path.join(work, f)))
    rows.sort()
    return rows


# ------------------------------------------------------------------------------- patches
def patches(film, work):
    rows = [r for r in load_track(work) if r[1] > 0.9]
    if len(rows) < 10:
        raise SystemExit('not enough dashboard frames: run "track" first')
    pick = [rows[int(i)] for i in np.linspace(0, len(rows) - 1, 41)]
    frames = []
    for r in pick:              # move every frame back to the template position
        f = grab(film, r[0], 1440, 1080)
        M = np.float32([[1, 0, -r[2]], [0, 1, -r[3]]])
        frames.append(cv2.warpAffine(f, M, (1440, 1080), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE))
    frames = np.stack(frames)
    med = np.median(frames, axis=0).astype(np.uint8)
    cv2.imwrite(os.path.join(work, 'dashboard_median.png'), med)
    out = {}
    for name, (cx, cy, r) in (('help', HELP), ('pause', PAUSE)):
        # keep the dark knob, erase only its light symbol ("?" / "II")
        x0, y0, x1, y1 = cx - r - 8, cy - r - 8, cx + r + 8, cy + r + 8
        crop = med[y0:y1, x0:x1].copy()
        g = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        disc = np.zeros(g.shape, np.uint8)
        cv2.circle(disc, (cx - x0, cy - y0), int(r * 0.72), 255, -1)
        glyph = ((g > 95) & (disc > 0)).astype(np.uint8) * 255
        glyph = cv2.dilate(glyph, np.ones((7, 7), np.uint8))
        clean = cv2.inpaint(crop, glyph, 6, cv2.INPAINT_TELEA).astype(np.float32)
        clean = cv2.GaussianBlur(clean, (0, 0), 1.2) * 0.4 + clean * 0.6
        alpha = np.zeros(g.shape, np.float32)
        cv2.circle(alpha, (cx - x0, cy - y0), int(r * 0.78), 1.0, -1)
        alpha = cv2.GaussianBlur(alpha, (0, 0), 2)
        rgba = np.dstack([np.clip(clean, 0, 255).astype(np.uint8), (alpha * 255).astype(np.uint8)])
        cv2.imwrite(os.path.join(work, 'patch_%s.png' % name), rgba)
        out[name] = [int(x0), int(y0), int(x1 - x0), int(y1 - y0)]
    # digital screen: empty background (the app prints score and lives on it)
    x0, y0, x1, y1 = SCREEN
    scr = med[y0:y1, x0:x1].copy()
    # the screen is dark glass: take its typical colour and keep the subtle vignette
    base = np.median(scr.reshape(-1, 3), axis=0)
    clean = np.empty_like(scr)
    clean[:] = base.astype(np.uint8)
    yy, xx = np.mgrid[0:scr.shape[0], 0:scr.shape[1]]
    vig = 1.0 - 0.18 * (((xx / scr.shape[1] - 0.5) ** 2) * 2 + ((yy / scr.shape[0] - 0.5) ** 2) * 2)
    clean = np.clip(clean.astype(np.float32) * vig[..., None], 0, 255).astype(np.uint8)
    rgba = np.dstack([clean, np.full(scr.shape[:2], 255, np.uint8)])
    cv2.imwrite(os.path.join(work, 'patch_screen.png'), rgba)
    out['screen'] = [x0, y0, x1 - x0, y1 - y0]
    json.dump(out, open(os.path.join(work, 'patches.json'), 'w'))
    print('patches', out)


# ------------------------------------------------------------------------------- lever
def lever(film, work, start, dur, fps=6):
    """Measures the lever head height in dashboard frames (aligned with the tracked offset)."""
    rows = load_track(work)
    tt = np.array([r[0] for r in rows])
    x0, y0, x1, y1 = LEVER
    w, h = x1 - x0, y1 - y0
    cmd = ['ffmpeg', '-v', 'error', '-ss', '%.3f' % start, '-t', '%.3f' % dur, '-i', film,
           '-vf', 'fps=%d,crop=%d:%d:%d:%d' % (fps, w, h, x0, y0), '-pix_fmt', 'bgr24', '-f', 'rawvideo', '-']
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    head = os.path.join(work, 'lever_head.png')
    tpl = cv2.imread(head, cv2.IMREAD_GRAYSCALE) if os.path.exists(head) else None
    out, i, fs = [], 0, w * h * 3
    keep = os.path.join(work, 'lever_frames')
    os.makedirs(keep, exist_ok=True)
    while True:
        buf = proc.stdout.read(fs)
        if len(buf) < fs:
            break
        t = start + i / fps
        i += 1
        k = int(np.clip(np.searchsorted(tt, t), 0, len(rows) - 1))
        _, sc, dx, dy = rows[k]
        if sc < 0.9 or abs(tt[k] - t) > 0.05:
            continue
        f = np.frombuffer(buf, np.uint8).reshape(h, w, 3)
        f = cv2.warpAffine(f, np.float32([[1, 0, -dx], [0, 1, -dy]]), (w, h), borderMode=cv2.BORDER_REPLICATE)
        g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
        if tpl is None:      # first frame of the first chunk with the lever in the middle
            hx0, hy0, hx1, hy1 = HEAD[0] - x0, HEAD[1] - y0, HEAD[2] - x0, HEAD[3] - y0
            tpl = g[hy0:hy1, hx0:hx1].copy()
            cv2.imwrite(head, tpl)
        band = g[:, HEAD[0] - x0 - 10:HEAD[2] - x0 + 10]
        res = cv2.matchTemplate(band, tpl, cv2.TM_CCOEFF_NORMED)
        _, mv, _, ml = cv2.minMaxLoc(res)
        hy = ml[1] - (HEAD[1] - y0)          # 0 = middle, < 0 up, > 0 down
        out.append((round(t, 3), int(hy), round(float(mv), 3)))
        if mv > 0.6 and (len(out) % 3 == 0 or abs(hy) > 30):
            cv2.imwrite(os.path.join(keep, '%08.3f_%+04d.png' % (t, hy)), f)
    proc.wait()
    json.dump(out, open(os.path.join(work, 'lever_%07.1f.json' % start), 'w'))
    ys = [o[1] for o in out if o[2] > 0.6]
    print('lever %.0f s: %d frames, head y: %s' % (start, len(out), np.histogram(ys, bins=[-400, -40, 40, 400])[0]))


def lever_images(work):
    """Median image of the lever area for each position."""
    keep = os.path.join(work, 'lever_frames')
    files = sorted(os.listdir(keep))
    groups = {'up': [], 'mid': [], 'down': []}
    for f in files:
        hy = int(f.split('_')[1].split('.')[0])
        groups['up' if hy < -40 else 'down' if hy > 40 else 'mid'].append(f)
    info, meds = {}, {}
    for name, fl in groups.items():
        if not fl:
            raise SystemExit('no frames with the lever %s' % name)
        fl = [fl[int(i)] for i in np.linspace(0, len(fl) - 1, min(len(fl), 41))]
        med = np.median(np.stack([cv2.imread(os.path.join(keep, f)) for f in fl]), axis=0).astype(np.uint8)
        meds[name] = med
        info[name] = len(fl)
    # only the dashboard part is painted: find its white top trim in the middle image
    g = cv2.cvtColor(meds['mid'], cv2.COLOR_BGR2GRAY).astype(np.float32)
    band = g[:, 40:340].mean(1)
    trim = next((y for y in range(10, len(band)) if band[y] > 185), 100)
    alpha = np.clip((np.arange(g.shape[0]) - (trim - 10)) / 8.0, 0, 1)
    a8 = (np.repeat(alpha[:, None], g.shape[1], 1) * 255).astype(np.uint8)
    for name, med in meds.items():
        cv2.imwrite(os.path.join(work, 'lever_%s.png' % name), np.dstack([med, a8]))
    info['trimRow'] = int(trim)
    print('lever images', info)


# ------------------------------------------------------------------------------- pack
def pack(film, work, out_zip):
    al = json.load(open(os.path.join(work, 'align.json')))
    rows = load_track(work)
    pt = json.load(open(os.path.join(work, 'patches.json')))
    # dashboard track: presence + offset, one sample every 1/TRACK_FPS s from t0
    t0 = rows[0][0] if rows else 0.0
    n = int(round((rows[-1][0] - t0) * TRACK_FPS)) + 1 if rows else 0
    on = np.zeros(n, np.uint8)
    dx = np.zeros(n, '<i2')      # quarter pixels
    dy = np.zeros(n, '<i2')
    for t, s, x, y in rows:
        i = int(round((t - t0) * TRACK_FPS))
        if 0 <= i < n and s > 0.6:
            on[i] = 1
            dx[i] = int(round(x * 4))
            dy[i] = int(round(y * 4))
    meta = json.loads(run(['ffprobe', '-v', 'error', '-select_streams', 'v', '-show_entries',
                           'stream=width,height,r_frame_rate', '-of', 'json', film],
                          stdout=subprocess.PIPE).stdout)['streams'][0]
    pack_json = {
        'format': 1,
        'video': 'hd.mp4', 'width': meta['width'], 'height': meta['height'], 'fps': meta['r_frame_rate'],
        'chapters': {k: v['start'] for k, v in al.items() if v['ok']},
        'dashboard': {'t0': t0, 'fps': TRACK_FPS, 'offsetUnit': 0.25,
                      'on': base64.b64encode(on.tobytes()).decode(),
                      'dx': base64.b64encode(dx.tobytes()).decode(),
                      'dy': base64.b64encode(dy.tobytes()).decode(),
                      'patches': pt},
        # SNES picture (256x239) -> film pixels: x' = x*sx + ox, y' = (y - 8)*sy + oy
        'snesToFilm': {'sx': 5.6, 'sy': 5.52, 'ox': 4, 'oy': 12},
        'lever': {'rect': [LEVER[0], LEVER[1], LEVER[2] - LEVER[0], LEVER[3] - LEVER[1]],
                  'images': {'up': 'lever_up.png', 'mid': 'lever_mid.png', 'down': 'lever_down.png'}},
    }
    vid = os.path.join(work, 'hd.mp4')
    if not os.path.exists(vid):
        run(['ffmpeg', '-v', 'error', '-y', '-i', film, '-map', '0:v:0', '-c', 'copy', '-an', '-dn',
             '-movflags', '+faststart', vid])
    with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('hdpack.json', json.dumps(pack_json))
        for f in ('patch_help.png', 'patch_pause.png', 'patch_screen.png', 'lever_up.png', 'lever_mid.png', 'lever_down.png'):
            z.write(os.path.join(work, f), f)
        z.write(vid, 'hd.mp4')
    print('pack', out_zip, os.path.getsize(out_zip) // (1 << 20), 'MB,', len(pack_json['chapters']), 'chapters')


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    cmd = a[0]
    if cmd == 'align':
        align(a[1], a[2], a[3])
    elif cmd == 'track':
        track(a[1], a[2], float(a[3]), float(a[4]))
    elif cmd == 'patches':
        patches(a[1], a[2])
    elif cmd == 'lever':
        lever(a[1], a[2], float(a[3]), float(a[4]))
    elif cmd == 'leverimages':
        lever_images(a[1])
    elif cmd == 'pack':
        pack(a[1], a[2], a[3])
    elif cmd == 'all':
        film, game, work, out = a[1:5]
        align(film, game, work)
        dur = float(json.loads(run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', film],
                                   stdout=subprocess.PIPE).stdout)['format']['duration'])
        s = 0.0
        while s < dur:
            track(film, work, s, 300.0)
            s += 300.0
        patches(film, work)
        s = 0.0
        while s < dur:
            lever(film, work, s, 300.0)
            s += 300.0
        lever_images(work)
        pack(film, work, out)
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
