#version 300 es
// HD restoration, pass 1 (source resolution):
// edge-preserving bilateral filter that removes the MSU-1 encoder's tile/dither
// noise while keeping the clean cel-animation edges, plus optional colour grading.
// uParams: x = strength (0..1), y = colour enhancement (0..1), z = range sigma, w = unused
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
uniform vec4 uParams;
in vec2 vUv;
out vec4 fragColor;

vec3 grade(vec3 c, float k) {
    // gentle "remaster" grade: lift muddy blacks, add contrast and saturation,
    // keep skin/anime palette natural.
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 sat = mix(vec3(l), c, 1.0 + 0.22 * k);
    vec3 con = (sat - 0.5) * (1.0 + 0.10 * k) + 0.5;
    con = pow(max(con, 0.0), vec3(1.0 - 0.06 * k));   // slight gamma lift in mids
    return clamp(con, 0.0, 1.0);
}

void main() {
    vec2 px = 1.0 / uSrcSize;
    vec3 c0 = texture(uTex, vUv).rgb;
    float strength = uParams.x;
    float sigR = max(uParams.z, 0.01);
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            vec2 o = vec2(float(x), float(y));
            vec3 c = texture(uTex, vUv + o * px).rgb;
            vec3 d = c - c0;
            float ws = exp(-dot(o, o) / (2.0 * 1.4 * 1.4));
            float wr = exp(-dot(d, d) / (2.0 * sigR * sigR));
            float w = ws * wr;
            acc += c * w;
            wsum += w;
        }
    }
    vec3 smoothc = acc / wsum;
    vec3 outc = mix(c0, smoothc, strength);
    fragColor = vec4(grade(outc, uParams.y), 1.0);
}
