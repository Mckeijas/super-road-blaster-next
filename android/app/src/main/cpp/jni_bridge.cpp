// JNI bridge between the Java app (com.srbport.roadblaster.NativeBridge) and srb_core.
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "srb_core.h"

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

JNIFN(jboolean, nativeInit)(JNIEnv *env, jclass, jobject assetManager, jbyteArray rom,
                            jstring chapters, jstring saveDir, jstring overrideDir) {
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
    cfg.romPath = cfg.saveDir + "/SuperRoadBlaster.sfc";
    cfg.opener = openAsset;
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

JNIFN(void, nativeSaveSram)(JNIEnv *, jclass) { srb::saveSram(); }

// Copies the newest frame (RGB565) into a direct ByteBuffer.
// info[0] = width, info[1] = height. Returns 1 if a new frame was copied.
JNIFN(jint, nativeCopyFrame)(JNIEnv *env, jclass, jobject directBuffer, jintArray info) {
    if (!srb::copyFrame(gFrame)) return 0;
    void *dst = env->GetDirectBufferAddress(directBuffer);
    jlong cap = env->GetDirectBufferCapacity(directBuffer);
    size_t bytes = gFrame.pixels.size() * 2;
    if (!dst || (jlong)bytes > cap) return 0;
    memcpy(dst, gFrame.pixels.data(), bytes);
    jint wh[2] = {gFrame.width, gFrame.height};
    env->SetIntArrayRegion(info, 0, 2, wh);
    return 1;
}

JNIFN(jint, nativeCurrentChapter)(JNIEnv *, jclass) { return srb::currentChapter(); }
JNIFN(jint, nativeVideoFrame)(JNIEnv *, jclass) { return srb::currentVideoFrame(); }
JNIFN(jint, nativeRumble)(JNIEnv *, jclass) { return srb::pendingRumble(); }
JNIFN(jint, nativeQteFeedback)(JNIEnv *, jclass) { return srb::lastQteFeedback(); }
JNIFN(jdouble, nativeFps)(JNIEnv *, jclass) { return srb::fps(); }
