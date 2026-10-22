// JNI bridge between the Java app (com.srbport.roadblaster.NativeBridge) and srb_core.
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <set>
#include <sys/stat.h>
#include <string>
#include <vector>

#include "srb_core.h"
#include "scene_gen.h"

#define LOG_TAG "SRBPort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
AAssetManager *gAssets = nullptr;
jobject gAssetsRef = nullptr; // keeps the Java AssetManager alive
srb::Frame gFrame;

// Serves files from the APK. They are stored uncompressed (see build.gradle
// noCompress), so the kernel can read them straight from the APK file.
int openAsset(const char *name, int64_t *offset, int64_t *length) {
    if (!gAssets) return -1;
    std::string path = std::string("game/") + name;
    AAsset *a = AAssetManager_open(gAssets, path.c_str(), AASSET_MODE_RANDOM);
    if (!a) return -1;
    off64_t start = 0, len = 0;
    int fd = AAsset_openFileDescriptor64(a, &start, &len);
    AAsset_close(a);
    if (fd < 0) {
        LOGI("asset %s is compressed or missing", path.c_str());
        return -1;
    }
    *offset = start;
    *length = len;
    return fd;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

enum Option {
    OPT_AUTO_QTE = 0,
    OPT_INFINITE_LIVES = 1,
    OPT_SPEED = 2,
    OPT_MOVIE_MODE = 3,
    OPT_HUD = 4,
    OPT_ENHANCED_AUDIO = 5,
    OPT_MSU_VOLUME = 6,
    OPT_SFX_VOLUME = 7,
};
float gMsuVol = 1.0f, gSfxVol = 1.0f;
} // namespace

#define JNIFN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_srbport_roadblaster_NativeBridge_##name

// gameDir: folder with the imported game (SuperRoadBlaster.sfc / .msu / -N.pcm).
// gains: MSU-1 Enhanced loudness gain per track in dB (may be null).
JNIFN(jboolean, nativeInit)(JNIEnv *env, jclass, jobject assetManager, jbyteArray rom,
                            jstring chapters, jstring saveDir, jstring overrideDir,
                            jstring gameDir, jfloatArray gains) {
    if (gAssetsRef) env->DeleteGlobalRef(gAssetsRef);
    gAssetsRef = env->NewGlobalRef(assetManager);
    gAssets = AAssetManager_fromJava(env, gAssetsRef);

    srb::Config cfg;
    jsize n = env->GetArrayLength(rom);
    cfg.rom.resize((size_t)n);
    env->GetByteArrayRegion(rom, 0, n, (jbyte *)cfg.rom.data());
    cfg.chaptersTxt = jstr(env, chapters);
    cfg.saveDir = jstr(env, saveDir);
    cfg.overrideDir = jstr(env, overrideDir);
    std::string gd = jstr(env, gameDir);
    if (!gd.empty()) {               // imported game: plain files next to the ROM
        cfg.romPath = gd + "/SuperRoadBlaster.sfc";
        cfg.opener = nullptr;
    } else {                         // game packed inside the APK (old builds)
        cfg.romPath = cfg.saveDir + "/SuperRoadBlaster.sfc";
        cfg.opener = openAsset;
    }
    if (gains) {
        jsize n = env->GetArrayLength(gains);
        cfg.enhancedGainDb.resize((size_t)n);
        env->GetFloatArrayRegion(gains, 0, n, cfg.enhancedGainDb.data());
    }
    gFrame = srb::Frame();
    bool ok = srb::init(cfg);
    LOGI("init %s", ok ? "ok" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIFN(void, nativeShutdown)(JNIEnv *env, jclass) {
    srb::shutdown();
    if (gAssetsRef) { env->DeleteGlobalRef(gAssetsRef); gAssetsRef = nullptr; }
    gAssets = nullptr;
}

JNIFN(jint, nativeRunFrame)(JNIEnv *env, jclass, jshortArray audio) {
    jsize cap = env->GetArrayLength(audio) / 2;
    jshort *buf = env->GetShortArrayElements(audio, nullptr);
    int frames = srb::runFrame((int16_t *)buf, cap);
    env->ReleaseShortArrayElements(audio, buf, 0);
    return frames;
}

JNIFN(void, nativeSetButtons)(JNIEnv *, jclass, jint mask) { srb::setButtons((uint16_t)mask); }
JNIFN(void, nativeSetPaused)(JNIEnv *, jclass, jboolean p) { srb::setPaused(p == JNI_TRUE); }

JNIFN(void, nativeSetOption)(JNIEnv *, jclass, jint id, jfloat v) {
    switch (id) {
    case OPT_AUTO_QTE: srb::setAutoQte((int)v); break;
    case OPT_INFINITE_LIVES: srb::setInfiniteLives(v != 0.0f); break;
    case OPT_SPEED: srb::setSpeed(v); break;
    case OPT_MOVIE_MODE: srb::setMovieMode(v != 0.0f); break;
    case OPT_HUD: srb::setHudVisible(v != 0.0f); break;
    case OPT_ENHANCED_AUDIO: srb::setEnhancedAudio(v != 0.0f); break;
    case OPT_MSU_VOLUME: gMsuVol = v; srb::setVolumes(gMsuVol, gSfxVol); break;
    case OPT_SFX_VOLUME: gSfxVol = v; srb::setVolumes(gMsuVol, gSfxVol); break;
    default: break;
    }
}

JNIFN(jbyteArray, nativeSaveState)(JNIEnv *env, jclass) {
    std::vector<uint8_t> st;
    if (!srb::saveState(st)) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)st.size());
    env->SetByteArrayRegion(arr, 0, (jsize)st.size(), (const jbyte *)st.data());
    return arr;
}

