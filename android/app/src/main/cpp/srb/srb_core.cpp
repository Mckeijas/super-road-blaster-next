// Super Road Blaster Android port - portable frontend core (see srb_core.h).
#include "srb_core.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#include "libretro.h"
// Snes9x internals (statically linked) for MSU-1 state, WRAM, ROM and PPU layers.
#include "snes9x.h"
#include "memmap.h"
#include "msu1.h"
#include "stream.h"
#include "apu/apu.h"

extern "C" {
#include "sonic.h"
}
#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#include "dr_flac.h"

namespace srb {
namespace {

constexpr double kVideoFps = 23.9777;   // MSU-1 video rate used by the game tools
constexpr int kSampleRate = 48000;

// ---------------------------------------------------------------------------
// fd + offset backed stream, buffered (MSU-1 data is read byte by byte).
class FdStream : public Stream {
public:
    FdStream(int fd, int64_t base, int64_t length, bool ownsFd)
        : fd_(fd), base_(base), length_(length), owns_(ownsFd) {}
    ~FdStream() override { if (owns_ && fd_ >= 0) ::close(fd_); }

    int get_char() override {
        if (bufPos_ >= bufLen_ && !fill()) return EOF;
        return (unsigned char)buf_[bufPos_++];
    }
    char *gets(char *dst, size_t len) override {
        if (len == 0) return nullptr;
        size_t i = 0;
        while (i + 1 < len) {
            int c = get_char();
            if (c == EOF) break;
            dst[i++] = (char)c;
            if (c == '\n') break;
        }
        dst[i] = 0;
        return i ? dst : nullptr;
    }
    size_t read(void *dst, size_t len) override {
        uint8_t *d = (uint8_t *)dst;
        size_t done = 0;
        while (done < len) {
            if (bufPos_ >= bufLen_ && !fill()) break;
            size_t n = std::min(len - done, bufLen_ - bufPos_);
            memcpy(d + done, buf_ + bufPos_, n);
            bufPos_ += n;
            done += n;
        }
        return done;
    }
    size_t write(void *, size_t) override { return 0; }
    size_t pos() override { return (size_t)(bufStart_ + (int64_t)bufPos_); }
    size_t size() override { return (size_t)length_; }
    int revert(uint8 origin, int32 offset) override {
        size_t p = pos_from_origin_offset(origin, offset);
        if ((int64_t)p > length_) return -1;
        if ((int64_t)p >= bufStart_ && (int64_t)p < bufStart_ + (int64_t)bufLen_) {
            bufPos_ = (size_t)((int64_t)p - bufStart_);
        } else {
            bufStart_ = (int64_t)p;
            bufLen_ = bufPos_ = 0;
        }
        return 0;
    }
    void closeStream() override { delete this; }

private:
    bool fill() {
        bufStart_ += (int64_t)bufLen_;
        bufLen_ = bufPos_ = 0;
        int64_t remain = length_ - bufStart_;
        if (remain <= 0) return false;
        size_t want = (size_t)std::min<int64_t>(remain, sizeof(buf_));
        ssize_t got = ::pread(fd_, buf_, want, (off_t)(base_ + bufStart_));
        if (got <= 0) return false;
        bufLen_ = (size_t)got;
        return true;
    }
    int fd_;
    int64_t base_, length_;
    bool owns_;
    char buf_[64 * 1024];
    int64_t bufStart_ = 0;
    size_t bufLen_ = 0, bufPos_ = 0;
};

// ---------------------------------------------------------------------------
// FLAC track presented to Snes9x as an MSU-1 .pcm stream ("MSU1" + loop + s16le).
class FlacStream : public Stream {
public:
    static FlacStream *open(int fd, int64_t base, int64_t length, uint32_t loop) {
        // Skip ID3v2 tags (added by some tools / provenance metadata) before "fLaC".
        for (int guard = 0; guard < 4; ++guard) {
            uint8_t h[10];
            if (length < 10 || ::pread(fd, h, 10, (off_t)base) != 10) break;
            if (h[0] != 'I' || h[1] != 'D' || h[2] != '3') break;
            int64_t sz = ((int64_t)(h[6] & 0x7f) << 21) | ((h[7] & 0x7f) << 14) | ((h[8] & 0x7f) << 7) | (h[9] & 0x7f);
            int64_t skip = 10 + sz + ((h[5] & 0x10) ? 10 : 0);
            if (skip >= length) break;
            base += skip;
            length -= skip;
        }
        auto *s = new FlacStream(fd, base, length, loop);
        s->flac_ = drflac_open(&FlacStream::onRead, &FlacStream::onSeek, &FlacStream::onTell, s, nullptr);
        if (!s->flac_ || s->flac_->channels != 2) { delete s; return nullptr; }
        s->total_ = 8 + (int64_t)s->flac_->totalPCMFrameCount * 4;
        return s;
    }
    ~FlacStream() override {
        if (flac_) drflac_close(flac_);
        if (fd_ >= 0) ::close(fd_);
    }
    int get_char() override {
        uint8_t c;
        return read(&c, 1) == 1 ? c : EOF;
    }
    char *gets(char *, size_t) override { return nullptr; }
    size_t read(void *dst, size_t len) override {
        uint8_t *d = (uint8_t *)dst;
        size_t done = 0;
        while (done < len && pos_ < total_) {
            if (pos_ < 8) {
                uint8_t hdr[8] = {'M', 'S', 'U', '1', (uint8_t)loop_, (uint8_t)(loop_ >> 8),
                                  (uint8_t)(loop_ >> 16), (uint8_t)(loop_ >> 24)};
                d[done++] = hdr[pos_++];
                continue;
            }
            int64_t sample = (pos_ - 8);
            if (sample < bufStart_ || sample >= bufStart_ + (int64_t)bufBytes_) {
                // refill: decode from the frame that contains pos_
                int64_t frame = sample / 4;
                if (frame != decodeFrame_) {
                    if (!drflac_seek_to_pcm_frame(flac_, (drflac_uint64)frame)) return done;
                    decodeFrame_ = frame;
                }
                drflac_uint64 got = drflac_read_pcm_frames_s16(flac_, kFrames, buf_);
                if (got == 0) return done;
                bufStart_ = frame * 4;
                bufBytes_ = (size_t)got * 4;
                decodeFrame_ += (int64_t)got;
            }
            size_t off = (size_t)(sample - bufStart_);
            size_t n = std::min(len - done, bufBytes_ - off);
            memcpy(d + done, (uint8_t *)buf_ + off, n);
            done += n;
            pos_ += (int64_t)n;
        }
        return done;
    }
    size_t write(void *, size_t) override { return 0; }
    size_t pos() override { return (size_t)pos_; }
    size_t size() override { return (size_t)total_; }
    int revert(uint8 origin, int32 offset) override {
        size_t p = pos_from_origin_offset(origin, offset);
        if ((int64_t)p > total_) return -1;
        pos_ = (int64_t)p;
        return 0;
    }
    void closeStream() override { delete this; }

private:
    static constexpr int kFrames = 4096;
    FlacStream(int fd, int64_t base, int64_t length, uint32_t loop)
        : fd_(fd), base_(base), length_(length), loop_(loop) {}
    static size_t onRead(void *u, void *out, size_t n) {
        auto *s = (FlacStream *)u;
        int64_t remain = s->length_ - s->cursor_;
        if (remain <= 0) return 0;
        n = (size_t)std::min<int64_t>((int64_t)n, remain);
        ssize_t got = ::pread(s->fd_, out, n, (off_t)(s->base_ + s->cursor_));
        if (got <= 0) return 0;
        s->cursor_ += got;
        return (size_t)got;
    }
    static drflac_bool32 onSeek(void *u, int offset, drflac_seek_origin origin) {
        auto *s = (FlacStream *)u;
        int64_t np = origin == DRFLAC_SEEK_SET ? offset : origin == DRFLAC_SEEK_CUR ? s->cursor_ + offset : s->length_ + offset;
        if (np < 0 || np > s->length_) return DRFLAC_FALSE;
        s->cursor_ = np;
        return DRFLAC_TRUE;
    }
    static drflac_bool32 onTell(void *u, drflac_int64 *cur) {
        *cur = ((FlacStream *)u)->cursor_;
        return DRFLAC_TRUE;
    }
    int fd_;
    int64_t base_, length_, cursor_ = 0;
    uint32_t loop_;
    drflac *flac_ = nullptr;
    int64_t total_ = 0, pos_ = 0;
    int16_t buf_[kFrames * 2];
    int64_t bufStart_ = -1, decodeFrame_ = 0;
    size_t bufBytes_ = 0;
};

// ---------------------------------------------------------------------------
// MSU-1 Enhanced, processed live on top of the original .pcm track: loudness gain per
// track, the same gentle EQ as the old offline remaster and a peak limiter. Nothing is
// stored: the imported game files are the only audio data on the device.
class EnhancedStream : public Stream {
public:
    EnhancedStream(Stream *raw, float gainDb) : raw_(raw) {
        gain_ = std::pow(10.0f, gainDb / 20.0f);
        const float fs = 44100.0f;
        setPeak(eq_[0], 70.0f, 0.8f, 1.5f, fs);
        setPeak(eq_[1], 3200.0f, 1.2f, 1.0f, fs);
        setPeak(eq_[2], 9500.0f, 1.0f, 1.2f, fs);
        release_ = 1.0f - std::exp(-1.0f / (0.040f * fs));
        size_ = raw_->size();
    }
    ~EnhancedStream() override { if (raw_) raw_->closeStream(); }

