package com.srbport.roadblaster;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;

/**
 * The optional HD video add-on (built with tools/hdpack/make_hdpack.py from the player's own
 * copy of the film and installed from the launcher): the film itself, where every chapter
 * starts in it, and the data to dress the iOS dashboard (when it is on screen, where it is,
 * clean patches for its buttons and screen, and the gear lever in its three positions).
 */
public final class HdPack {
    public static final int FILM_W = 1440, FILM_H = 1080;

    public final File video;
    public final double[] chapterStart = new double[GameData.TRACKS];   // NaN = not in the film
    public final byte[] mask = new byte[GameData.TRACKS];
    // dashboard track
    private double t0;
    private int fps = 60;
    private byte[] on = new byte[0];
    private short[] dx = new short[0], dy = new short[0];
    private float unit = 0.25f;
    // dashboard decoration (film pixels: x, y, w, h)
    public final int[] help = new int[4], pause = new int[4], screen = new int[4], lever = new int[4];
    // green indicator screen (format 2): clean background + prompt images, same rectangle
    public final int[] indicators = new int[4];
    public boolean hasIndicators;
    // seconds between keyframes of hd.mp4 (0.5 in packs from 1.7.9 on; older packs: up to 5 s)
    public double keyframeInterval = 5.0;
    // SNES picture -> film pixels
    public float sx = 5.6f, sy = 5.52f, ox = 4f, oy = 12f;
    private final File dir;

    private HdPack(File dir) throws Exception {
        this.dir = dir;
        video = new File(dir, "hd.mp4");
        JSONObject j = new JSONObject(new String(GameData.readFile(new File(dir, "hdpack.json")), "UTF-8"));
        Arrays.fill(chapterStart, Double.NaN);
        keyframeInterval = j.optDouble("keyframeInterval", 5.0);
        JSONObject ch = j.getJSONObject("chapters");
        for (Iterator<String> it = ch.keys(); it.hasNext(); ) {
            String k = it.next();
            int id = Integer.parseInt(k);
            if (id >= 0 && id < chapterStart.length) {
                chapterStart[id] = ch.getDouble(k);
                mask[id] = 1;
            }
        }
        JSONObject d = j.optJSONObject("dashboard");
        if (d != null) {
            t0 = d.optDouble("t0", 0);
            fps = d.optInt("fps", 60);
            unit = (float) d.optDouble("offsetUnit", 1.0);
            on = Base64.decode(d.getString("on"), Base64.DEFAULT);
            dx = shorts(Base64.decode(d.getString("dx"), Base64.DEFAULT), on.length, unit == 1.0f);
            dy = shorts(Base64.decode(d.getString("dy"), Base64.DEFAULT), on.length, unit == 1.0f);
            JSONObject p = d.getJSONObject("patches");
            rect(p.getJSONArray("help"), help);
            rect(p.getJSONArray("pause"), pause);
            rect(p.getJSONArray("screen"), screen);
        }
        JSONObject ind = j.optJSONObject("indicators");
        if (ind != null) {
            rect(ind.getJSONArray("rect"), indicators);
            hasIndicators = new File(dir, "ind_clean.png").exists();
        }
        fillGaps();
        JSONObject lv = j.optJSONObject("lever");
        if (lv != null) rect(lv.getJSONArray("rect"), lever);
        JSONObject m = j.optJSONObject("snesToFilm");
        if (m != null) {
            sx = (float) m.optDouble("sx", sx);
            sy = (float) m.optDouble("sy", sy);
            ox = (float) m.optDouble("ox", ox);
            oy = (float) m.optDouble("oy", oy);
        }
    }

    /** Short dropouts of the dashboard detection (< 0.5 s) would make it flicker: bridge them. */
    private void fillGaps() {
        int max = Math.max(1, fps / 2), i = 0, n = on.length;
        while (i < n) {
            if (on[i] != 0) { i++; continue; }
            int j = i;
            while (j < n && on[j] == 0) j++;
            if (i > 0 && j < n && j - i <= max) {
                for (int k = i; k < j; k++) {
                    float f = (k - i + 1) / (float) (j - i + 1);
                    on[k] = 1;
                    if (k < dx.length && j < dx.length) {
                        dx[k] = (short) Math.round(dx[i - 1] + (dx[j] - dx[i - 1]) * f);
                        dy[k] = (short) Math.round(dy[i - 1] + (dy[j] - dy[i - 1]) * f);
                    }
                }
            }
            i = j;
        }
    }

    private static short[] shorts(byte[] b, int n, boolean bytes) {
        short[] s = new short[n];
        if (bytes) {                        // format 1 of the old tool: int8 whole pixels
            for (int i = 0; i < n && i < b.length; i++) s[i] = b[i];
            return s;
        }
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n && bb.remaining() >= 2; i++) s[i] = bb.getShort();
        return s;
    }

    private static void rect(JSONArray a, int[] r) {
        for (int i = 0; i < 4 && i < a.length(); i++) r[i] = a.optInt(i);
    }

    // ------------------------------------------------------------------ files
    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "hd");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static boolean installed(Context ctx) {
        File d = new File(ctx.getFilesDir(), "hd");
        return new File(d, "hdpack.json").isFile() && new File(d, "hd.mp4").isFile();
    }

    public static long sizeBytes(Context ctx) {
        File[] l = new File(ctx.getFilesDir(), "hd").listFiles();
        long s = 0;
        if (l != null) for (File f : l) s += f.length();
        return s;
    }

    public static HdPack load(Context ctx) {
        try {
            return installed(ctx) ? new HdPack(dir(ctx)) : null;
        } catch (Exception e) {
            android.util.Log.e("SRBHd", "pack", e);
            return null;
        }
    }

    public Bitmap bitmap(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPremultiplied = true;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    // ------------------------------------------------------------------ queries
    /** Film time for a picture of the game, or NaN if that chapter is not in the film. */
    public double filmTime(int track, int audioSample) {
        if (track < 0 || track >= chapterStart.length || audioSample < 0) return Double.NaN;
        double s = chapterStart[track];
        return Double.isNaN(s) ? s : s + audioSample / 44100.0;
    }

    /** Is the iOS dashboard on screen at film time t? off = its offset in film pixels. */
    public boolean dashboardAt(double t, float[] off) {
        int i = (int) Math.round((t - t0) * fps);
        if (i < 0 || i >= on.length || on[i] == 0) return false;
        off[0] = dx[i] * unit;
        off[1] = dy[i] * unit;
        return true;
    }
}
