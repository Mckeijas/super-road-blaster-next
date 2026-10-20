#!/usr/bin/env python3
"""MSU-1 Enhanced: declip, gentle denoise/EQ and EBU R128 loudness normalisation
for every SuperRoadBlaster-N.pcm. Output: FLAC (44.1 kHz stereo) per track, decoded
by the app at runtime, plus loudness report.  Usage: enhance_audio.py <in_dir> <out_dir>"""
import json, os, subprocess, sys, tempfile, struct
from concurrent.futures import ThreadPoolExecutor
IN, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
TARGET_I, TARGET_TP = -14.0, -1.0
CHAIN = ("adeclip=window=55:overlap=75:arorder=8:threshold=10,"
         "afftdn=nr=5:nf=-55:tn=1,"
         "equalizer=f=70:t=q:w=0.8:g=1.5,equalizer=f=3200:t=q:w=1.2:g=1.0,equalizer=f=9500:t=q:w=1.0:g=1.2")
def ff(args): return subprocess.run(['ffmpeg','-hide_banner','-nostdin','-y']+args, capture_output=True, text=True)
def one(i):
    src = os.path.join(IN, 'SuperRoadBlaster-%d.pcm' % i)
    raw = open(src,'rb').read(); loop = struct.unpack('<I', raw[4:8])[0]
    inp = ['-f','s16le','-ar','44100','-ac','2','-i','-']
    with tempfile.NamedTemporaryFile(suffix='.pcm') as t:
        t.write(raw[8:]); t.flush()
        inp = ['-f','s16le','-ar','44100','-ac','2','-i',t.name]
        # pass 1: measure loudness after the restoration chain
        r = ff(inp + ['-af', CHAIN + ',loudnorm=I=%g:TP=%g:LRA=14:print_format=json' % (TARGET_I, TARGET_TP), '-f','null','-'])
        js = r.stderr[r.stderr.rfind('{'):r.stderr.rfind('}')+1]
        m = json.loads(js)
        dur = (len(raw)-8)/4/44100
        nsamp = (len(raw) - 8) // 4
        if dur < 3.0 or float(m['input_i']) < -60:   # too short for R128: peak-safe gain only
            gain = 0.0
        else:                                        # linear gain to the target loudness
            gain = max(-12.0, min(12.0, TARGET_I - float(m['input_i'])))
        # deterministic: linear gain + true-peak style limiter, then force the exact
        # original length (MSU-1 audio must stay frame-accurate with the video)
        ln = ('volume=%.2fdB,alimiter=limit=%.3f:attack=2:release=40:level=false,'
              'apad=whole_len=%d,atrim=end_sample=%d' % (gain, 10 ** (TARGET_TP / 20.0), nsamp, nsamp))
        out = os.path.join(OUT, 'SuperRoadBlaster-%d.flac' % i)
        r2 = ff(inp + ['-af', CHAIN + ',aresample=44100:resampler=soxr,' + ln, '-c:a','flac','-compression_level','8','-sample_fmt','s16', out])
        if r2.returncode: raise RuntimeError(r2.stderr[-500:])
    return i, m['input_i'], dur, loop
with ThreadPoolExecutor(4) as ex:
    res = list(ex.map(one, range(122)))
json.dump(res, open(os.path.join(OUT, 'report.json'), 'w'), indent=0)
print('done', len(res))
