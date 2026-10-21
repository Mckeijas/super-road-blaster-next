package com.srbport.roadblaster;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws the HD add-on picture (GL thread):
 *   1. the HD film frame (external texture from HdVideo);
 *   2. when the iOS dashboard is on screen: the iPad buttons painted out, the dashboard screen
 *      showing the player's real score and lives, and the gear lever up / middle / down
 *      following accelerate / brake;
 *   3. when there is no dashboard: the SNES HUD (arrows, turbo/brake icons, points), drawn by
 *      the emulator on its own (video layers hidden, backdrop colour = transparent).
 */
final class HdCompositor {
    private static final String TAG = "SRBHd";
    private final HdPack pack;
    private final HdVideo video;
    private final float[] texM = new float[16];
    private final float[] off = new float[2];
    private int oesTex, progOes, progSprite, progDim;
    private int texHelp, texPause, texScreen, texUp, texMid, texDown;
    private int texIndClean, texIndLeft, texIndRight, texIndBrake, texIndAccel;
    private float floatAlpha;   // prompt screen shown on its own when the dashboard is not on screen
    private int lcdScore = Integer.MIN_VALUE, lcdLives = Integer.MIN_VALUE;
    private Bitmap screenBg;
    private final int[] stats = new int[2];
    private int score = -1, lives = -1;
    private FloatBuffer quad;
    private boolean ok;

    HdCompositor(HdPack pack, HdVideo video) {
        this.pack = pack;
        this.video = video;
    }

    // ------------------------------------------------------------------ GL setup
    void onSurfaceCreated(FloatBuffer quad) {
        this.quad = quad;
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        video.attach(oesTex);
        progOes = program(
                "attribute vec2 aPos; uniform mat4 uTexM; varying vec2 vUv;\n" +
                "void main(){ vec2 t = aPos*0.5+0.5; vUv = (uTexM*vec4(t,0.0,1.0)).xy; gl_Position = vec4(aPos,0.0,1.0); }",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n" +
                "uniform samplerExternalOES uTex; varying vec2 vUv;\n" +
                "void main(){ gl_FragColor = texture2D(uTex, vUv); }");
        progSprite = program(
                "#version 300 es\nlayout(location=0) in vec2 aPos; uniform vec4 uRect; uniform vec4 uUv; out vec2 vUv;\n" +
                "void main(){ vec2 t = aPos*0.5+0.5; gl_Position = vec4(mix(uRect.xy, uRect.zw, t), 0.0, 1.0);\n" +
                "  vUv = vec2(mix(uUv.x, uUv.z, t.x), mix(uUv.w, uUv.y, t.y)); }",
                "#version 300 es\nprecision mediump float; uniform sampler2D uTex; uniform float uAlpha; uniform vec4 uKey;\n" +
                "in vec2 vUv; out vec4 o;\n" +
                "void main(){ vec4 c = texture(uTex, vUv);\n" +
                "  if (uKey.a > 0.5 && all(lessThan(abs(c.rgb - uKey.rgb), vec3(0.02)))) discard;\n" +
                "  o = c * uAlpha; }");
        progDim = program(
                "#version 300 es\nlayout(location=0) in vec2 aPos; void main(){ gl_Position = vec4(aPos,0.0,1.0); }",
                "#version 300 es\nprecision mediump float; uniform float uA; out vec4 o; void main(){ o = vec4(0.0,0.0,0.0,uA); }");
        texHelp = upload(pack.bitmap("patch_help.png"));
        texPause = upload(pack.bitmap("patch_pause.png"));
        screenBg = pack.bitmap("patch_screen.png");
        texScreen = 0;
        lcdScore = lcdLives = Integer.MIN_VALUE;
        texUp = upload(pack.bitmap("lever_up.png"));
        texMid = upload(pack.bitmap("lever_mid.png"));
        texDown = upload(pack.bitmap("lever_down.png"));
        if (pack.hasIndicators) {
            texIndClean = upload(pack.bitmap("ind_clean.png"));
            texIndLeft = upload(pack.bitmap("ind_left.png"));
            texIndRight = upload(pack.bitmap("ind_right.png"));
            texIndBrake = upload(pack.bitmap("ind_brake.png"));
            texIndAccel = upload(pack.bitmap("ind_accel.png"));
        }
        ok = progOes != 0 && progSprite != 0;
    }

