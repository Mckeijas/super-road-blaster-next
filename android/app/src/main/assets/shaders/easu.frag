#version 300 es
// HD restoration, upscale pass: AMD FidelityFX Super Resolution 1.0 "EASU"
// (edge adaptive spatial upsampling). Reconstructs smooth diagonal edges instead of
// the blocky staircases of the MSU-1 video. Adapted to GLSL ES 3.00 from the
// libretro slang-shaders port (SPDX-License-Identifier: Unlicense; FSR is MIT, AMD).
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
in vec2 vUv;
out vec4 fragColor;

float rcpSafe(float x) { return 1.0 / max(x, 1.0 / 32768.0); }
float sat1(float x) { return clamp(x, 0.0, 1.0); }
ivec2 gMax;
vec4 tapRaw(ivec2 p) { return texelFetch(uTex, clamp(p, ivec2(0), gMax), 0); }
#define tap(p) tapRaw(sp + (p))

// One EASU tap: rotated/anisotropic approximated Lanczos kernel.
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = off.x * dir.x + off.y * dir.y;
    v.y = off.x * (-dir.y) + off.y * dir.x;
    v *= len2;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB; wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    gMax = ivec2(uSrcSize) - 1;
    vec2 pp = vUv * uSrcSize - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    // --- Fetch all 12 unique texels directly as RGB ---
    // The 12-tap kernel layout relative to 'fp':
    //    b c          (0,-1) (1,-1)
    //  e f g h   (-1,0) (0,0) (1,0) (2,0)
    //  i j k l   (-1,1) (0,1) (1,1) (2,1)
    //    n o          (0, 2) (1, 2)
    ivec2 sp = ivec2(fp);
    vec3 b = tap(ivec2( 0,-1)).rgb;
    vec3 c = tap(ivec2( 1,-1)).rgb;
    vec3 e = tap(ivec2(-1, 0)).rgb;
    vec3 f = tap(ivec2( 0, 0)).rgb;
    vec3 g = tap(ivec2( 1, 0)).rgb;
    vec3 h = tap(ivec2( 2, 0)).rgb;
    vec3 i = tap(ivec2(-1, 1)).rgb;
    vec3 j = tap(ivec2( 0, 1)).rgb;
    vec3 k = tap(ivec2( 1, 1)).rgb;
    vec3 l = tap(ivec2( 2, 1)).rgb;
    vec3 n = tap(ivec2( 0, 2)).rgb;
    vec3 o = tap(ivec2( 1, 2)).rgb;

    // --- Approximate luma (luma times 2, in 2 FMA/MAD) ---
    float bL = b.b * float(0.5) + (b.r * float(0.5) + b.g);
    float cL = c.b * float(0.5) + (c.r * float(0.5) + c.g);
    float eL = e.b * float(0.5) + (e.r * float(0.5) + e.g);
    float fL = f.b * float(0.5) + (f.r * float(0.5) + f.g);
    float gL = g.b * float(0.5) + (g.r * float(0.5) + g.g);
    float hL = h.b * float(0.5) + (h.r * float(0.5) + h.g);
    float iL = i.b * float(0.5) + (i.r * float(0.5) + i.g);
    float jL = j.b * float(0.5) + (j.r * float(0.5) + j.g);
    float kL = k.b * float(0.5) + (k.r * float(0.5) + k.g);
    float lL = l.b * float(0.5) + (l.r * float(0.5) + l.g);
    float nL = n.b * float(0.5) + (n.r * float(0.5) + n.g);
    float oL = o.b * float(0.5) + (o.r * float(0.5) + o.g);

    // --- Accumulate direction and length ---
    // Inlined FsrEasuSetF for each of the 4 bilinear quadrants.
    // Each quadrant computes gradient direction and edge length
    // from its 5-tap cross pattern centered on the quadrant's
    // nearest texel.
    //
    // Quadrant layout (bilinear weights):
    //  s=(1-x)(1-y)  t=x(1-y)
    //  u=(1-x)y      v=xy
    //
    // Cross pattern for each quadrant:
    //  s: center=f, left=e,  right=g, up=b, down=j
    //  t: center=g, left=f,  right=h, up=c, down=k
    //  u: center=j, left=i,  right=k, up=f, down=n
    //  v: center=k, left=j,  right=l, up=g, down=o

    vec2 dir = vec2(0.0);
    float len = float(0.0);

    // Quadrant s
    {
        float w = (float(1.0) - pp.x) * (float(1.0) - pp.y);
        float dc = gL - fL; float cb = fL - eL;
        float lenX = max(abs(dc), abs(cb));
        lenX = rcpSafe(lenX);
        float dirX = gL - eL;
        dir.x += dirX * w;
        lenX = sat1(abs(dirX) * lenX); lenX *= lenX; len += lenX * w;
        float ec = jL - fL; float ca = fL - bL;
        float lenY = max(abs(ec), abs(ca));
        lenY = rcpSafe(lenY);
        float dirY = jL - bL;
        dir.y += dirY * w;
        lenY = sat1(abs(dirY) * lenY); lenY *= lenY; len += lenY * w;
    }
    // Quadrant t
    {
        float w = pp.x * (float(1.0) - pp.y);
        float dc = hL - gL; float cb = gL - fL;
        float lenX = max(abs(dc), abs(cb));
        lenX = rcpSafe(lenX);
        float dirX = hL - fL;
        dir.x += dirX * w;
        lenX = sat1(abs(dirX) * lenX); lenX *= lenX; len += lenX * w;
        float ec = kL - gL; float ca = gL - cL;
        float lenY = max(abs(ec), abs(ca));
        lenY = rcpSafe(lenY);
        float dirY = kL - cL;
        dir.y += dirY * w;
        lenY = sat1(abs(dirY) * lenY); lenY *= lenY; len += lenY * w;
    }
    // Quadrant u
    {
        float w = (float(1.0) - pp.x) * pp.y;
        float dc = kL - jL; float cb = jL - iL;
        float lenX = max(abs(dc), abs(cb));
        lenX = rcpSafe(lenX);
        float dirX = kL - iL;
        dir.x += dirX * w;
        lenX = sat1(abs(dirX) * lenX); lenX *= lenX; len += lenX * w;
        float ec = nL - jL; float ca = jL - fL;
        float lenY = max(abs(ec), abs(ca));
        lenY = rcpSafe(lenY);
        float dirY = nL - fL;
        dir.y += dirY * w;
        lenY = sat1(abs(dirY) * lenY); lenY *= lenY; len += lenY * w;
    }
    // Quadrant v
    {
        float w = pp.x * pp.y;
        float dc = lL - kL; float cb = kL - jL;
        float lenX = max(abs(dc), abs(cb));
        lenX = rcpSafe(lenX);
        float dirX = lL - jL;
        dir.x += dirX * w;
        lenX = sat1(abs(dirX) * lenX); lenX *= lenX; len += lenX * w;
        float ec = oL - kL; float ca = kL - gL;
        float lenY = max(abs(ec), abs(ca));
        lenY = rcpSafe(lenY);
        float dirY = oL - gL;
        dir.y += dirY * w;
        lenY = sat1(abs(dirY) * lenY); lenY *= lenY; len += lenY * w;
    }

    // --- Normalize direction ---
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < float(1.0 / 32768.0);
    dirR = inversesqrt(dirR);
    dirR = zro ? float(1.0) : dirR;
    dir.x = zro ? float(1.0) : dir.x;
    dir *= vec2(dirR);

    // --- Shape length ---
    len = len * float(0.5);
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) * rcpSafe(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(float(1.0) + (stretch - float(1.0)) * len, float(1.0) + float(-0.5) * len);
    float lob = float(0.5) + float((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = rcpSafe(lob);

    // --- Min/max of 4 nearest (f, g, j, k) for de-ringing ---
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));

    // --- Accumulate 12 taps (inlined FsrEasuTapF) ---
    vec3 aC = vec3(0.0);
    float aW = float(0.0);

    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    // --- Normalize and de-ring ---
    vec3 pix = min(max4, max(min4, aC * vec3(rcpSafe(aW))));
    fragColor = vec4(pix, 1.0);
}
