package com.srbport.roadblaster;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebView;

import org.json.JSONObject;

/** Front-end launcher: graphics / audio / Director's Mode options and scene select. */
public final class LauncherActivity extends Activity implements UiBridge.Host {
    private static final int REQ_IMPORT = 41;
    private WebView web;
    private Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean keepAwake;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = new Settings(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07040C);
        setContentView(web);
        WebUi.setup(web, new UiBridge(this, settings, this), "launcher.html", this::rebuild);
        web.setBackgroundColor(0xFF07040C);
        hideSystemUi();
        // game imported but its scene data is missing (first run after updating, or an
        // interrupted import): build it now
        if (GameData.isComplete(this) && !GameImporter.scenesReady(this) && !GameImporter.isBusy()) {
            GameImporter.startScenes(this);
        }
        watchImport();
    }

    /** Keeps the screen on while importing and refreshes the page when it finishes. */
    private void watchImport() {
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() {
            boolean wasBusy;
            @Override public void run() {
                boolean busy = GameImporter.isBusy();
                if (busy != keepAwake) {
                    keepAwake = busy;
                    if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                if (wasBusy && !busy) web.evaluateJavascript("window.srbRefresh && window.srbRefresh()", null);
                wasBusy = busy;
                handler.postDelayed(this, 700);
            }
        });
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                "application/x-zip", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            web.evaluateJavascript("window.srbToast && window.srbToast('No hay explorador de archivos disponible')", null);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        GameImporter.startImport(this, uri);
        web.evaluateJavascript("window.srbRefresh && window.srbRefresh()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(e);
        }
        if (WebUi.dispatchPad(web, e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void uiAction(String action, String arg) {
        switch (action) {
            case "play": {
                // arg: new | resume | movie | scene:N | slot:N
                String mode = arg, extra = null;
                int colon = arg.indexOf(':');
                if (colon > 0) { mode = arg.substring(0, colon); extra = arg.substring(colon + 1); }
                startActivity(GameActivity.intent(this, mode, extra));
                break;
            }
            case "import":
                if (!GameImporter.isBusy()) pickZip();
                break;
            case "quit":
                finishAffinity();
                break;
            default:
                break;
        }
    }

    @Override
    public void uiSettingChanged(String key, String value) { }

    @Override
    public JSONObject uiExtraState() { return null; }

    /** The page's render process died: throw the WebView away and start the screen again. */
    private void rebuild() {
        if (isFinishing() || isDestroyed()) return;
        try {
            ((android.view.ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        } catch (Exception ignored) {
        }
        recreate();
    }

    @Override
    protected void onPause() {
        // stop video, animations and timers while the screen is off or another app is on top
        web.evaluateJavascript("window.srbSleep && window.srbSleep()", null);
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
        hideSystemUi();
        web.evaluateJavascript("window.srbRefresh && window.srbRefresh(); window.srbWake && window.srbWake()", null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    private void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