JNIFN(jboolean, nativeLoadState)(JNIEnv *env, jclass, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> st((size_t)n);
    env->GetByteArrayRegion(data, 0, n, (jbyte *)st.data());
    return srb::loadState(st) ? JNI_TRUE : JNI_FALSE;
}

// "Continuar": the same game, keeps its clean-run status
JNIFN(jboolean, nativeResumeState)(JNIEnv *env, jclass, jbyteArray data) {
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> st((size_t)n);
    env->GetByteArrayRegion(data, 0, n, (jbyte *)st.data());
    return srb::loadState(st, true) ? JNI_TRUE : JNI_FALSE;
}

// "NAM 724" when a clean run has just made its record, else null
JNIFN(jstring, nativeTakeNewMark)(JNIEnv *env, jclass) {
    std::string name;
    int score = 0;
    if (!srb::takeNewMark(name, score)) return nullptr;
    return env->NewStringUTF((name + " " + std::to_string(score)).c_str());
}

JNIFN(void, nativeSaveSram)(JNIEnv *, jclass) { srb::saveSram(); }

// Copies the newest frame (RGB565) into a direct ByteBuffer.
// info = {width, height, overlay (HD add-on: HUD only), track, MSU-1 audio sample, key colour}.
// Returns 1 if a new frame was copied.
JNIFN(jint, nativeCopyFrame)(JNIEnv *env, jclass, jobject directBuffer, jintArray info) {
    if (!srb::copyFrame(gFrame)) return 0;
    void *dst = env->GetDirectBufferAddress(directBuffer);
    jlong cap = env->GetDirectBufferCapacity(directBuffer);
    size_t bytes = gFrame.pixels.size() * 2;
    if (!dst || (jlong)bytes > cap) return 0;
    memcpy(dst, gFrame.pixels.data(), bytes);
    jint meta[6] = {gFrame.width, gFrame.height, gFrame.overlay ? 1 : 0, gFrame.track, gFrame.audioPos, gFrame.key};
    env->SetIntArrayRegion(info, 0, std::min<jsize>(6, env->GetArrayLength(info)), meta);
    return 1;
}

// --- HD video add-on -------------------------------------------------------------------
JNIFN(void, nativeSetHdOverlay)(JNIEnv *, jclass, jboolean on) { srb::setHdOverlay(on == JNI_TRUE); }
JNIFN(void, nativeSetHdChapters)(JNIEnv *env, jclass, jbyteArray mask) {
    std::vector<uint8_t> m((size_t)env->GetArrayLength(mask));
    env->GetByteArrayRegion(mask, 0, (jsize)m.size(), (jbyte *)m.data());
    srb::setHdChapters(m);
}
// out = {score, lives}; returns false if the player object is not alive (title, menus...)
JNIFN(jboolean, nativePlayerStats)(JNIEnv *env, jclass, jintArray out) {
    int sc = 0, lv = 0;
    if (!srb::playerStats(&sc, &lv)) return JNI_FALSE;
    jint v[2] = {sc, lv};
    env->SetIntArrayRegion(out, 0, 2, v);
    return JNI_TRUE;
}
JNIFN(jint, nativeEffectiveButtons)(JNIEnv *, jclass) { return srb::effectiveButtons(); }
JNIFN(jint, nativePromptsAt)(JNIEnv *, jclass, jint track, jint sample) { return srb::promptsAt(track, sample); }

JNIFN(jint, nativeCurrentChapter)(JNIEnv *, jclass) { return srb::currentChapter(); }
JNIFN(jint, nativeVideoFrame)(JNIEnv *, jclass) { return srb::currentVideoFrame(); }
JNIFN(jint, nativeRumble)(JNIEnv *, jclass) { return srb::pendingRumble(); }
JNIFN(jint, nativeQteFeedback)(JNIEnv *, jclass) { return srb::lastQteFeedback(); }
JNIFN(jdouble, nativeFps)(JNIEnv *, jclass) { return srb::fps(); }


// Scene generation after importing the game (see srb/scene_gen.h).
JNIFN(jboolean, nativeGenBegin)(JNIEnv *env, jclass, jbyteArray rom, jstring chapters, jstring gameDir,
                                jstring saveDir, jstring outDir) {
    std::vector<uint8_t> r((size_t)env->GetArrayLength(rom));
    env->GetByteArrayRegion(rom, 0, (jsize)r.size(), (jbyte *)r.data());
    return srb::genBegin(r, jstr(env, chapters), jstr(env, gameDir), jstr(env, saveDir), jstr(env, outDir))
               ? JNI_TRUE : JNI_FALSE;
}
JNIFN(jint, nativeGenRun)(JNIEnv *, jclass, jint frames, jint maxFrames, jint expected) {
    return srb::genRun(frames, maxFrames, expected);
}
JNIFN(jint, nativeGenFrame)(JNIEnv *, jclass) { return srb::genFrame(); }
JNIFN(void, nativeGenEnd)(JNIEnv *, jclass) { srb::genEnd(); }
