package com.srbport.roadblaster;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** window.Android in the launcher and in the pause menu. */
public final class UiBridge {
    /** Implemented by the hosting activity. */
    public interface Host {
        void uiAction(String action, String arg);   // play / resume / save / load / menu / quit ...
        void uiSettingChanged(String key, String value);
        JSONObject uiExtraState();
    }

    private final Activity activity;
    private final Settings settings;
    private final Host host;

    public UiBridge(Activity a, Settings s, Host h) {
        activity = a;
        settings = s;
        host = h;
    }

    @JavascriptInterface
    public String getState() {
        try {
            JSONObject o = new JSONObject();
            o.put("settings", settings.toJson());
            // scene availability comes from the data generated on this device after importing
            JSONArray chapters = new JSONArray(GameData.readAssetText(activity.getAssets(), GameData.CHAPTERS_JSON));
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject c = chapters.getJSONObject(i);
                int id = c.optInt("id", -1);
                c.put("hasState", GameData.sceneStateFile(activity, id).exists());
                c.put("hasThumb", GameData.sceneThumbFile(activity, id).exists());
                File clipInfo = new File(GameData.scenesDir(activity), String.format(Locale.ROOT, "clips/%03d.txt", id));
                if (clipInfo.exists()) {   // "frames fps columns" of the moving preview
                    String[] f = new String(GameData.readFile(clipInfo)).trim().split("\\s+");
                    if (f.length >= 3) {
                        c.put("clipFrames", Integer.parseInt(f[0]));
                        c.put("clipFps", Double.parseDouble(f[1]));
                        c.put("clipCols", Integer.parseInt(f[2]));
                    }
                }
            }
            o.put("chapters", chapters);
            o.put("thumbBase", "file://" + new File(GameData.scenesDir(activity), "thumbs").getAbsolutePath() + "/");
            o.put("clipBase", "file://" + new File(GameData.scenesDir(activity), "clips").getAbsolutePath() + "/");
            o.put("scenesReady", GameImporter.scenesReady(activity));
            o.put("import", GameImporter.status());
            o.put("hasResume", GameData.resumeFile(activity).exists());
            File ri = GameData.resumeInfoFile(activity);
            o.put("resumeChapter", ri.exists() ? new String(GameData.readFile(ri)).trim() : "-1");
            android.os.BatteryManager bm = (android.os.BatteryManager) activity.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                o.put("battery", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY));
                o.put("charging", bm.isCharging());
            }
            o.put("gameComplete", GameData.isComplete(activity));
            boolean hd = HdPack.installed(activity);
            o.put("hdInstalled", hd);
            o.put("hdSizeMb", hd ? HdPack.sizeBytes(activity) >> 20 : 0);
            o.put("scores", GameData.highScores(activity));
            o.put("soundtrackDir", GameData.soundtrackDir(activity).getAbsolutePath());
            JSONArray slots = new JSONArray();
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            for (int i = 1; i <= 3; i++) {
                JSONObject s = new JSONObject();
                File f = GameData.slotFile(activity, i);
                s.put("slot", i);
                s.put("exists", f.exists());
                if (f.exists()) {
                    s.put("date", fmt.format(new Date(f.lastModified())));
                    File info = GameData.slotInfoFile(activity, i);
                    s.put("chapter", info.exists() ? new String(GameData.readFile(info)).trim() : "-1");
                }
                slots.put(s);
            }
            o.put("slots", slots);
            JSONObject extra = host.uiExtraState();
            if (extra != null) o.put("extra", extra);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void setSetting(String key, String value) {
        settings.set(key, value);
        activity.runOnUiThread(() -> host.uiSettingChanged(key, value));
    }

    @JavascriptInterface
    public void resetSettings() { settings.reset(); }

    @JavascriptInterface
    public String readAsset(String path) {
        if (path.contains("..")) return "";
        return GameData.readAssetText(activity.getAssets(), path);
    }

    /** data: URL for images used as WebGL textures (file:// images are tainted). */
    @JavascriptInterface
    public String readAssetDataUrl(String path) {
        if (path.contains("..")) return "";
        try {
            byte[] b = GameData.readAsset(activity.getAssets(), path);
            String mime = path.endsWith(".png") ? "image/png" : path.endsWith(".wav") ? "audio/wav"
                    : path.endsWith(".mp4") ? "video/mp4" : "image/jpeg";
            return "data:" + mime + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (IOException e) {
            return "";
        }
    }

    /** data: URL of a generated scene thumbnail (WebGL textures can't use file:// images). */
    @JavascriptInterface
    public String readSceneDataUrl(int chapter) {
        File f = GameData.sceneThumbFile(activity, chapter);
        if (!f.exists()) return "";
        try {
            return "data:image/png;base64," + Base64.encodeToString(GameData.readFile(f), Base64.NO_WRAP);
        } catch (IOException e) {
            return "";
        }
    }

    @JavascriptInterface
    public void action(String action, String arg) {
        activity.runOnUiThread(() -> host.uiAction(action, arg));
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        android.os.Vibrator v = (android.os.Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && settings.flag("rumble")) {
            v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 200)), 120));
        }
    }
}