    int get_char() override {
        uint8_t c;
        return read(&c, 1) == 1 ? c : EOF;
    }
    char *gets(char *, size_t) override { return nullptr; }
    size_t read(void *dst, size_t len) override {
        uint8_t *d = (uint8_t *)dst;
        size_t done = 0;
        while (done < len && pos_ < size_) {
            if (pos_ < 8) {                               // "MSU1" + loop point: untouched
                raw_->revert(0, (int32)pos_);
                uint8_t c;
                if (raw_->read(&c, 1) != 1) break;
                d[done++] = c;
                pos_++;
                continue;
            }
            int64_t block = (int64_t)(pos_ - 8) / kBlockBytes;
            if (block != cached_ && !process(block)) break;
            size_t off = (size_t)((int64_t)(pos_ - 8) - block * kBlockBytes);
            size_t n = std::min(len - done, cachedBytes_ - off);
            if (n == 0) break;
            memcpy(d + done, (uint8_t *)buf_ + off, n);
            done += n;
            pos_ += n;
        }
        return done;
    }
    size_t write(void *, size_t) override { return 0; }
    size_t pos() override { return pos_; }
    size_t size() override { return size_; }
    int revert(uint8 origin, int32 offset) override {
        size_t p = pos_from_origin_offset(origin, offset);
        if (p > size_) return -1;
        pos_ = p;
        return 0;
    }
    void closeStream() override { delete this; }

private:
    static constexpr int kBlockFrames = 4096;
    static constexpr int64_t kBlockBytes = kBlockFrames * 4;
    struct Biquad { float b0, b1, b2, a1, a2; float z[2][2]; };

