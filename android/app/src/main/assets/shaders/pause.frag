#version 300 es
// Pause backdrop: heavy blur of the game frame, darkened and desaturated, with a
// vignette. Drawn full screen on top of the normal picture with alpha = uParams.x
// (animated 0..1 when the pause menu opens).
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
uniform vec4 uParams;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec2 px = 1.0 / uSrcSize;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int y = -3; y <= 3; ++y) {
        for (int x = -3; x <= 3; ++x) {
            vec2 o = vec2(float(x), float(y)) * 1.6;
            float w = exp(-dot(o, o) / 18.0);
            acc += texture(uTex, vUv + o * px).rgb * w;
            wsum += w;
        }
    }
    vec3 c = acc / wsum;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 0.65) * 0.42;
    // purple night tint, like the launcher
    c = mix(c, c * vec3(0.85, 0.75, 1.15) + vec3(0.02, 0.0, 0.04), 0.6);
    vec2 v = vUv - 0.5;
    float vig = smoothstep(0.85, 0.25, length(v * vec2(1.0, 1.25)));
    c *= mix(0.55, 1.0, vig);
    fragColor = vec4(c, uParams.x);
}
