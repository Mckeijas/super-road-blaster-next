#include "scene_gen.h"
#include "srb_core.h"

#include <cstdio>
#include <set>
#include <sys/stat.h>
#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace srb {
namespace {
// Moving preview of a scene: CLIP_FRAMES pictures (~5.5 s at ~12 fps) of the visible area,
// laid out in a CLIP_COLS-wide grid inside one JPEG (<out>/clips/NNN.jpg + NNN.txt with
// "frames fps"). The launcher animates it. Captured across scene cuts if the scene is short.
constexpr int CLIP_FRAMES = 68, CLIP_COLS = 8, CLIP_Y0 = 8, CLIP_H = 213;
struct Clip {
    int ch = -1, w = 0, taken = 0;
    long start = 0;
    std::vector<uint8_t> rgb;
};

struct Gen {
    std::string outDir;
    std::set<int> saved;
    uint32_t lastCounter = 0;
    int lastCh = -99, chStartFrame = 0, thumbFor = -1;
    long frame = 0, lastNew = 0;
    int16_t audio[48000];
    std::vector<Clip> clips;
    int clipStep = 4;
    srb::Frame fr;
};
Gen *gGen = nullptr;

void writeThumb(const std::string &path, const srb::Frame &fr) {
    std::vector<uint8_t> rgb((size_t)fr.width * fr.height * 3);
    for (int i = 0; i < fr.width * fr.height; ++i) {
        uint16_t p = fr.pixels[(size_t)i];
        int r = (p >> 11) & 31, gg = (p >> 5) & 63, b = p & 31;
        rgb[(size_t)i * 3] = (uint8_t)((r << 3) | (r >> 2));
        rgb[(size_t)i * 3 + 1] = (uint8_t)((gg << 2) | (gg >> 4));
        rgb[(size_t)i * 3 + 2] = (uint8_t)((b << 3) | (b >> 2));
    }
    std::string tmp = path + ".tmp";
    if (stbi_write_png(tmp.c_str(), fr.width, fr.height, 3, rgb.data(), fr.width * 3)) rename(tmp.c_str(), path.c_str());
}
void clipPut(Clip &c, const srb::Frame &fr) {
    if (c.rgb.empty()) {
        c.w = fr.width;
        int rows = (CLIP_FRAMES + CLIP_COLS - 1) / CLIP_COLS;
        c.rgb.assign((size_t)c.w * CLIP_COLS * CLIP_H * rows * 3, 0);
    }
    int col = c.taken % CLIP_COLS, row = c.taken / CLIP_COLS, stride = c.w * CLIP_COLS * 3;
    for (int y = 0; y < CLIP_H; ++y) {
        int sy = y + (fr.height >= CLIP_Y0 + CLIP_H ? CLIP_Y0 : 0);
        if (sy >= fr.height) break;
        uint8_t *d = c.rgb.data() + (size_t)(row * CLIP_H + y) * stride + (size_t)col * c.w * 3;
        const uint16_t *src = fr.pixels.data() + (size_t)sy * fr.width;
        for (int x = 0; x < c.w && x < fr.width; ++x) {
            uint16_t p = src[x];
            int r = (p >> 11) & 31, gg = (p >> 5) & 63, b = p & 31;
            d[x * 3] = (uint8_t)((r << 3) | (r >> 2));
            d[x * 3 + 1] = (uint8_t)((gg << 2) | (gg >> 4));
            d[x * 3 + 2] = (uint8_t)((b << 3) | (b >> 2));
        }
    }
    c.taken++;
}

void clipWrite(const std::string &outDir, const Clip &c, double fps) {
    if (c.taken < 8) return;
    int rows = (c.taken + CLIP_COLS - 1) / CLIP_COLS;
    char p[64];
    snprintf(p, sizeof p, "/clips/%03d.jpg", c.ch);
    std::string path = outDir + p, tmp = path + ".tmp";
    if (!stbi_write_jpg(tmp.c_str(), c.w * CLIP_COLS, CLIP_H * rows, 3, c.rgb.data(), 80)) return;
    rename(tmp.c_str(), path.c_str());
    snprintf(p, sizeof p, "/clips/%03d.txt", c.ch);
    if (FILE *f = fopen((outDir + p).c_str(), "w")) {
        fprintf(f, "%d %.3f %d\n", c.taken, fps, CLIP_COLS);
        fclose(f);
    }
}
} // namespace