    static void setPeak(Biquad &q, float f, float Q, float gainDb, float fs) {
        float A = std::pow(10.0f, gainDb / 40.0f), w0 = 2.0f * 3.14159265f * f / fs;
        float alpha = std::sin(w0) / (2.0f * Q), c = std::cos(w0);
        float a0 = 1.0f + alpha / A;
        q.b0 = (1.0f + alpha * A) / a0; q.b1 = -2.0f * c / a0; q.b2 = (1.0f - alpha * A) / a0;
        q.a1 = -2.0f * c / a0; q.a2 = (1.0f - alpha / A) / a0;
        memset(q.z, 0, sizeof q.z);
    }
    void resetState() {
        for (Biquad &q : eq_) memset(q.z, 0, sizeof q.z);
        env_ = 1.0f;
    }
    bool readBlock(int64_t block, int16_t *out, size_t *bytes) {
        if (raw_->revert(0, (int32)(8 + block * kBlockBytes)) != 0) return false;
        *bytes = raw_->read(out, kBlockBytes) & ~(size_t)3;
        return *bytes > 0;
    }
    void filter(int16_t *smp, size_t frames) {
        const float limit = 0.891f * 32767.0f;            // -1 dBFS
        for (size_t i = 0; i < frames; ++i) {
            float v[2];
            for (int ch = 0; ch < 2; ++ch) {
                float x = smp[i * 2 + ch] * gain_;
                for (Biquad &q : eq_) {                     // transposed direct form II
                    float y = q.b0 * x + q.z[ch][0];
                    q.z[ch][0] = q.b1 * x - q.a1 * y + q.z[ch][1];
                    q.z[ch][1] = q.b2 * x - q.a2 * y;
                    x = y;
                }
                v[ch] = x;
            }
            float peak = std::max(std::fabs(v[0]), std::fabs(v[1]));
            if (peak * env_ > limit) env_ = limit / peak;       // instant attack
            else env_ += (1.0f - env_) * release_;            // 40 ms release
            for (int ch = 0; ch < 2; ++ch) {
                float y = v[ch] * env_;
                smp[i * 2 + ch] = (int16_t)std::max(-32768.0f, std::min(32767.0f, y));
            }
        }
    }
    bool process(int64_t block) {
        if (block != cached_ + 1) {                        // seek: settle filters on the block before
            resetState();
            size_t b = 0;
            if (block > 0 && readBlock(block - 1, buf_, &b)) filter(buf_, b / 4);
        }
        size_t bytes = 0;
        if (!readBlock(block, buf_, &bytes)) return false;
        filter(buf_, bytes / 4);
        cached_ = block;
        cachedBytes_ = bytes;
        return true;
    }