    /** Sets the film time for the current game picture and reports whether HD can be shown. */
    boolean prepare(int track, int audioPos) {
        double t = pack.filmTime(track, audioPos);
        if (Double.isNaN(t)) {
            video.setActive(false);
            return false;
        }
        video.setTarget(t);
        video.setActive(true);
        return ok && video.synced();
    }

    void setActive(boolean a) { video.setActive(a); }

    // ------------------------------------------------------------------ drawing
    /**
     * vp = viewport of the 4:3 picture on screen. snesTex = the emulator picture (HUD only),
     * key = its transparent colour (RGB565). buttons = what the game sees (pad + automatic QTE).
     */
    void draw(int[] vp, int track, int audioPos, int snesTex, int snesW, int snesH, int key, int buttons, float dim) {
        double t = pack.filmTime(track, audioPos);
        video.update(texM);
        GLES30.glViewport(vp[0], vp[1], vp[2], vp[3]);
        // 1. film
        GLES30.glUseProgram(progOes);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progOes, "uTexM"), 1, false, texM, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progOes, "uTex"), 0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        // the game's own prompts (the film's green screen follows the iOS timing, which is
        // not always the game's): left / right / brake / accelerate
        int prompts = pack.hasIndicators ? NativeBridge.nativePromptsAt(track, audioPos) : 0;
        if (!Double.isNaN(t) && pack.dashboardAt(t, off)) {
            // 2. iOS dashboard, dressed for this game
            float ox = off[0], oy = off[1];
            floatAlpha = 0f;
            sprite(texHelp, pack.help, ox, oy, 0, 0, 1, 1, null);
            sprite(texPause, pack.pause, ox, oy, 0, 0, 1, 1, null);
            if (pack.hasIndicators) {
                sprite(texIndClean, pack.indicators, ox, oy, 0, 0, 1, 1, null);
                drawPrompts(prompts, ox, oy, 1f);
            }
            updateStats();
            if (score >= 0) {
                refreshScreen();
                sprite(texScreen, pack.screen, ox, oy, 0, 0, 1, 1, null);
            }
            int lever = (buttons & NativeBridge.BTN_A) != 0 ? texUp : (buttons & NativeBridge.BTN_B) != 0 ? texDown : texMid;
            sprite(lever, pack.lever, ox, oy, 0, 0, 1, 1, null);
        } else if (pack.hasIndicators) {
            // no dashboard in this shot: the prompt screen appears on its own while needed
            floatAlpha = Math.max(0f, Math.min(1f, floatAlpha + (prompts != 0 ? 0.25f : -0.12f)));
            if (floatAlpha > 0.01f) {
                sprite(texIndClean, pack.indicators, 0, 0, 0, 0, 1, 1, null, floatAlpha * 0.92f);
                drawPrompts(prompts, 0, 0, floatAlpha);
            }
        } else if (snesTex != 0 && snesH > 0) {
            // 3. SNES HUD on its own, mapped onto the film (rows 8..198 = the video area)
            float r = ((key >> 11) & 31) / 31f, g = ((key >> 5) & 63) / 63f, b = (key & 31) / 31f;
            int[] rect = {Math.round(pack.ox), Math.round(pack.oy), Math.round(snesW * pack.sx), Math.round(191 * pack.sy)};
            sprite(snesTex, rect, 0, 0, 0, 8f / snesH, 1, 199f / snesH, new float[]{r, g, b, 1});
        }
        if (dim > 0.001f) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glUseProgram(progDim);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(progDim, "uA"), dim);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawPrompts(int prompts, float ox, float oy, float a) {
        if (prompts == 0) return;
        // blink like the dashboard does
        float blink = (android.os.SystemClock.uptimeMillis() % 320) < 210 ? 1f : 0.45f;
        a *= blink;
        if ((prompts & 1) != 0) sprite(texIndLeft, pack.indicators, ox, oy, 0, 0, 1, 1, null, a);
        if ((prompts & 2) != 0) sprite(texIndRight, pack.indicators, ox, oy, 0, 0, 1, 1, null, a);
        if ((prompts & 4) != 0) sprite(texIndBrake, pack.indicators, ox, oy, 0, 0, 1, 1, null, a);
        if ((prompts & 8) != 0) sprite(texIndAccel, pack.indicators, ox, oy, 0, 0, 1, 1, null, a);
    }

    private void sprite(int tex, int[] r, float dx, float dy, float u0, float v0, float u1, float v1, float[] key) {
        sprite(tex, r, dx, dy, u0, v0, u1, v1, key, 1f);
    }

    /** Draws a texture into a rectangle given in film pixels (x, y, w, h), moved by (dx, dy). */
    private void sprite(int tex, int[] r, float dx, float dy, float u0, float v0, float u1, float v1, float[] key, float alpha) {
        if (tex == 0 || r[2] <= 0) return;
        float x0 = (r[0] + dx) / HdPack.FILM_W * 2f - 1f, x1 = (r[0] + dx + r[2]) / HdPack.FILM_W * 2f - 1f;
        float yTop = 1f - (r[1] + dy) / HdPack.FILM_H * 2f, yBot = 1f - (r[1] + dy + r[3]) / HdPack.FILM_H * 2f;
        GLES30.glUseProgram(progSprite);
        GLES30.glUniform4f(GLES30.glGetUniformLocation(progSprite, "uRect"), x0, yBot, x1, yTop);
        GLES30.glUniform4f(GLES30.glGetUniformLocation(progSprite, "uUv"), u0, v0, u1, v1);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(progSprite, "uAlpha"), alpha);
        if (key != null) GLES30.glUniform4f(GLES30.glGetUniformLocation(progSprite, "uKey"), key[0], key[1], key[2], 1f);
        else GLES30.glUniform4f(GLES30.glGetUniformLocation(progSprite, "uKey"), 0, 0, 0, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        int filter = key != null ? GLES30.GL_NEAREST : GLES30.GL_LINEAR;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progSprite, "uTex"), 0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ------------------------------------------------------------------ dashboard screen
    private void updateStats() {
        if (NativeBridge.nativePlayerStats(stats)) {
            score = stats[0];
            lives = stats[1];
        }
    }

    private void refreshScreen() {
        if (score == lcdScore && lives == lcdLives && texScreen != 0) return;
        lcdScore = score;
        lcdLives = lives;
        Bitmap b = drawScreen(screenBg, score, lives);
        if (b == null) return;
        if (texScreen == 0) texScreen = upload(b);
        else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texScreen);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        }
        b.recycle();
    }

    /** The dashboard's red digital screen: a person and "x lives" on the left, score on the right. */
    static Bitmap drawScreen(Bitmap bg, int score, int lives) {
        if (bg == null) return null;
        Bitmap b = bg.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(b);
        int w = b.getWidth(), h = b.getHeight();
        Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        on.setColor(Color.rgb(255, 30, 36));
        on.setShadowLayer(h * 0.06f, 0, 0, Color.argb(200, 255, 20, 20));
        Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
        ghost.setColor(Color.argb(28, 255, 40, 40));
        // lives: little person + "x" + digit
        float ph = h * 0.46f, px = w * 0.055f, py = (h - ph) / 2f + h * 0.04f;
        c.drawCircle(px + ph * 0.18f, py + ph * 0.12f, ph * 0.12f, on);
        c.drawRoundRect(new RectF(px, py + ph * 0.27f, px + ph * 0.36f, py + ph * 0.72f), ph * 0.06f, ph * 0.06f, on);
        c.drawRect(px + ph * 0.06f, py + ph * 0.68f, px + ph * 0.15f, py + ph, on);
        c.drawRect(px + ph * 0.21f, py + ph * 0.68f, px + ph * 0.30f, py + ph, on);
        Paint small = new Paint(on);
        small.setStrokeWidth(h * 0.035f);
        float xx = px + ph * 0.55f, xy = py + ph * 0.55f, xs = ph * 0.13f;
        c.drawLine(xx - xs, xy - xs, xx + xs, xy + xs, small);
        c.drawLine(xx - xs, xy + xs, xx + xs, xy - xs, small);
        sevenSeg(c, xx + ph * 0.3f, py + ph * 0.25f, ph * 0.38f, ph * 0.72f, Math.max(0, Math.min(9, lives)), on, ghost);
        // score: shown like the game, x1000, with thousands separators (e.g. 724,000)
        String s = String.format(java.util.Locale.ROOT, "%,d", Math.max(0, score) * 1000L);
        float dh = h * 0.56f, dw = dh * 0.5f, gap = dh * 0.12f, top = (h - dh) / 2f;
        float x = w - w * 0.05f;
        int digits = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == ',') {
                x -= dw * 0.35f;
                c.drawCircle(x + dw * 0.12f, top + dh * 0.98f, dh * 0.05f, on);
                continue;
            }
            x -= dw;
            sevenSeg(c, x, top, dw, dh, ch - '0', on, ghost);
            x -= gap;
            digits++;
        }
        for (int i = digits; i < 7; i++) {           // unlit digits, like a real LCD
            x -= dw;
            sevenSeg(c, x, top, dw, dh, -1, on, ghost);
            x -= gap;
        }
        return b;
    }

    private static final int[] SEG = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    /** Slanted seven-segment digit (-1 = only the unlit segments). */
    private static void sevenSeg(Canvas c, float x, float y, float w, float h, int d, Paint on, Paint off) {
        int bits = d >= 0 && d <= 9 ? SEG[d] : 0;
        float t = w * 0.17f, sk = w * 0.18f, hh = h / 2f;
        float[][] s = {   // segment boxes a..g: x0, y0, x1, y1 (vertical ones get the slant)
                {t, 0, w - t, t}, {w - t, t * 0.6f, w, hh - t * 0.3f}, {w - t, hh + t * 0.3f, w, h - t * 0.6f},
                {t, h - t, w - t, h}, {0, hh + t * 0.3f, t, h - t * 0.6f}, {0, t * 0.6f, t, hh - t * 0.3f},
                {t, hh - t / 2f, w - t, hh + t / 2f}};
        for (int i = 0; i < 7; i++) {
            float[] r = s[i];
            Path p = new Path();
            float s0 = sk * (1f - r[1] / h), s1 = sk * (1f - r[3] / h);   // slant: top shifted right
            boolean horizontal = (i == 0 || i == 3 || i == 6);
            float in = horizontal ? (r[3] - r[1]) / 2f : (r[2] - r[0]) / 2f;
            if (horizontal) {
                float m = (r[1] + r[3]) / 2f, sm = sk * (1f - m / h);
                p.moveTo(x + r[0] + sm, y + m);
                p.lineTo(x + r[0] + in + s0, y + r[1]);
                p.lineTo(x + r[2] - in + s0, y + r[1]);
                p.lineTo(x + r[2] + sm, y + m);
                p.lineTo(x + r[2] - in + s1, y + r[3]);
                p.lineTo(x + r[0] + in + s1, y + r[3]);
            } else {
                float m = (r[0] + r[2]) / 2f;
                p.moveTo(x + m + s0, y + r[1]);
                p.lineTo(x + r[2] + sk * (1f - (r[1] + in) / h), y + r[1] + in);
                p.lineTo(x + r[2] + sk * (1f - (r[3] - in) / h), y + r[3] - in);
                p.lineTo(x + m + s1, y + r[3]);
                p.lineTo(x + r[0] + sk * (1f - (r[3] - in) / h), y + r[3] - in);
                p.lineTo(x + r[0] + sk * (1f - (r[1] + in) / h), y + r[1] + in);
            }
            p.close();
            c.drawPath(p, (bits >> i & 1) != 0 ? on : off);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static int upload(Bitmap b) {
        if (b == null) return 0;
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "shader: " + GLES30.glGetShaderInfoLog(s));
            GLES30.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int program(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs), f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glBindAttribLocation(p, 0, "aPos");
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }

    static FloatBuffer newQuad() {
        FloatBuffer q = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        q.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        return q;
    }
}
