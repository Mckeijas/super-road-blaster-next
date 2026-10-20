package com.srbport.roadblaster;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebView;

import org.json.JSONObject;

/** Front-end launcher: graphics / audio / Director's Mode options and scene select. */
public final class LauncherActivity extends Activity implements UiBridge.Host {
    private WebView web;
    private Settings settings;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = new Settings(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07040C);
        setContentView(web);
        WebUi.setup(web, new UiBridge(this, settings, this), "launcher.html");
        web.setBackgroundColor(0xFF07040C);
        hideSystemUi();
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

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        web.evaluateJavascript("window.srbRefresh && window.srbRefresh()", null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
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
