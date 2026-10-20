#version 300 es
// "Original": pixel-perfect look without shimmering (sharp bilinear).
// uParams.y = colour enhancement (0..1), uParams.x = 1 for plain bilinear ("Suave")
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
uniform vec2 uOutSize;
uniform vec4 uParams;
uniform vec4 uCrop;
in vec2 vUv;
out vec4 fragColor;

vec3 grade(vec3 c, float k) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 sat = mix(vec3(l), c, 1.0 + 0.22 * k);
    vec3 con = (sat - 0.5) * (1.0 + 0.10 * k) + 0.5;
    con = pow(max(con, 0.0), vec3(1.0 - 0.06 * k));
    return clamp(con, 0.0, 1.0);
}

void main() {
    vec2 texel = vUv * uSrcSize;
    vec2 cropPx = abs(uCrop.zw - uCrop.xy) * uSrcSize;
    vec2 scale = max(uOutSize / cropPx, vec2(1.0));
    vec2 region = 0.5 - 0.5 / scale;
    vec2 f = fract(texel) - 0.5;
    vec2 off = (clamp(f, -region, region) - f) * (1.0 - uParams.x);
    vec3 c = texture(uTex, (texel + off) / uSrcSize).rgb;
    fragColor = vec4(grade(c, uParams.y), 1.0);
}
