package com.srbport.roadblaster;

import android.app.Activity;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** The game screen: emulator + renderer + pause menu overlay. */
public final class GameActivity extends Activity implements EmuThread.Listener, UiBridge.Host {
    public static final String EXTRA_MODE = "mode";   // new | resume | scene | movie | slot
    public static final String EXTRA_ARG = "arg";
    private static final String TAG = "SRBGame";
    private static final int CHAPTER_INTRO = 2;

    private Settings settings;
    private GLSurfaceView glView;
    private GameRenderer renderer;
    private WebView menu;
    private FrameLayout root;
    private EmuThread emu;
    private final PadInput pad = new PadInput();
    private TouchControls touch;
    private volatile int touchMask;
    private boolean menuOpen;
    private boolean movieMode;
    private boolean started;
    private float currentSpeed = 1f;
    private Vibrator vibrator;
    // chapters that are part of a level (START opens our pause menu there)
    private boolean[] levelChapter = new boolean[0];
    private volatile int lastChapter = -1;

    // The native core is a process-wide singleton: only one session may own it.
    private static final Object SESSION_LOCK = new Object();
    private static EmuThread activeEmu;

    /** Lock for anything that uses the native core (the game or the scene generator). */
    static Object sessionLock() { return SESSION_LOCK; }