    Stream *raw_;
    float gain_ = 1.0f, env_ = 1.0f, release_ = 0.001f;
    Biquad eq_[3];
    size_t size_ = 0, pos_ = 0;
    int16_t buf_[kBlockFrames * 2];
    int64_t cached_ = -2;
    size_t cachedBytes_ = 0;
};

// ---------------------------------------------------------------------------
struct State {
    Config cfg;
    std::vector<Chapter> chapters;

    std::atomic<uint16_t> padMask{0};
    uint16_t lastPad = 0;
    std::atomic<bool> paused{false};
    std::atomic<int> autoQte{QTE_OFF};
    std::atomic<bool> infiniteLives{false};
    std::atomic<bool> movieMode{false};
    std::atomic<bool> hudVisible{true};
    std::atomic<float> speed{1.0f};
    std::atomic<bool> enhancedAudio{false};

    uint16_t injected = 0, blocked = 0;
    int framesSinceInject = 9999;

    // Video
    std::mutex frameMutex;
    Frame frame;

    // Audio
    std::vector<int16_t> audio;    // raw core output for the current frame
    sonicStream sonic = nullptr;
    float sonicSpeed = 1.0f;
    double fps = 50.0;

    // Chapter tracking
    int lastTrack = -1;
    std::atomic<uint32_t> chapterCounter{0};
    std::atomic<int> rumble{0};
    int lastRumbleFrame = -1;

    // Infinite-lives ROM patch
    size_t loseLifeDec = 0;        // offset of "DEC dp" inside the ROM, 0 = not found
    uint8_t loseLifeOrig[2] = {0, 0};
    bool patched = false;

