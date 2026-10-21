package com.srbport.roadblaster;

import android.content.res.AssetManager;

import java.nio.ByteBuffer;

/** JNI entry points of libsrbport.so (Snes9x + MSU-1 + Director's Mode). */
public final class NativeBridge {
    static {
        System.loadLibrary("srbport");
    }

    public static final int OPT_AUTO_QTE = 0;
    public static final int OPT_INFINITE_LIVES = 1;
    public static final int OPT_SPEED = 2;
    public static final int OPT_MOVIE_MODE = 3;
    public static final int OPT_HUD = 4;
    public static final int OPT_ENHANCED_AUDIO = 5;
    public static final int OPT_MSU_VOLUME = 6;
    public static final int OPT_SFX_VOLUME = 7;

    // SNES pad bits (libretro joypad ids)
    public static final int BTN_B = 1, BTN_Y = 1 << 1, BTN_SELECT = 1 << 2, BTN_START = 1 << 3;
    public static final int BTN_UP = 1 << 4, BTN_DOWN = 1 << 5, BTN_LEFT = 1 << 6, BTN_RIGHT = 1 << 7;
    public static final int BTN_A = 1 << 8, BTN_X = 1 << 9, BTN_L = 1 << 10, BTN_R = 1 << 11;

    private NativeBridge() {}

    /** gameDir: imported game folder; gains: MSU-1 Enhanced gain per track (dB). */
    public static native boolean nativeInit(AssetManager assets, byte[] rom, String chaptersTxt,
                                            String saveDir, String overrideDir, String gameDir, float[] gains);

    // Scene selector generation after importing the game (states + thumbnails)
    public static native boolean nativeGenBegin(byte[] rom, String chaptersTxt, String gameDir, String saveDir, String outDir);
    /** Returns scenes saved so far, or -1 when finished. */
    public static native int nativeGenRun(int frames, int maxFrames, int expected);
    public static native int nativeGenFrame();
    public static native void nativeGenEnd();
    public static native void nativeShutdown();
    public static native int nativeRunFrame(short[] audioOut);
    public static native void nativeSetButtons(int mask);
    public static native void nativeSetPaused(boolean paused);
    public static native void nativeSetOption(int id, float value);
    public static native byte[] nativeSaveState();
    public static native boolean nativeLoadState(byte[] state);
    public static native void nativeSaveSram();
    public static native int nativeCopyFrame(ByteBuffer dst, int[] info);
    /** info = {width, height, hdOverlay, track, audioSample, keyColorRgb565}. */
    // HD video add-on
    public static native void nativeSetHdOverlay(boolean on);
    public static native void nativeSetHdChapters(byte[] mask);
    /** out = {score, lives}; false when there is no player on screen. */
    public static native boolean nativePlayerStats(int[] out);
    /** Buttons the game is getting this frame (pad + automatic QTE). */
    public static native int nativeEffectiveButtons();
    /** Prompts the game wants at that point (bit 0 left, 1 right, 2 brake, 3 accelerate). */
    public static native int nativePromptsAt(int track, int audioSample);
    public static native int nativeCurrentChapter();
    public static native int nativeVideoFrame();
    public static native int nativeRumble();
    public static native int nativeQteFeedback();
    public static native double nativeFps();
}