    /** Stops a game session that is still alive. Call while holding sessionLock(). */
    static void stopActiveSessionLocked() {
        if (activeEmu != null) {
            activeEmu.shutdown();
            activeEmu = null;
            NativeBridge.nativeShutdown();
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        settings = new Settings(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        root = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        renderer = new GameRenderer(getAssets());
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        touch = new TouchControls(this, new TouchControls.Listener() {
            @Override public void onTouchButtons(int mask) {
                touchMask = mask;
                pushButtons();
            }
            @Override public void onTouchStart() {
                if (!started) return;
                if (inLevel()) { openMenu(true); return; }
                // outside levels START belongs to the game (title screen, high scores)
                touchMask |= NativeBridge.BTN_START;
                pushButtons();
                glView.postDelayed(() -> { touchMask &= ~NativeBridge.BTN_START; pushButtons(); }, 150);
            }
            @Override public void onTouchPause() { openMenu(false); }
        });
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        createMenu();
        setContentView(root);
        hideSystemUi();

        movieMode = "movie".equals(getIntent().getStringExtra(EXTRA_MODE));
        new Thread(this::boot, "srb-boot").start();
    }

    private void boot() {
        try {
            if (!GameData.isComplete(this) || GameImporter.isBusy()) {
                fail("Primero carga el juego desde el menú principal (Cargar juego .zip).");
                return;
            }
            byte[] rom = GameData.readFile(GameData.romFile(this));
            String chapters = GameData.readAssetText(getAssets(), GameData.CHAPTERS_TXT);
            parseLevelChapters(chapters);
            String override = "custom".equals(settings.get("audio"))
                    ? GameData.soundtrackDir(this).getAbsolutePath() : "";
            synchronized (SESSION_LOCK) {
                if (activeEmu != null) { // previous session still alive: stop it first
                    activeEmu.shutdown();
                    activeEmu = null;
                    NativeBridge.nativeShutdown();
                }
                if (isFinishing() || isDestroyed()) return;
                if (!NativeBridge.nativeInit(getAssets(), rom, chapters,
                        GameData.saveDir(this).getAbsolutePath(), override,
                        GameData.gameDir(this).getAbsolutePath(), GameData.enhancedGains(getAssets()))) {
                    fail("No se pudo iniciar el juego.");
                    return;
                }
                emu = new EmuThread(this);
                activeEmu = emu;
            }
            applyOptions();
            String mode = getIntent().getStringExtra(EXTRA_MODE);
            String arg = getIntent().getStringExtra(EXTRA_ARG);
            byte[] state = null;
            if ("resume".equals(mode)) {
                File f = GameData.resumeFile(this);
                if (f.exists()) state = GameData.readFile(f);
            } else if ("scene".equals(mode) && arg != null) {
                state = GameData.readFile(GameData.sceneStateFile(this, Integer.parseInt(arg)));
            } else if ("movie".equals(mode)) {
                state = GameData.readFile(GameData.sceneStateFile(this, CHAPTER_INTRO));
            } else if ("slot".equals(mode) && arg != null) {
                File f = GameData.slotFile(this, Integer.parseInt(arg));
                if (f.exists()) state = GameData.readFile(f);
            }
            if (state != null && !NativeBridge.nativeLoadState(state)) Log.w(TAG, "state load failed");
            started = true;
            emu.start();
            if (isFinishing()) runOnUiThread(this::stopSession);
        } catch (Exception e) {
            Log.e(TAG, "boot", e);
            fail("Error al cargar los datos del juego: " + e.getMessage());
        }
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    /** Pushes every setting to the native core and the renderer. */
    private void applyOptions() {
        NativeBridge.nativeSetOption(NativeBridge.OPT_AUTO_QTE, settings.num("qte", 0));
        NativeBridge.nativeSetOption(NativeBridge.OPT_INFINITE_LIVES, settings.flag("lives") ? 1 : 0);
        NativeBridge.nativeSetOption(NativeBridge.OPT_HUD, settings.flag("hud") ? 1 : 0);
        NativeBridge.nativeSetOption(NativeBridge.OPT_MOVIE_MODE, movieMode ? 1 : 0);
        NativeBridge.nativeSetOption(NativeBridge.OPT_ENHANCED_AUDIO, "enhanced".equals(settings.get("audio")) ? 1 : 0);
        NativeBridge.nativeSetOption(NativeBridge.OPT_MSU_VOLUME, settings.num("msuVol", 100) / 100f);
        NativeBridge.nativeSetOption(NativeBridge.OPT_SFX_VOLUME, settings.num("sfxVol", 100) / 100f);
        pad.setLayout(settings.get("layout"));
        renderer.configure(settings.get("filter"), settings.flag("color"), settings.get("aspect"));
        String touchMode = settings.get("touch");
        float touchAlpha = settings.num("touchAlpha", 0.2f);
        runOnUiThread(() -> touch.configure(touchMode, touchAlpha, movieMode));
        currentSpeed = -1f;
        updateSpeed();
    }

    private void updateSpeed() {
        float sp = 1f;
        if (pad.slowHeld() || settings.flag("slowmo")) sp = settings.num("slowSpeed", 0.5f);
        else if (pad.fastHeld()) sp = 2f;
        if (sp != currentSpeed) {
            currentSpeed = sp;
            NativeBridge.nativeSetOption(NativeBridge.OPT_SPEED, sp);
        }
    }

    // --- EmuThread.Listener ------------------------------------------------------
    @Override
    public void onFrame() {
        int ch = NativeBridge.nativeCurrentChapter();
        if (ch >= 0) lastChapter = ch;
        glView.requestRender();
    }

    private void parseLevelChapters(String txt) {
        boolean[] lv = new boolean[256];
        for (String line : txt.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 5 && "C".equals(f[0])) {
                try {
                    int id = Integer.parseInt(f[1]);
                    if (id >= 0 && id < lv.length) lv[id] = f[4].startsWith("level");
                } catch (NumberFormatException ignored) {
                }
            }
        }
        levelChapter = lv;
    }

    /** True while a level is being played (not title, intro or high scores). */
    private boolean inLevel() {
        int ch = lastChapter;
        return ch >= 0 && ch < levelChapter.length && levelChapter[ch];
    }

    @Override
    public void onRumble(int strength) {
        if (vibrator != null && settings.flag("rumble") && !menuOpen) {
            vibrator.vibrate(VibrationEffect.createOneShot(70, Math.max(1, Math.min(255, strength))));
        }
    }

    // --- input ------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (menuOpen) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && PadInput.actionFor(code) == PadInput.ACT_MENU
                    && code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ESCAPE) {
                closeMenu();
                return true;
            }
            WebUi.dispatchPad(menu, e);
            return true;
        }
        int act = PadInput.actionFor(code);
        if (act == PadInput.ACT_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openMenu(false);
            return true;
        }
        if (act == PadInput.ACT_HUD) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                boolean hud = !settings.flag("hud");
                settings.set("hud", hud ? "1" : "0");
                NativeBridge.nativeSetOption(NativeBridge.OPT_HUD, hud ? 1 : 0);
                Toast.makeText(this, hud ? "HUD visible" : "HUD oculto", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        // The game's own START pause only dims the screen: during a level, START opens
        // our pause menu instead (with title and the option to go back to the launcher).
        if (pad.bitFor(code) == NativeBridge.BTN_START && started && inLevel()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openMenu(true);
            return true;
        }
        if (pad.onKey(e)) {
            touch.onPhysicalInput();
            pushButtons();
            updateSpeed();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!menuOpen && pad.onMotion(e)) {
            touch.onPhysicalInput();
            pushButtons();
            updateSpeed();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** Physical controls and touch buttons together. */
    private void pushButtons() {
        if (started && !menuOpen) NativeBridge.nativeSetButtons(pad.mask() | touchMask);
    }

    /** (Re)creates the pause menu WebView, also after its render process died. */
    private void createMenu() {
        if (menu != null) {
            root.removeView(menu);
            menu.destroy();
        }
        menu = new WebView(this);
        WebUi.setup(menu, new UiBridge(this, settings, this), "pause.html", () -> {
            boolean wasOpen = menuOpen;
            createMenu();
            if (wasOpen && emu != null) { menuOpen = false; renderer.setPaused(false); emu.setPausedState(false); openMenu(false); }
        });
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
    }

    // --- pause menu -------------------------------------------------------------
    /** mini = in-game pause (START): big "PAUSA" + continue / options / exit to launcher. */
    private void openMenu(boolean mini) {
        if (menuOpen || emu == null) return;
        menuOpen = true;
        pad.clear();
        touch.releaseAll();
        touchMask = 0;
        NativeBridge.nativeSetButtons(0);
        emu.setPausedState(true);
        renderer.setPaused(true);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY); // animate the blurred backdrop
        menu.setVisibility(View.VISIBLE);
        menu.evaluateJavascript(mini ? "window.srbOpenMini && window.srbOpenMini()"
                : "window.srbOpen && window.srbOpen()", null);
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        renderer.setPaused(false); // fades out while the game runs again
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        menu.evaluateJavascript("window.srbClose && window.srbClose()", null);
        menu.setVisibility(View.GONE);
        pad.clear();
        if (emu != null) emu.setPausedState(false);
        hideSystemUi();
    }

    @Override
    public void uiAction(String action, String arg) {
        switch (action) {
            case "resume":
                closeMenu();
                break;
            case "save": {
                int slot = parse(arg, 1);
                emu.post(() -> {
                    byte[] st = NativeBridge.nativeSaveState();
                    try {
                        GameData.writeFile(GameData.slotFile(this, slot), st);
                        String ch = String.valueOf(NativeBridge.nativeCurrentChapter());
                        GameData.writeFile(GameData.slotInfoFile(this, slot), ch.getBytes(StandardCharsets.UTF_8));
                        toast("Guardado en la ranura " + slot);
                    } catch (Exception ex) {
                        toast("No se pudo guardar");
                    }
                    refreshMenu();
                });
                break;
            }
            case "load": {
                int slot = parse(arg, 1);
                emu.post(() -> {
                    try {
                        NativeBridge.nativeLoadState(GameData.readFile(GameData.slotFile(this, slot)));
                        runOnUiThread(this::closeMenu);
                    } catch (Exception ex) {
                        toast("Ranura vacía");
                    }
                });
                break;
            }
            case "scene": {
                int ch = parse(arg, -1);
                emu.post(() -> {
                    try {
                        NativeBridge.nativeLoadState(GameData.readFile(GameData.sceneStateFile(this, ch)));
                        runOnUiThread(this::closeMenu);
                    } catch (Exception ex) {
                        toast("Escena no disponible");
                    }
                });
                break;
            }
            case "restart": {
                emu.post(() -> {
                    int ch = NativeBridge.nativeCurrentChapter();
                    try {
                        if (ch < 0) throw new IllegalStateException();
                        NativeBridge.nativeLoadState(GameData.readFile(GameData.sceneStateFile(this, ch)));
                        runOnUiThread(this::closeMenu);
                    } catch (Exception ex) {
                        toast("No hay punto de inicio para esta escena");
                    }
                });
                break;
            }
            case "menu":
                finish();
                break;
            case "quit":
                finishAffinity();
                break;
            default:
                break;
        }
    }

    @Override
    public void uiSettingChanged(String key, String value) {
        if (started) {
            applyOptions();
            glView.requestRender(); // redraw the paused frame with the new filter
        }
    }

    @Override
    public JSONObject uiExtraState() {
        JSONObject o = new JSONObject();
        try {
            o.put("inGame", true);
            o.put("chapter", started ? NativeBridge.nativeCurrentChapter() : -1);
            o.put("movie", movieMode);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void refreshMenu() {
        runOnUiThread(() -> menu.evaluateJavascript("window.srbRefresh && window.srbRefresh()", null));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    // --- lifecycle ----------------------------------------------------------------
    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
        menu.onPause();
        menu.pauseTimers();
        touch.releaseAll();
        touchMask = 0;
        if (emu != null) {
            emu.setPausedState(true);
            emu.post(() -> {
                byte[] st = NativeBridge.nativeSaveState();
                try {
                    if (st != null && !movieMode) {
                        GameData.writeFile(GameData.resumeFile(this), st);
                        GameData.writeFile(GameData.resumeInfoFile(this),
                                String.valueOf(NativeBridge.nativeCurrentChapter()).getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                }
                NativeBridge.nativeSaveSram();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        menu.resumeTimers();
        menu.onResume();
        glView.onResume();
        hideSystemUi();
        if (emu != null && !menuOpen) emu.setPausedState(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        stopSession();
        menu.destroy();
        super.onDestroy();
    }

    private void stopSession() {
        synchronized (SESSION_LOCK) {
            if (emu != null && emu == activeEmu) {
                emu.shutdown();
                activeEmu = null;
                NativeBridge.nativeShutdown();
            }
        }
    }

    private void hideSystemUi() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    public static Intent intent(Activity from, String mode, String arg) {
        Intent i = new Intent(from, GameActivity.class);
        i.putExtra(EXTRA_MODE, mode);
        if (arg != null) i.putExtra(EXTRA_ARG, arg);
        return i;
    }
}