    std::string sramPath;
    bool loaded = false;
};

State *g = nullptr;

void logf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

void retroLog(enum retro_log_level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

std::string sysDir;

bool envCb(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = sysDir.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(const enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = retroLog;
        return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto *v = (struct retro_variable *)data;
        // Keep the original look: no overscan, accurate blending.
        if (v && v->key && !strcmp(v->key, "snes9x_overscan")) { v->value = "disabled"; return true; }
        return false;
    }
    default:
        return false;
    }
}

void videoCb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g) return; // dupe frame
    std::lock_guard<std::mutex> lk(g->frameMutex);
    g->frame.width = (int)w;
    g->frame.height = (int)h;
    g->frame.pixels.resize((size_t)w * h);
    const uint8_t *src = (const uint8_t *)data;
    for (unsigned y = 0; y < h; ++y)
        memcpy(&g->frame.pixels[(size_t)y * w], src + y * pitch, w * 2);
    g->frame.serial++;
}

void audioSampleCb(int16_t l, int16_t r) {
    g->audio.push_back(l);
    g->audio.push_back(r);
}

size_t audioBatchCb(const int16_t *data, size_t frames) {
    g->audio.insert(g->audio.end(), data, data + frames * 2);
    return frames;
}

void inputPollCb() {}

uint16_t effectiveMask() {
    uint16_t pad = g->padMask.load();
    if (g->movieMode.load()) pad &= (BTN_START | BTN_SELECT); // pure playback
    return (uint16_t)((pad & ~g->blocked) | g->injected);
}

int16_t inputStateCb(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    uint16_t m = effectiveMask();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)m;
    return (id < 16 && (m & (1u << id))) ? 1 : 0;
}

// ---------------------------------------------------------------------------
bool openPlain(const std::string &path, int *fd, int64_t *off, int64_t *len);

// Finds a game file: custom opener (APK assets) or next to the ROM (desktop).
bool findFile(const std::string &name, int *fd, int64_t *off, int64_t *len) {
    if (g->cfg.opener) {
        *fd = g->cfg.opener(name.c_str(), off, len);
        return *fd >= 0;
    }
    std::string dir = g->cfg.romPath;
    size_t slash = dir.find_last_of('/');
    dir = slash == std::string::npos ? std::string(".") : dir.substr(0, slash);
    return openPlain(dir + "/" + name, fd, off, len);
}

bool openPlain(const std::string &path, int *fd, int64_t *off, int64_t *len) {
    *fd = ::open(path.c_str(), O_RDONLY);
    if (*fd < 0) return false;
    struct stat st{};
    if (fstat(*fd, &st) != 0 || st.st_size <= 8) { ::close(*fd); *fd = -1; return false; }
    *off = 0;
    *len = st.st_size;
    return true;
}

STREAM openGameFile(const char *ext) {
    if (!g) return nullptr;
    std::string name = std::string("SuperRoadBlaster") + ext; // ".msu" or "-12.pcm"
    int fd = -1;
    int64_t off = 0, len = 0;
    bool isPcm = name.size() > 4 && name.compare(name.size() - 4, 4, ".pcm") == 0;
    if (!isPcm) return findFile(name, &fd, &off, &len) ? new FdStream(fd, off, len, true) : nullptr;

    std::string base = name.substr(0, name.size() - 4);
    // 1) alternative soundtrack folder: .pcm (MSU-1 format) or .flac
    if (!g->cfg.overrideDir.empty()) {
        if (openPlain(g->cfg.overrideDir + "/" + name, &fd, &off, &len)) {
            logf("[srb] override %s\n", name.c_str());
            return new FdStream(fd, off, len, true);
        }
        if (openPlain(g->cfg.overrideDir + "/" + base + ".flac", &fd, &off, &len)) {
            if (FlacStream *s = FlacStream::open(fd, off, len, 0)) return s;
        }
    }
    // 2) original track, optionally through the live MSU-1 Enhanced processing
    if (!findFile(name, &fd, &off, &len)) return nullptr;
    Stream *raw = new FdStream(fd, off, len, true);
    if (g->enhancedAudio.load()) {
        int track = atoi(base.substr(base.find_last_of('-') + 1).c_str());
        float gain = track >= 0 && track < (int)g->cfg.enhancedGainDb.size() ? g->cfg.enhancedGainDb[track] : 0.0f;
        return new EnhancedStream(raw, gain);
    }
    return raw;
}

