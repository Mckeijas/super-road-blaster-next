#!/usr/bin/env python3
"""Finds the positions of the gear lever (right side of the iOS dashboard) in the HD film:
samples dashboard frames, aligns them with the tracked offset, clusters the lever area and
writes one median image per cluster.   lever_probe.py <film.mp4> <work> [samples]"""
import json, os, subprocess, sys
import numpy as np
import cv2

LEVER = (1080, 800, 1440, 1080)    # x0, y0, x1, y1 in the 1440x1080 template frame


def main():
    film, work = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 240
    rows = []
    for f in sorted(os.listdir(work)):
        if f.startswith('track_'):
            rows += json.load(open(os.path.join(work, f)))
    rows = [r for r in rows if r[1] > 0.9]
    pick = [rows[int(i)] for i in np.linspace(0, len(rows) - 1, n)]
    x0, y0, x1, y1 = LEVER
    crops, times = [], []
    for t, s, dx, dy in pick:
        r = subprocess.run(['ffmpeg', '-v', 'error', '-ss', '%.3f' % t, '-i', film, '-frames:v', '1',
                            '-f', 'rawvideo', '-pix_fmt', 'bgr24', '-'], stdout=subprocess.PIPE)
        fr = np.frombuffer(r.stdout, np.uint8).reshape(1080, 1440, 3)
        M = np.float32([[1, 0, -dx], [0, 1, -dy]])
        fr = cv2.warpAffine(fr, M, (1440, 1080), borderMode=cv2.BORDER_REPLICATE)
        crops.append(fr[y0:y1, x0:x1])
        times.append(t)
    data = np.stack([cv2.resize(c, (90, 70)).reshape(-1) for c in crops]).astype(np.float32)
    best = None
    for k in (2, 3, 4):
        crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 50, 0.5)
        comp, lab, cen = cv2.kmeans(data, k, None, crit, 8, cv2.KMEANS_PP_CENTERS)
        print('k', k, 'compactness', int(comp))
        if k == 3:
            best = lab.ravel()
    out = {}
    for c in range(3):
        idx = np.where(best == c)[0]
        med = np.median(np.stack([crops[i] for i in idx]), axis=0).astype(np.uint8)
        cv2.imwrite(os.path.join(work, 'lever_cluster%d.png' % c), med)
        out[c] = [times[i] for i in idx[:12]]
        print('cluster', c, len(idx), 'frames, e.g.', out[c][:6])
    json.dump(out, open(os.path.join(work, 'lever_clusters.json'), 'w'))
    # montage ordered by the main variation (first principal component)
    mean = data.mean(0)
    u, sv, vt = np.linalg.svd(data - mean, full_matrices=False)
    pc = (data - mean) @ vt[0]
    order = np.argsort(pc)
    sel = order[np.linspace(0, len(order) - 1, 24).astype(int)]
    tiles = [cv2.resize(crops[i], (180, 140)) for i in sel]
    for i, t in zip(sel, tiles):
        cv2.putText(t, '%.1f' % times[i], (4, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 255), 1)
    rows = [np.concatenate(tiles[r * 8:(r + 1) * 8], 1) for r in range(3)]
    cv2.imwrite(os.path.join(work, 'lever_montage.png'), np.concatenate(rows, 0))
    np.save(os.path.join(work, 'lever_pc.npy'), np.array([times, pc]))


if __name__ == '__main__':
    main()
