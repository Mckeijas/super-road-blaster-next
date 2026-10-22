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

    // --- high scores ----------------------------------------------------------------------
    /**
     * Hall of fame from the game's battery RAM (SuperRoadBlaster.srm), as the game writes it
     * (src/core/persistency.65816): "7 13" header, then one slot per Score object
     * ("8 13", object id, size, object data). The last 7 bytes of each Score are
     * score (16-bit, shown x1000), name (4 ASCII bytes) and lives.
     * Returns [{name, score}] best first, or the game's default table if none was saved yet.
     */
    public static org.json.JSONArray highScores(Context ctx) {
        java.util.List<long[]> list = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        boolean saved = false;
        try {
            byte[] d = readFile(new File(saveDir(ctx), "SuperRoadBlaster.srm"));
            int len = (d[2] & 0xff) | ((d[3] & 0xff) << 8);
            if ((d[0] & 0xff) == 0x37 && (d[1] & 0xff) == 0x13 && len > 8 && len <= d.length
                    && (d[len - 2] & 0xff) == 0x39 && (d[len - 1] & 0xff) == 0x13) {
                int p = 6;
                while (p + 4 <= len - 2 && (d[p] & 0xff) == 0x38 && (d[p + 1] & 0xff) == 0x13) {
                    int size = d[p + 3] & 0xff;
                    int data = p + 4;
                    if (size >= 7 && data + size <= len - 2) {
                        int e = data + size;
                        long score = (d[e - 7] & 0xff) | ((d[e - 6] & 0xff) << 8);
                        StringBuilder n = new StringBuilder();
                        for (int i = e - 5; i < e - 1; i++) {
                            int ch = d[i] & 0xff;
                            if (ch == 0) break;
                            if (ch >= 32 && ch < 127) n.append((char) ch);
                        }
                        list.add(new long[]{score, names.size()});
                        names.add(n.toString().trim());
                    }
                    p = data + size;
                }
                saved = !list.isEmpty();
            }
        } catch (Exception ignored) {
        }
        if (!saved) {               // the game's own defaults: "D4S" 50, 40, 30, 20, 10
            for (int i = 0; i < 5; i++) { list.add(new long[]{50 - i * 10, names.size()}); names.add("D4S"); }
        }
        list.sort((x, y) -> Long.compare(y[0], x[0]));
        // records made by clean runs (whole game, no Director's Mode help, no states): "NAM 724"
        java.util.List<String> marks = new java.util.ArrayList<>();
        try {
            for (String l : new String(readFile(new File(saveDir(ctx), "SuperRoadBlaster.marks")), "UTF-8").split("\n"))
                if (!l.trim().isEmpty()) marks.add(l.trim());
        } catch (Exception ignored) {
        }
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            for (long[] e : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", names.get((int) e[1]));
                o.put("score", e[0] * 1000);
                o.put("mark", marks.remove(names.get((int) e[1]) + " " + e[0]));
                o.put("defaults", !saved);
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out;
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
