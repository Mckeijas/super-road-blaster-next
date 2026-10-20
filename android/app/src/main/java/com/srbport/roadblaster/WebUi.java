package com.srbport.roadblaster;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Helpers for the HTML user interface (launcher and pause menu). */
public final class WebUi {
    private WebUi() {}

    @SuppressLint("SetJavaScriptEnabled")
    public static void setup(WebView wv, UiBridge bridge, String page) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setFocusable(false);               // keys are routed by the activity
        wv.setFocusableInTouchMode(false);
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(bridge, "Android");
        wv.loadUrl("file:///android_asset/ui/" + page);
    }

    /** Forwards a controller key to the page (window.srbPad). Returns true if handled. */
    public static boolean dispatchPad(WebView wv, KeyEvent e) {
        String tok = PadInput.uiToken(e.getKeyCode());
        if (tok == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            String js = "window.srbPad && window.srbPad('" + tok + "'," + (e.getRepeatCount() > 0) + ")";
            wv.evaluateJavascript(js, null);
        }
        return true;
    }
}
