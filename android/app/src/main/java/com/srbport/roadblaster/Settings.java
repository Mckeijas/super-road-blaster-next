package com.srbport.roadblaster;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** All user options (launcher + pause menu), stored as strings in SharedPreferences. */
public final class Settings {
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        // Graphics
        DEFAULTS.put("filter", "hd");          // original | smooth | hd | hd_strong | crt
        DEFAULTS.put("color", "1");            // 1 = remastered colours
        DEFAULTS.put("aspect", "43");          // 43 | fill | zoom
        DEFAULTS.put("hdVideo", "1");          // use the HD film when the HD pack is installed
        // Audio
        DEFAULTS.put("audio", "enhanced");     // original | enhanced | custom
        DEFAULTS.put("msuVol", "100");         // 0..150 %
        DEFAULTS.put("sfxVol", "100");
        // Director's Mode
        DEFAULTS.put("qte", "0");              // 0 off | 1 assisted | 2 automatic
        DEFAULTS.put("lives", "0");            // infinite lives
        DEFAULTS.put("slowSpeed", "0.5");      // speed used by slow motion
        DEFAULTS.put("slowmo", "0");           // slow motion always on
        DEFAULTS.put("hud", "1");              // show score / arrows
        // Controls
        DEFAULTS.put("layout", "snes");        // snes (by position) | xbox (by label)
        DEFAULTS.put("rumble", "1");
        DEFAULTS.put("touch", "auto");         // auto | on | off  (floating touch buttons)
        DEFAULTS.put("touchAlpha", "0.2");     // opacity of the touch buttons
        // Interface
        DEFAULTS.put("uiSound", "1");          // menu sound effects
        DEFAULTS.put("intro", "1");            // animated title when the app opens
        // Hidden
        DEFAULTS.put("dev", "0");
    }

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("srb_settings", Context.MODE_PRIVATE);
    }

    public String get(String key) {
        String def = DEFAULTS.get(key);
        return prefs.getString(key, def == null ? "" : def);
    }

    public boolean flag(String key) { return "1".equals(get(key)); }

    public float num(String key, float fallback) {
        try { return Float.parseFloat(get(key)); } catch (NumberFormatException e) { return fallback; }
    }

    public void set(String key, String value) {
        if (!DEFAULTS.containsKey(key)) return;
        prefs.edit().putString(key, value).apply();
    }

    public void reset() { prefs.edit().clear().apply(); }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            for (String k : DEFAULTS.keySet()) o.put(k, get(k));
        } catch (JSONException ignored) {
        }
        return o;
    }
}