uint16_t buttonFor(const std::string &type) {
    if (type == "direction-left") return BTN_LEFT;
    if (type == "direction-right") return BTN_RIGHT;
    if (type == "accelerate") return BTN_A;
    if (type == "brake") return BTN_B;
    return 0;
}

uint16_t opposite(uint16_t b) {
    switch (b) {
    case BTN_LEFT: return BTN_RIGHT;
    case BTN_RIGHT: return BTN_LEFT;
    case BTN_A: return BTN_B;
    case BTN_B: return BTN_A;
    default: return 0;
    }
}

void parseChapters(const std::string &txt) {
    // Format (tools/parse_events.py):
    //   C <id> <level> <durationMs> <name>
    //   E <type> <start> <end>
    //   R <frame>
    std::istringstream in(txt);
    std::string line;
    g->chapters.clear();
    int cur = -1;
    while (std::getline(in, line)) {
        std::istringstream ls(line);
        std::string tag;
        ls >> tag;
        if (tag == "C") {
            Chapter c;
            ls >> c.id >> c.level >> c.durationMs >> c.name;
            if ((int)g->chapters.size() <= c.id) g->chapters.resize(c.id + 1);
            g->chapters[c.id] = c;
            cur = c.id;
        } else if (tag == "E" && cur >= 0) {
            QteEvent e;
            ls >> e.type >> e.start >> e.end;
            e.button = buttonFor(e.type);
            if (e.button) g->chapters[cur].events.push_back(e);
        } else if (tag == "R" && cur >= 0) {
            int f = 0;
            ls >> f;
            g->chapters[cur].rumbleFrames.push_back(f);
        }
    }
}

// Player.loseLife:  sep #$20 / lda dp / bne + / sec / rts / + dec dp / clc / rts
size_t findLoseLife() {
    const uint8_t *rom = Memory.ROM;
    size_t n = Memory.CalculatedSize;
    for (size_t i = 0; i + 12 <= n; ++i) {
        if (rom[i] == 0xE2 && rom[i + 1] == 0x20 && rom[i + 2] == 0xA5 && rom[i + 4] == 0xD0 &&
            rom[i + 5] == 0x02 && rom[i + 6] == 0x38 && rom[i + 7] == 0x60 && rom[i + 8] == 0xC6 &&
            rom[i + 9] == rom[i + 3] && rom[i + 10] == 0x18 && rom[i + 11] == 0x60)
            return i + 8;
    }
    return 0;
}

void applyLivesPatch() {
    bool want = g->infiniteLives.load() || g->movieMode.load();
    if (!g->loseLifeDec || want == g->patched) return;
    uint8_t *p = Memory.ROM + g->loseLifeDec;
    if (want) { p[0] = 0xEA; p[1] = 0xEA; }               // NOP NOP instead of DEC lifes
    else { p[0] = g->loseLifeOrig[0]; p[1] = g->loseLifeOrig[1]; }
    g->patched = want;
}

void applyLayers() {
    // Movie mode / HUD off: hide sprites (score, arrows, steering wheel) and BG3
    // (dashboard, text). Video lives on BG1/BG2.
    bool hide = g->movieMode.load() || !g->hudVisible.load();
    Settings.BG_Forced = hide ? (uint8)(0x10 | 0x04) : 0;
}

int trackNow() {
    if (!(MSU1.MSU1_STATUS & AudioPlaying)) return -1;
    return (int)MSU1.MSU1_CURRENT_TRACK;
}