bool genBegin(const std::vector<uint8_t> &rom, const std::string &chaptersTxt, const std::string &gameDir,
              const std::string &saveDir, const std::string &outDir) {
    Config cfg;
    cfg.rom = rom;
    cfg.chaptersTxt = chaptersTxt;
    cfg.saveDir = saveDir;
    cfg.romPath = gameDir + "/SuperRoadBlaster.sfc";
    if (!init(cfg)) return false;
    delete gGen;
    gGen = new Gen();
    gGen->outDir = outDir;
    mkdir((outDir + "/states").c_str(), 0755);
    mkdir((outDir + "/thumbs").c_str(), 0755);
    mkdir((outDir + "/clips").c_str(), 0755);
    setAutoQte(QTE_FULL);
    setInfiniteLives(true);
    setHudVisible(false);   // clean thumbnails: no score, arrows or dashboard
    return true;
}

int genRun(int frames, int maxFrames, int expected) {
    Gen *g = gGen;
    if (!g) return -1;
    for (int i = 0; i < frames; ++i, ++g->frame) {
        int ch = currentChapter();
        bool start = (g->frame % 90) < 3 && ch < 3;      // advance title / attract screens
        setButtons(start ? BTN_START : 0);
        if (chapterChangeCounter() != g->lastCounter || ch != g->lastCh) {
            g->lastCounter = chapterChangeCounter();
            if (ch >= 0 && !g->saved.count(ch)) {
                std::vector<uint8_t> st;
                if (saveState(st)) {
                    char p[64];
                    snprintf(p, sizeof p, "/states/%03d.state", ch);
                    std::string path = g->outDir + p, tmp = path + ".tmp";
                    if (FILE *f = fopen(tmp.c_str(), "wb")) {
                        fwrite(st.data(), 1, st.size(), f);
                        fclose(f);
                        rename(tmp.c_str(), path.c_str());
                    }
                    g->saved.insert(ch);
                    Clip c;
                    c.ch = ch;
                    c.start = g->frame + 15;                // past the cut from the previous scene
                    g->clips.push_back(std::move(c));
                    g->thumbFor = ch;
                    g->chStartFrame = (int)g->frame;
                    g->lastNew = g->frame;
                }
            }
            g->lastCh = ch;
        }
        runFrame(g->audio, 24000);
        bool fresh = copyFrame(g->fr);      // one copy per frame, shared by thumbnails and clips
        if (g->thumbFor >= 0 && currentChapter() != g->thumbFor && g->frame - g->chStartFrame > 2) {
            g->thumbFor = -1;                               // scene too short for a thumbnail
        }
        // thumbnail ~1.2 s into the scene (past the cut from the previous one); short scenes
        // keep an early provisional one taken at ~0.3 s
        long age = g->frame - g->chStartFrame;
        if (g->thumbFor >= 0 && age == 15 && fresh) {
            char p[64];
            snprintf(p, sizeof p, "/thumbs/%03d.png", g->thumbFor);
            writeThumb(g->outDir + p, g->fr);
        }
        if (g->thumbFor >= 0 && age == 60 && fresh) {
            char p[64];
            snprintf(p, sizeof p, "/thumbs/%03d.png", g->thumbFor);
            writeThumb(g->outDir + p, g->fr);
            g->thumbFor = -1;
        }
        g->clipStep = fps() < 55.0 ? 4 : 5;
        for (size_t k = 0; k < g->clips.size();) {
            Clip &c = g->clips[k];
            long a = g->frame - c.start;
            if (a >= 0 && a % g->clipStep == 0 && g->fr.width > 0) clipPut(c, g->fr);
            if (c.taken >= CLIP_FRAMES) {
                clipWrite(g->outDir, c, fps() / g->clipStep);
                g->clips.erase(g->clips.begin() + (long)k);
            } else {
                ++k;
            }
        }
        if (g->frame >= maxFrames) {
            for (Clip &c : g->clips) clipWrite(g->outDir, c, fps() / g->clipStep);
            g->clips.clear();
            return -1;
        }
        if ((int)g->saved.size() >= expected && g->thumbFor < 0 && g->clips.empty() && g->frame - g->lastNew > 90) return -1;
    }
    return (int)g->saved.size();
}

int genFrame() { return gGen ? (int)gGen->frame : 0; }

void genEnd() {
    shutdown();
    delete gGen;
    gGen = nullptr;
}
} // namespace srb
