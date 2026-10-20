// srbfx.js - WebGL2 implementation of the in-game video pipeline (same GLSL and
// presets as GameRenderer.java). Used by the launcher's live preview.
(function (global) {
  'use strict';
  async function loadText(path) {
    if (global.Android && Android.readAsset) return Android.readAsset(path);
    const r = await fetch('../' + path);
    return r.text();
  }
  // Visible game area inside the PAL frame (rows 8..220 of 239).
  function cropFor(w, h) {
    if (h >= 230) return [0, 8 / h, 1, 221 / h];
    return [0, 0, 1, 1];
  }
  class SrbFx {
    constructor(canvas) {
      this.canvas = canvas;
      const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: true, antialias: false });
      if (!gl) throw new Error('WebGL2 not available');
      this.gl = gl;
      this.programs = {};
      this.fbos = [];
      const vbo = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.src = gl.createTexture();
    }
    async init() {
      this.presets = JSON.parse(await loadText('shaders/presets.json')).presets;
      this.vert = await loadText('shaders/quad.vert');
      for (const name of ['restore', 'catmull', 'easu', 'refine', 'sharp', 'crt']) {
        this.programs[name] = this._program(this.vert, await loadText('shaders/' + name + '.frag'), name);
      }
    }
    _program(vs, fs, name) {
      const gl = this.gl;
      const mk = (type, src) => {
        const s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(name + ': ' + gl.getShaderInfoLog(s));
        return s;
      };
      const p = gl.createProgram();
      gl.attachShader(p, mk(gl.VERTEX_SHADER, vs));
      gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs));
      gl.bindAttribLocation(p, 0, 'aPos');
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(name + ': ' + gl.getProgramInfoLog(p));
      return p;
    }
    setImage(img) {
      const gl = this.gl;
      gl.bindTexture(gl.TEXTURE_2D, this.src);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      this.srcW = img.naturalWidth || img.width;
      this.srcH = img.naturalHeight || img.height;
    }
    _fbo(i, w, h) {
      const gl = this.gl;
      let f = this.fbos[i];
      if (!f) {
        f = this.fbos[i] = { tex: gl.createTexture(), fb: gl.createFramebuffer(), w: 0, h: 0 };
      }
      if (f.w !== w || f.h !== h) {
        gl.bindTexture(gl.TEXTURE_2D, f.tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.bindFramebuffer(gl.FRAMEBUFFER, f.fb);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, f.tex, 0);
        f.w = w; f.h = h;
      }
      return f;
    }
    // Renders `preset` into a viewport (x,y,w,h) of the canvas.
    render(presetId, color, vp, scissor) {
      const gl = this.gl;
      const preset = this.presets[presetId];
      vp = vp || [0, 0, this.canvas.width, this.canvas.height];
      const crop = cropFor(this.srcW, this.srcH);
      let inTex = this.src, inW = this.srcW, inH = this.srcH, raw = true;
      preset.passes.forEach((pass, idx) => {
        const last = idx === preset.passes.length - 1;
        const outW = pass.size === 'src' ? inW : vp[2];
        const outH = pass.size === 'src' ? inH : vp[3];
        let target = null;
        if (!last) target = this._fbo(idx % 2, outW, outH);
        gl.bindFramebuffer(gl.FRAMEBUFFER, target ? target.fb : null);
        if (target) gl.viewport(0, 0, outW, outH); else gl.viewport(vp[0], vp[1], vp[2], vp[3]);
        if (!target && scissor) { gl.enable(gl.SCISSOR_TEST); gl.scissor(scissor[0], scissor[1], scissor[2], scissor[3]); }
        else gl.disable(gl.SCISSOR_TEST);
        const p = this.programs[pass.shader];
        gl.useProgram(p);
        const c = pass.crop ? crop : [0, 0, 1, 1];
        // raw upload has row 0 at the top; framebuffers have row 0 at the bottom
        const uc = raw ? [c[0], c[3], c[2], c[1]] : [c[0], 1 - c[3], c[2], 1 - c[1]];
        gl.uniform4f(gl.getUniformLocation(p, 'uCrop'), uc[0], uc[1], uc[2], uc[3]);
        gl.uniform2f(gl.getUniformLocation(p, 'uSrcSize'), inW, inH);
        gl.uniform2f(gl.getUniformLocation(p, 'uOutSize'), outW, outH);
        const prm = pass.params.slice();
        if (pass.colorParam !== undefined) prm[pass.colorParam] = color;
        gl.uniform4f(gl.getUniformLocation(p, 'uParams'), prm[0], prm[1], prm[2], prm[3]);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, inTex);
        const filt = pass.shader === 'sharp' || pass.shader === 'crt' || pass.shader === 'refine' ? gl.LINEAR : gl.NEAREST;
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filt);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filt);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.uniform1i(gl.getUniformLocation(p, 'uTex'), 0);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        gl.disable(gl.SCISSOR_TEST);
        if (target) { inTex = target.tex; inW = outW; inH = outH; raw = false; }
      });
    }
  }
  global.SrbFx = SrbFx;
})(window);
