package com.srbport.roadblaster;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports the game from the user's own .zip of the Super Road Blaster release
 * (SuperRoadBlaster.sfc + .msu + -0.pcm … -121.pcm) and then builds the scene selector
 * on the device (start states and thumbnails), so the app itself carries no game data.
 *
 * Phases reported to the launcher: idle | copy | scenes | done | error.
 */
public final class GameImporter {
    private static final String TAG = "SRBImport";
    private static final Pattern FILE = Pattern.compile("(?i)^.*?(\\.sfc|\\.smc|\\.msu|-(\\d{1,3})\\.pcm)$");
    private static final int GEN_FRAMES_ESTIMATE = 57000;   // whole adventure, ~19 minutes at 50 Hz

    private static volatile String phase = "idle";
    private static volatile int percent;
    private static volatile String message = "";
    private static volatile Thread worker;

    private GameImporter() {}

    public static boolean isBusy() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    public static JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("percent", percent);
            o.put("message", message);
            o.put("busy", isBusy());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** True when the scene selector data has been generated for the imported game. */
    public static boolean scenesReady(Context ctx) {
        return new File(GameData.scenesDir(ctx), "ready").exists();
    }

    /** Import a .zip chosen by the user, then generate the scenes. */
    public static synchronized void startImport(Context ctx, Uri uri) {
        if (isBusy()) return;
        Context app = ctx.getApplicationContext();
        phase = "copy";
        percent = 0;
        message = "";
        worker = new Thread(() -> {
            try {
                importZip(app, uri);
                generateScenes(app);
                phase = "done";
                percent = 100;
                message = "Juego cargado";
            } catch (Exception e) {
                Log.e(TAG, "import", e);
                phase = "error";
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }, "srb-import");
        worker.start();
    }

    /** Only the scene generation (game already imported but scenes missing or outdated). */
    public static synchronized void startScenes(Context ctx) {
        if (isBusy()) return;
        Context app = ctx.getApplicationContext();
        phase = "scenes";
        percent = 0;
        message = "";
        worker = new Thread(() -> {
            try {
                generateScenes(app);
                phase = "done";
                percent = 100;
            } catch (Exception e) {
                Log.e(TAG, "scenes", e);
                phase = "error";
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }, "srb-scenes");
        worker.start();
    }

    // ------------------------------------------------------------------ HD video add-on
    private static final String[] HD_FILES = {"hdpack.json", "hd.mp4", "patch_help.png", "patch_pause.png",
            "patch_screen.png", "lever_up.png", "lever_mid.png", "lever_down.png"};

    /** Installs the HD pack (.zip made with tools/hdpack/make_hdpack.py). */
    public static synchronized void startHdImport(Context ctx, Uri uri) {
        if (isBusy()) return;
        Context app = ctx.getApplicationContext();
        phase = "hd";
        percent = 0;
        message = "";
        worker = new Thread(() -> {
            try {
                importHd(app, uri);
                phase = "done";
                percent = 100;
                message = "Pack HD instalado";
            } catch (Exception e) {
                Log.e(TAG, "hd import", e);
                phase = "error";
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }, "srb-hd-import");
        worker.start();
    }

    public static void removeHd(Context ctx) {
        deleteTree(new File(ctx.getFilesDir(), "hd"));
    }

    private static void importHd(Context ctx, Uri uri) throws IOException {
        long zipSize = sizeOf(ctx, uri);
        File staging = new File(ctx.getFilesDir(), "hd_import");
        deleteTree(staging);
        staging.mkdirs();
        long free = new StatFs(ctx.getFilesDir().getAbsolutePath()).getAvailableBytes();
        if (zipSize > 0 && free < zipSize + (64L << 20)) {
            deleteTree(staging);
            throw new IOException(String.format(Locale.ROOT,
                    "No hay espacio suficiente: hacen falta unos %d MB libres.", (zipSize + (64L << 20)) >> 20));
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(java.util.Arrays.asList(HD_FILES));
        java.util.Set<String> got = new java.util.HashSet<>();
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("No se pudo abrir el archivo.");
            CountingStream counted = new CountingStream(raw);
            try (ZipInputStream zin = new ZipInputStream(counted)) {
                ZipEntry e;
                byte[] buf = new byte[1 << 18];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    if (!wanted.contains(base)) continue;
                    try (OutputStream os = new FileOutputStream(new File(staging, base))) {
                        int r;
                        while ((r = zin.read(buf)) > 0) {
                            os.write(buf, 0, r);
                            if (zipSize > 0) percent = (int) Math.min(99, counted.count * 100 / zipSize);
                        }
                    }
                    got.add(base);
                }
            }
        } catch (IOException e) {
            deleteTree(staging);
            if (String.valueOf(e.getMessage()).contains("ENOSPC")) throw new IOException("Se llenó el almacenamiento del dispositivo.");
            if (e instanceof java.util.zip.ZipException) throw new IOException("El archivo no es un .zip válido.");
            throw e;
        }
        if (!got.contains("hdpack.json") || !got.contains("hd.mp4")) {
            deleteTree(staging);
            throw new IOException("El .zip no es un pack HD de SRBN.");
        }
        File dst = new File(ctx.getFilesDir(), "hd");
        deleteTree(dst);
        if (!staging.renameTo(dst)) throw new IOException("No se pudo guardar el pack HD.");
        if (HdPack.load(ctx) == null) {
            deleteTree(dst);
            throw new IOException("El pack HD está dañado.");
        }
        Log.i(TAG, "hd pack installed");
    }

    // ------------------------------------------------------------------ 1. copy the zip
    private static void importZip(Context ctx, Uri uri) throws IOException {
        long zipSize = sizeOf(ctx, uri);
        File staging = new File(ctx.getFilesDir(), "game_import");
        deleteTree(staging);
        staging.mkdirs();
        long free = new StatFs(ctx.getFilesDir().getAbsolutePath()).getAvailableBytes();
        if (zipSize > 0 && free < zipSize + (64L << 20)) {
            throw new IOException(String.format(Locale.ROOT,
                    "No hay espacio suficiente: hacen falta unos %d MB libres.", (zipSize + (64L << 20)) >> 20));
        }
        int found = 0;
        boolean rom = false, msu = false;
        boolean[] pcm = new boolean[GameData.TRACKS];
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("No se pudo abrir el archivo.");
            CountingStream counted = new CountingStream(raw);
            try (ZipInputStream zin = new ZipInputStream(counted)) {
                ZipEntry e;
                byte[] buf = new byte[1 << 18];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                    String base = name.substring(slash + 1);
                    if (base.startsWith(".")) continue;
                    Matcher m = FILE.matcher(base);
                    if (!m.matches()) continue;
                    String ext = m.group(1).toLowerCase(Locale.ROOT);
                    String target;
                    if (ext.equals(".sfc") || ext.equals(".smc")) { target = "SuperRoadBlaster.sfc"; rom = true; }
                    else if (ext.equals(".msu")) { target = "SuperRoadBlaster.msu"; msu = true; }
                    else {
                        int n = Integer.parseInt(m.group(2));
                        if (n < 0 || n >= GameData.TRACKS) continue;
                        target = "SuperRoadBlaster-" + n + ".pcm";
                        pcm[n] = true;
                    }
                    File out = new File(staging, target);
                    try (OutputStream os = new FileOutputStream(out)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) {
                            os.write(buf, 0, r);
                            if (zipSize > 0) percent = (int) Math.min(99, counted.count * 100 / zipSize);
                        }
                    }
                    if (ext.equals(".smc")) stripCopierHeader(out);
                    found++;
                }
            }
        } catch (IOException e) {
            deleteTree(staging);
            if (String.valueOf(e.getMessage()).contains("ENOSPC")) throw new IOException("Se llenó el almacenamiento del dispositivo.");
            if (e instanceof java.util.zip.ZipException) throw new IOException("El archivo no es un .zip válido.");
            throw e;
        }
        // --- check what we got
        StringBuilder missing = new StringBuilder();
        if (!rom) missing.append(" la ROM (.sfc)");
        if (!msu) missing.append(missing.length() > 0 ? "," : "").append(" el vídeo (.msu)");
        int pcmMissing = 0;
        for (boolean b : pcm) if (!b) pcmMissing++;
        if (pcmMissing > 0) missing.append(missing.length() > 0 ? "," : "").append(" ").append(pcmMissing).append(" pistas de audio (.pcm)");
        if (missing.length() > 0) {
            deleteTree(staging);
            throw new IOException(found == 0 ? "El .zip no contiene Super Road Blaster."
                    : "Al .zip le falta" + missing + ".");
        }
        if (!looksLikeSrb(new File(staging, "SuperRoadBlaster.sfc"))) {
            deleteTree(staging);
            throw new IOException("La ROM del .zip no es Super Road Blaster.");
        }
        if (!startsWith(new File(staging, "SuperRoadBlaster.msu"), "S-MSU1")) {
            deleteTree(staging);
            throw new IOException("El archivo .msu no es válido.");
        }
        // --- swap in the new game, old scene data is now outdated
        File game = new File(ctx.getFilesDir(), "game");
        deleteTree(game);
        if (!staging.renameTo(game)) throw new IOException("No se pudo guardar el juego.");
        deleteTree(GameData.scenesDir(ctx));
        Log.i(TAG, "game imported");
    }

    // ------------------------------------------------------------------ 2. build the scenes
    private static void generateScenes(Context ctx) throws Exception {
        phase = "scenes";
        percent = 0;
        File out = GameData.scenesDir(ctx);
        deleteTree(out);
        out.mkdirs();
        String chapters = GameData.readAssetText(ctx.getAssets(), GameData.CHAPTERS_TXT);
        int expected = 0;
        JSONArray list = new JSONArray(GameData.readAssetText(ctx.getAssets(), GameData.CHAPTERS_JSON));
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            if (!"crash".equals(c.optString("kind")) && c.optBoolean("hasState")) expected++;
        }
        byte[] rom = GameData.readFile(GameData.romFile(ctx));
        synchronized (GameActivity.sessionLock()) {
            GameActivity.stopActiveSessionLocked();
            // own scratch save folder: the automatic run must not touch the player's high scores
            File genSave = new File(ctx.getCacheDir(), "scene_gen");
            genSave.mkdirs();
            if (!NativeBridge.nativeGenBegin(rom, chapters, GameData.gameDir(ctx).getAbsolutePath(),
                    genSave.getAbsolutePath(), out.getAbsolutePath())) {
                throw new IOException("No se pudo arrancar el juego importado.");
            }
            try {
                while (NativeBridge.nativeGenRun(1500, 75000, expected) >= 0) {
                    percent = Math.min(99, NativeBridge.nativeGenFrame() * 100 / GEN_FRAMES_ESTIMATE);
                }
            } finally {
                NativeBridge.nativeGenEnd();
            }
        }
        GameData.writeFile(new File(out, "ready"), "1".getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "scenes ready");
    }

    // ------------------------------------------------------------------ helpers
    private static long sizeOf(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Super Road Blaster's internal header name, in the HiROM or LoROM header. */
    private static boolean looksLikeSrb(File rom) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(rom, "r")) {
            for (long at : new long[]{0xFFC0, 0x7FC0}) {
                if (f.length() < at + 21) continue;
                byte[] h = new byte[21];
                f.seek(at);
                f.readFully(h);
                String t = new String(h, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                if (t.contains("ROAD BLASTER")) return true;
            }
        }
        return false;
    }

    private static boolean startsWith(File f, String magic) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[magic.length()];
            if (r.length() < h.length) return false;
            r.readFully(h);
            return magic.equals(new String(h, StandardCharsets.US_ASCII));
        }
    }

    /** .smc dumps may carry a 512-byte copier header. */
    private static void stripCopierHeader(File f) throws IOException {
        if (f.length() % 1024 != 512) return;
        byte[] all = GameData.readFile(f);
        byte[] rom = new byte[all.length - 512];
        System.arraycopy(all, 512, rom, 0, rom.length);
        GameData.writeFile(f, rom);
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }

    private static final class CountingStream extends FilterInputStream {
        volatile long count;
        CountingStream(InputStream in) { super(in); }
        @Override public int read() throws IOException { int r = super.read(); if (r >= 0) count++; return r; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }
        @Override public long skip(long n) throws IOException { long r = super.skip(n); count += r; return r; }
    }
}
