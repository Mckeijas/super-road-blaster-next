#version 300 es
// "CRT": scanlines, slot mask and a soft glow, tuned for a 1080p handheld.
// uParams.x = scanline strength, y = colour enhancement, z = mask strength
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSrcSize;
uniform vec2 uOutSize;
uniform vec4 uParams;
uniform vec4 uCrop;
in vec2 vUv;
out vec4 fragColor;

vec3 toLin(vec3 c) { return pow(c, vec3(2.4)); }
vec3 toGam(vec3 c) { return pow(c, vec3(1.0 / 2.2)); }

void main() {
    vec2 p = vUv * uSrcSize;
    vec2 i = floor(p) + 0.5;
    vec2 f = p - i;
    // horizontal: soft bilinear; vertical: gaussian beam around the line centre
    vec3 c0 = toLin(texture(uTex, vec2(p.x, i.y) / uSrcSize).rgb);
    vec3 c1 = toLin(texture(uTex, vec2(p.x, i.y + sign(f.y)) / uSrcSize).rgb);
    float d0 = abs(f.y), d1 = 1.0 - abs(f.y);
    float s = mix(0.0, 1.0, uParams.x);
    float w0 = exp(-d0 * d0 * mix(1.5, 9.0, s) / (0.6 + 0.4 * max(max(c0.r, c0.g), c0.b)));
    float w1 = exp(-d1 * d1 * mix(1.5, 9.0, s) / (0.6 + 0.4 * max(max(c1.r, c1.g), c1.b)));
    vec3 col = c0 * w0 + c1 * w1;
    // slot mask in output pixels
    float m = mod(gl_FragCoord.x, 3.0);
    vec3 mask = vec3(m < 1.0 ? 1.0 : 0.75, (m >= 1.0 && m < 2.0) ? 1.0 : 0.75, m >= 2.0 ? 1.0 : 0.75);
    col *= mix(vec3(1.0), mask, uParams.z);
    col *= 1.0 + 0.45 * s; // compensate brightness
    vec3 g = toGam(clamp(col, 0.0, 1.0));
    float l = dot(g, vec3(0.2126, 0.7152, 0.0722));
    g = mix(vec3(l), g, 1.0 + 0.2 * uParams.y);
    fragColor = vec4(clamp(g, 0.0, 1.0), 1.0);
}