int videoFrameNow() {
    if (trackNow() < 0) return -1;
    double secs = (double)((int64_t)MSU1.MSU1_AUDIO_POS - 8) / 4.0 / 44100.0;
    return (int)(secs * kVideoFps);
}

void directorTick() {
    g->injected = 0;
    g->blocked = 0;
    int track = trackNow();
    if (track != g->lastTrack) {
        if (track >= 0) g->chapterCounter++;
        g->lastTrack = track;
        g->lastRumbleFrame = -1;
    }
    if (track < 0 || track >= (int)g->chapters.size()) return;
    const Chapter &c = g->chapters[track];
    int f = videoFrameNow();

    for (int rf : c.rumbleFrames)
        if (rf > g->lastRumbleFrame && rf <= f) { g->rumble = 200; g->lastRumbleFrame = rf; }

    int mode = g->movieMode.load() ? QTE_FULL : g->autoQte.load();
    if (mode == QTE_OFF) return;
    for (const QteEvent &e : c.events) {
        if (f < e.start - 1 || f > e.end) continue;
        int len = std::max(1, e.end - e.start);
        int from, to;
        if (mode == QTE_FULL) { from = e.start + 1; to = e.start + 1 + std::max(2, std::min(6, len / 2)); }
        else { from = std::max(e.start + 1, e.end - 6); to = e.end - 1; }
        if (f >= from && f <= to) { g->injected |= e.button; g->framesSinceInject = 0; }
        g->blocked |= opposite(e.button);
    }
    g->blocked &= ~g->injected;
}

} // namespace

// ===========================================================================
bool init(const Config &cfg) {
    if (g) shutdown();
    g = new State();
    g->cfg = cfg;
    sysDir = cfg.saveDir.empty() ? std::string(".") : cfg.saveDir;
    parseChapters(cfg.chaptersTxt);
    S9xMSU1OpenOverride = openGameFile;

    retro_set_environment(envCb);
    retro_set_video_refresh(videoCb);
    retro_set_audio_sample(audioSampleCb);
    retro_set_audio_sample_batch(audioBatchCb);
    retro_set_input_poll(inputPollCb);
    retro_set_input_state(inputStateCb);
    retro_init();

    retro_game_info info{};
    info.path = cfg.romPath.empty() ? "/SuperRoadBlaster.sfc" : cfg.romPath.c_str();
    info.data = cfg.rom.data();
    info.size = cfg.rom.size();
    if (!retro_load_game(&info)) {
        logf("[srb] retro_load_game failed\n");
        return false;
    }
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_system_av_info av{};
    retro_get_system_av_info(&av);
    g->fps = av.timing.fps;

    // SRAM (high scores)
    g->sramPath = sysDir + "/SuperRoadBlaster.srm";
    if (void *sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)) {
        size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        if (FILE *f = fopen(g->sramPath.c_str(), "rb")) { fread(sram, 1, sz, f); fclose(f); }
    }

    g->loseLifeDec = findLoseLife();
    if (g->loseLifeDec) memcpy(g->loseLifeOrig, Memory.ROM + g->loseLifeDec, 2);
    logf("[srb] loaded: fps=%.3f chapters=%zu loseLife@%zx\n", g->fps, g->chapters.size(), g->loseLifeDec);

    g->sonic = sonicCreateStream(kSampleRate, 2);
    g->loaded = true;
    return true;
}

void shutdown() {
    if (!g) return;
    if (g->loaded) {
        saveSram();
        retro_unload_game();
        retro_deinit();
    }
    if (g->sonic) sonicDestroyStream(g->sonic);
    S9xMSU1OpenOverride = nullptr;
    delete g;
    g = nullptr;
}

