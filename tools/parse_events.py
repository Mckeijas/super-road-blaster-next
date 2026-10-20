#!/usr/bin/env python3
"""Parse Super Road Blaster chapter XMLs into a JSON table usable by the Android frontend."""
import sys, os, json, glob, xml.dom.minidom as md
FPS = 23.9777
def t(node):
    if node is None: return None
    return int(node.getAttribute('min'))*60000 + int(node.getAttribute('second'))*1000 + int(node.getAttribute('ms'))
def child(el, name):
    for c in el.childNodes:
        if c.nodeType == c.ELEMENT_NODE and c.tagName == name: return c
    return None
def times(el):
    tl = child(el, 'timeline')
    s = tl.getElementsByTagName('timestart'); e = tl.getElementsByTagName('timeend')
    return t(s[-1]) if s else 0, t(e[-1]) if e else None
def params(el):
    p = child(el, 'params'); d = {}
    if p:
        for c in p.childNodes:
            if c.nodeType == c.ELEMENT_NODE: d[c.getAttribute('key')] = c.getAttribute('value')
    return d
def result(el):
    r = child(el, 'result')
    if not r: return None
    for c in r.childNodes:
        if c.nodeType == c.ELEMENT_NODE: return {'type': c.tagName, 'name': c.getAttribute('name') or None}
def frame(ms): return int(ms * FPS * 0.001)
def parse(path):
    dom = md.parse(path); ch = dom.getElementsByTagName('chapter')[0]
    s, e = times(ch); p = params(ch)
    out = {'name': os.path.basename(path)[:-4], 'startMs': s, 'endMs': e, 'durationMs': e - s,
           'level': int(p.get('level', 0) or 0), 'controller': p.get('controller'),
           'result': result(ch), 'events': []}
    for ev in ch.getElementsByTagName('event'):
        es, ee = times(ev); ep = params(ev); typ = ev.getAttribute('type')
        if typ == 'direction': typ = 'direction-' + ep['type']
        if typ == 'macro' and ev.getAttribute('name').startswith('vibrate'):
            out.setdefault('rumble', []).append(max(0, frame(es) - frame(s)))
        out['events'].append({'type': typ, 'start': max(0, frame(es) - frame(s)),
            'end': max(0, frame(ee if ee is not None else es) - frame(s)), 'result': result(ev)})
    return out
def write_txt(chs, path):
    """chapters.txt consumed by srb_core (chapter id == alphabetical index == MSU-1 track)."""
    chs = sorted(chs, key=lambda c: c['name'])
    with open(path, 'w') as f:
        for i, c in enumerate(chs):
            f.write('C %d %d %d %s\n' % (i, c['level'], c['durationMs'], c['name']))
            for e in c['events']:
                if e['type'] in ('direction-left', 'direction-right', 'accelerate', 'brake'):
                    f.write('E %s %d %d\n' % (e['type'], e['start'], e['end']))
                elif e['type'].startswith('macro') or e['type'] == 'shake':
                    pass
            for e in c.get('rumble', []):
                f.write('R %d\n' % e)

if __name__ == '__main__':
    chs = [parse(f) for f in sorted(glob.glob(os.path.join(sys.argv[1], '*.xml')))]
    json.dump(chs, open(sys.argv[2], 'w'), indent=1)
    if len(sys.argv) > 3: write_txt(chs, sys.argv[3])
    from collections import Counter
    print(len(chs), Counter(e['type'] for c in chs for e in c['events']))
