#version 300 es
// Full-screen quad. aPos in [-1,1]; uCrop = (u0, v0, u1, v1) region of the input to sample.
layout(location = 0) in vec2 aPos;
uniform vec4 uCrop;
out vec2 vUv;
void main() {
    vec2 t = aPos * 0.5 + 0.5;
    vUv = mix(uCrop.xy, uCrop.zw, t);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
