#version 300 es
// HD restoration, pass 2: high quality Catmull-Rom (bicubic) upscale to the
// output resolution (sharper than bilinear, no blocky pixels).
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
in vec2 vUv;
out vec4 fragColor;

vec4 cubicW(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t,
                1.5 * t3 - 2.5 * t2 + 1.0,
                -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                0.5 * t3 - 0.5 * t2);
}

void main() {
    vec2 p = vUv * uSrcSize - 0.5;
    vec2 f = fract(p);
    vec2 base = (floor(p) + 0.5) / uSrcSize;
    vec2 px = 1.0 / uSrcSize;
    vec4 wx = cubicW(f.x), wy = cubicW(f.y);
    vec3 acc = vec3(0.0);
    for (int j = 0; j < 4; ++j) {
        vec3 row = vec3(0.0);
        for (int i = 0; i < 4; ++i) {
            row += texture(uTex, base + vec2(float(i - 1), float(j - 1)) * px).rgb * wx[i];
        }
        acc += row * wy[j];
    }
    fragColor = vec4(clamp(acc, 0.0, 1.0), 1.0);
}
