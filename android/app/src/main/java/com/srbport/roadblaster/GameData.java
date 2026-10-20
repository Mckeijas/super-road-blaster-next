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
    public static final String ROM = "game/SuperRoadBlaster.sfc";
    public static final String CHAPTERS_TXT = "game/chapters.txt";
    public static final String CHAPTERS_JSON = "game/chapters.json";

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

    /** True when the ROM and the MSU-1 data were packed into this APK. */
    public static boolean isComplete(AssetManager am) {
        return assetExists(am, ROM) && assetExists(am, "game/SuperRoadBlaster.msu")
                && assetExists(am, CHAPTERS_TXT);
    }

    public static String sceneStatePath(int chapter) {
        return String.format(java.util.Locale.ROOT, "game/states/%03d.state", chapter);
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
