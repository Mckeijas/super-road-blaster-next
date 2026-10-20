#version 300 es
// HD restoration, pass 3 (output resolution):
// contrast-adaptive sharpening (CAS-style, halo free) + anime line darkening.
// uParams: x = sharpen (0..1), y = line darkening (0..1)
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
uniform vec4 uParams;
in vec2 vUv;
out vec4 fragColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 px = 1.0 / uSrcSize;
    vec3 a = texture(uTex, vUv + vec2(-px.x, -px.y)).rgb;
    vec3 b = texture(uTex, vUv + vec2(0.0, -px.y)).rgb;
    vec3 c = texture(uTex, vUv + vec2(px.x, -px.y)).rgb;
    vec3 d = texture(uTex, vUv + vec2(-px.x, 0.0)).rgb;
    vec3 e = texture(uTex, vUv).rgb;
    vec3 f = texture(uTex, vUv + vec2(px.x, 0.0)).rgb;
    vec3 g = texture(uTex, vUv + vec2(-px.x, px.y)).rgb;
    vec3 h = texture(uTex, vUv + vec2(0.0, px.y)).rgb;
    vec3 i = texture(uTex, vUv + vec2(px.x, px.y)).rgb;

    // CAS
    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mx = max(max(max(d, e), max(f, b)), h);
    vec3 mn2 = min(mn, min(min(a, c), min(g, i)));
    vec3 mx2 = max(mx, max(max(a, c), max(g, i)));
    mn += mn2; mx += mx2;
    vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, 1e-4), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, clamp(uParams.x, 0.0, 1.0));
    vec3 w = amp * peak;
    vec3 sharp = clamp((b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w), 0.0, 1.0);

    // line darkening: Sobel on luma, darken only the dark side of an edge
    float la = luma(a), lb = luma(b), lc = luma(c), ld = luma(d), le = luma(e);
    float lf = luma(f), lg = luma(g), lh = luma(h), li = luma(i);
    float gx = (lc + 2.0 * lf + li) - (la + 2.0 * ld + lg);
    float gy = (lg + 2.0 * lh + li) - (la + 2.0 * lb + lc);
    float edge = clamp(length(vec2(gx, gy)) * 0.9 - 0.12, 0.0, 1.0);
    float localMean = (la + lb + lc + ld + lf + lg + lh + li) / 8.0;
    float darkSide = step(le, localMean);
    float ink = 1.0 - uParams.y * 0.35 * edge * darkSide;
    fragColor = vec4(sharp * ink, 1.0);
}