int runFrame(int16_t *out, int maxFrames) {
    if (!g || !g->loaded || g->paused.load()) return 0;
    applyLivesPatch();
    applyLayers();
    directorTick();
    if (g->framesSinceInject < 9999) g->framesSinceInject++;

    g->audio.clear();
    retro_run();

    int frames = (int)(g->audio.size() / 2);
    float sp = g->speed.load();
    if (sp >= 0.999f && sp <= 1.001f) {
        if (g->sonicSpeed != 1.0f) { sonicFlushStream(g->sonic); g->sonicSpeed = 1.0f;
            int left = sonicSamplesAvailable(g->sonic);
            if (left > 0) { std::vector<int16_t> tmp(left * 2); sonicReadShortFromStream(g->sonic, tmp.data(), left); } }
        int n = std::min(frames, maxFrames);
        memcpy(out, g->audio.data(), (size_t)n * 4);
        return n;
    }
    if (sp != g->sonicSpeed) { sonicSetSpeed(g->sonic, sp); g->sonicSpeed = sp; }
    sonicWriteShortToStream(g->sonic, g->audio.data(), frames);
    return sonicReadShortFromStream(g->sonic, out, maxFrames);
}

void setButtons(uint16_t mask) { if (g) g->padMask = mask; }
void setPaused(bool p) { if (g) g->paused = p; }
void setAutoQte(int mode) { if (g) g->autoQte = mode; }
void setInfiniteLives(bool on) { if (g) g->infiniteLives = on; }
void setSpeed(float s) { if (g) g->speed = std::max(0.2f, std::min(2.0f, s)); }
void setMovieMode(bool on) { if (g) g->movieMode = on; }
void setHudVisible(bool on) { if (g) g->hudVisible = on; }
void setEnhancedAudio(bool on) { if (g) g->enhancedAudio = on; }
void setVolumes(float msu, float sfx) {
    S9xSRBMsuGain = std::max(0.0f, std::min(2.0f, msu));
    S9xSRBSfxGain = std::max(0.0f, std::min(2.0f, sfx));
}

bool saveState(std::vector<uint8_t> &out) {
    if (!g || !g->loaded) return false;
    // never store the patched ROM state as part of the gameplay state
    out.resize(retro_serialize_size());
    return retro_serialize(out.data(), out.size());
}

bool loadState(const std::vector<uint8_t> &in) {
    if (!g || !g->loaded) return false;
    bool ok = retro_unserialize(in.data(), in.size());
    g->lastTrack = -2; // force a chapter change notification
    if (g->sonic) { sonicFlushStream(g->sonic); }
    return ok;
}

void saveSram() {
    if (!g || !g->loaded) return;
    void *sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || !sz) return;
    if (FILE *f = fopen(g->sramPath.c_str(), "wb")) { fwrite(sram, 1, sz, f); fclose(f); }
}

int currentChapter() { return g ? trackNow() : -1; }
int currentVideoFrame() { return g ? videoFrameNow() : -1; }
const Chapter *chapter(int id) {
    if (!g || id < 0 || id >= (int)g->chapters.size()) return nullptr;
    return &g->chapters[id];
}
int chapterCount() { return g ? (int)g->chapters.size() : 0; }
uint32_t chapterChangeCounter() { return g ? g->chapterCounter.load() : 0; }

bool copyFrame(Frame &dst) {
    if (!g) return false;
    std::lock_guard<std::mutex> lk(g->frameMutex);
    if (g->frame.serial == dst.serial || g->frame.pixels.empty()) return false;
    dst.width = g->frame.width;
    dst.height = g->frame.height;
    dst.serial = g->frame.serial;
    dst.pixels = g->frame.pixels;
    return true;
}

double fps() { return g ? g->fps : 50.0; }
int lastQteFeedback() { return g ? g->framesSinceInject : 9999; }
int pendingRumble() { return g ? g->rumble.exchange(0) : 0; }
uint8_t *wram() { return Memory.RAM; }
size_t romPatchOffsetLoseLife() { return g ? g->loseLifeDec : 0; }

} // namespace srb
