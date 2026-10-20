package com.srbport.roadblaster;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 3.0 video output. Runs the same multi-pass pipeline as the launcher
 * preview (assets/shaders/presets.json): restoration, FSR-EASU upscale, CAS
 * sharpening, CRT, etc.
 */
public final class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "SRBRenderer";
    private static final String[] SHADERS = {"restore", "catmull", "easu", "refine", "sharp", "crt", "pause"};

    private final AssetManager assets;
    private final ByteBuffer frame = ByteBuffer.allocateDirect(512 * 480 * 2).order(ByteOrder.nativeOrder());
    private final int[] info = new int[2];
    private int texW, texH;
    private int lastW, lastH; // size of the frame kept in `frame` (survives GL context loss)
    private int srcTex;
    private final Map<String, Integer> programs = new HashMap<>();
    private final int[] fboTex = new int[2], fbo = new int[2], fboW = new int[2], fboH = new int[2];
    private FloatBuffer quad;
    private int viewW = 1, viewH = 1;
    private JSONObject presets;

    private volatile String filter = "hd";
    private volatile float color = 1f;
    private volatile String aspect = "43";
    private boolean hasFrame;

    // Pause backdrop animation (blur + darken over the game picture)
    private volatile boolean pauseTarget;
    private volatile long pauseChanged;
    private float pauseFrom;
    private float pauseAmount;

    /** Animates the blurred pause backdrop in or out (~220 ms). */
    public void setPaused(boolean paused) {
        if (paused == pauseTarget) return;
        pauseFrom = pauseAmount;
        pauseTarget = paused;
        pauseChanged = System.nanoTime();
    }

    /** True while the pause backdrop is still animating. */
    public boolean isAnimating() {
        float target = pauseTarget ? 1f : 0f;
        return Math.abs(pauseAmount - target) > 0.001f;
    }

    public GameRenderer(AssetManager am) { assets = am; }

    public void configure(String filter, boolean enhancedColor, String aspect) {
        this.filter = filter;
        this.color = enhancedColor ? 1f : 0f;
        this.aspect = aspect;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        programs.clear();
        String vs = GameData.readAssetText(assets, "shaders/quad.vert");
        for (String s : SHADERS) {
            int p = buildProgram(vs, GameData.readAssetText(assets, "shaders/" + s + ".frag"), s);
            if (p != 0) programs.put(s, p);
        }
        try {
            presets = new JSONObject(GameData.readAssetText(assets, "shaders/presets.json")).getJSONObject("presets");
        } catch (Exception e) {
            Log.e(TAG, "presets.json", e);
            presets = new JSONObject();
        }
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        srcTex = t[0];
        texW = texH = 0;
        GLES30.glGenTextures(2, fboTex, 0);
        GLES30.glGenFramebuffers(2, fbo, 0);
        fboW[0] = fboW[1] = fboH[0] = fboH[1] = 0;
        hasFrame = false;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW = w;
        viewH = h;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (NativeBridge.nativeCopyFrame(frame, info) == 1) {
            lastW = info[0];
            lastH = info[1];
            upload(lastW, lastH);
            hasFrame = true;
        } else if (!hasFrame && lastW > 0) {
            upload(lastW, lastH); // new GL context: re-upload the last frame we kept
            hasFrame = true;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, viewW, viewH);
        GLES30.glClearColor(0, 0, 0, 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (!hasFrame) return;
        renderPipeline();
        renderPauseBackdrop();
    }

    private void renderPauseBackdrop() {
        float target = pauseTarget ? 1f : 0f;
        float t = Math.min(1f, (System.nanoTime() - pauseChanged) / 220_000_000f);
        float e = 1f - (1f - t) * (1f - t) * (1f - t); // ease-out cubic
        pauseAmount = pauseFrom + (target - pauseFrom) * e;
        if (pauseAmount <= 0.001f) return;
        Integer prog = programs.get("pause");
        if (prog == null) return;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, viewW, viewH);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(prog);
        float[] c = crop();
        // fill the whole screen with the (cropped) frame, raw texture orientation
        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uCrop"), c[0], c[3], c[2], c[1]);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uSrcSize"), texW, texH);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uOutSize"), viewW, viewH);
        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uParams"), pauseAmount * 0.96f, 0, 0, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void upload(int w, int h) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2);
        frame.position(0);
        if (w != texW || h != texH) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB, w, h, 0, GLES30.GL_RGB,
                    GLES30.GL_UNSIGNED_SHORT_5_6_5, frame);
            texW = w;
            texH = h;
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGB,
                    GLES30.GL_UNSIGNED_SHORT_5_6_5, frame);
        }
    }

    /** Visible game area inside the PAL frame (rows 8..220 of 239). */
    private float[] crop() {
        if (texH >= 230) return new float[]{0f, 8f / texH, 1f, 221f / texH};
        return new float[]{0f, 0f, 1f, 1f};
    }

    /** Viewport (x, y, w, h) on screen for the chosen aspect mode. */
    private int[] viewport(float[] c) {
        if ("fill".equals(aspect)) return new int[]{0, 0, viewW, viewH};
        float cropW = (c[2] - c[0]) * texW, cropH = (c[3] - c[1]) * texH;
        float ar = cropW * (8f / 7f) / cropH; // SNES pixel aspect ratio
        int w, h;
        if ("zoom".equals(aspect)) { // fill width, crop top/bottom
            w = viewW;
            h = Math.round(viewW / ar);
        } else if (viewW / (float) viewH > ar) {
            h = viewH;
            w = Math.round(viewH * ar);
        } else {
            w = viewW;
            h = Math.round(viewW / ar);
        }
        return new int[]{(viewW - w) / 2, (viewH - h) / 2, w, h};
    }

    private void renderPipeline() {
        JSONObject preset = presets.optJSONObject(filter);
        if (preset == null) preset = presets.optJSONObject("original");
        if (preset == null) return;
        JSONArray passes = preset.optJSONArray("passes");
        if (passes == null) return;
        float[] c = crop();
        int[] vp = viewport(c);
        int inTex = srcTex, inW = texW, inH = texH;
        boolean raw = true;
        for (int idx = 0; idx < passes.length(); idx++) {
            JSONObject pass = passes.optJSONObject(idx);
            boolean last = idx == passes.length() - 1;
            boolean srcSize = "src".equals(pass.optString("size"));
            int outW = srcSize ? inW : vp[2];
            int outH = srcSize ? inH : vp[3];
            Integer prog = programs.get(pass.optString("shader"));
            if (prog == null) prog = programs.get("sharp");
            if (prog == null) return;
            if (last) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(vp[0], vp[1], vp[2], vp[3]);
            } else {
                int slot = idx % 2;
                ensureFbo(slot, outW, outH);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[slot]);
                GLES30.glViewport(0, 0, outW, outH);
            }
            GLES30.glUseProgram(prog);
            float[] cc = pass.optBoolean("crop") ? c : new float[]{0, 0, 1, 1};
            // raw upload: row 0 at the top; framebuffers: row 0 at the bottom
            float[] uc = raw ? new float[]{cc[0], cc[3], cc[2], cc[1]}
                    : new float[]{cc[0], 1 - cc[3], cc[2], 1 - cc[1]};
            GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uCrop"), uc[0], uc[1], uc[2], uc[3]);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uSrcSize"), inW, inH);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uOutSize"), outW, outH);
            float[] prm = new float[4];
            JSONArray pa = pass.optJSONArray("params");
            for (int i = 0; pa != null && i < 4 && i < pa.length(); i++) prm[i] = (float) pa.optDouble(i, 0);
            if (pass.has("colorParam")) prm[pass.optInt("colorParam")] = color;
            GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uParams"), prm[0], prm[1], prm[2], prm[3]);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex);
            String sh = pass.optString("shader");
            int filt = ("sharp".equals(sh) || "crt".equals(sh) || "refine".equals(sh)) ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filt);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filt);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            if (!last) {
                int slot = idx % 2;
                inTex = fboTex[slot];
                inW = outW;
                inH = outH;
                raw = false;
            }
        }
    }

    private void ensureFbo(int slot, int w, int h) {
        if (fboW[slot] == w && fboH[slot] == h) return;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[slot]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[slot]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboTex[slot], 0);
        fboW[slot] = w;
        fboH[slot] = h;
    }

    private static int compile(int type, String src, String name) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, name + ": " + GLES30.glGetShaderInfoLog(s));
            GLES30.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int buildProgram(String vs, String fs, String name) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs, name + ".vert");
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs, name);
        if (v == 0 || f == 0) return 0;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glBindAttribLocation(p, 0, "aPos");
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, name + " link: " + GLES30.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }
}
