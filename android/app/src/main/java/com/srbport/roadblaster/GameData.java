package com.srbport.roadblaster;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Access to the game files packed in the APK and to the user's save data. */
public final class GameData {
    // Chapter scripts (from the game's MIT-licensed source) travel in the APK. The game
    // itself (ROM, .msu, .pcm) is imported by the user from a .zip (see GameImporter).
    public static final String CHAPTERS_TXT = "game/chapters.txt";
    public static final String CHAPTERS_JSON = "game/chapters.json";
    public static final String GAINS_JSON = "game/enhanced_gains.json";
    public static final int TRACKS = 122;

    private GameData() {}

    public static byte[] readAsset(AssetManager am, String path) throws IOException {
        try (InputStream in = am.open(path)) {
            return readAll(in);
        }
    }

    public static String readAssetText(AssetManager am, String path) {
        try {
            return new String(readAsset(am, path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean assetExists(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // --- imported game ------------------------------------------------------------------
    public static File gameDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "game");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File romFile(Context ctx) { return new File(gameDir(ctx), "SuperRoadBlaster.sfc"); }

    /** True when the game has been imported (ROM, .msu and every .pcm track). */
    public static boolean isComplete(Context ctx) {
        File d = gameDir(ctx);
        if (!new File(d, "SuperRoadBlaster.sfc").isFile() || !new File(d, "SuperRoadBlaster.msu").isFile()) return false;
        for (int i = 0; i < TRACKS; i++) if (!new File(d, "SuperRoadBlaster-" + i + ".pcm").isFile()) return false;
        return true;
    }

    /** Scene selector data generated on the device after importing: states/ and thumbs/. */
    public static File scenesDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "scenes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File sceneStateFile(Context ctx, int chapter) {
        return new File(scenesDir(ctx), String.format(java.util.Locale.ROOT, "states/%03d.state", chapter));
    }

    public static File sceneThumbFile(Context ctx, int chapter) {
        return new File(scenesDir(ctx), String.format(java.util.Locale.ROOT, "thumbs/%03d.png", chapter));
    }

    /** MSU-1 Enhanced loudness gain per track, in dB (numbers only, shipped with the app). */
    public static float[] enhancedGains(AssetManager am) {
        float[] g = new float[TRACKS];
        try {
            org.json.JSONArray a = new org.json.JSONObject(readAssetText(am, GAINS_JSON)).getJSONArray("gainDb");
            for (int i = 0; i < g.length && i < a.length(); i++) g[i] = (float) a.optDouble(i, 0);
        } catch (Exception ignored) {
        }
        return g;
    }

    // --- user files ---------------------------------------------------------------
    public static File saveDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "saves");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File resumeFile(Context ctx) { return new File(saveDir(ctx), "resume.state"); }

    public static File resumeInfoFile(Context ctx) { return new File(saveDir(ctx), "resume.info"); }

    public static File slotFile(Context ctx, int slot) {
        return new File(saveDir(ctx), "slot" + slot + ".state");
    }

    public static File slotInfoFile(Context ctx, int slot) {
        return new File(saveDir(ctx), "slot" + slot + ".info");
    }

    /** Folder for an alternative soundtrack (SuperRoadBlaster-N.pcm or .flac). */
    public static File soundtrackDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "banda_sonora");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    public static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
