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
struct Gen {
    std::string outDir;
    std::set<int> saved;
    uint32_t lastCounter = 0;
    int lastCh = -99, chStartFrame = 0, thumbFor = -1;
    long frame = 0, lastNew = 0;
    int16_t audio[48000];
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
                    g->thumbFor = ch;
                    g->chStartFrame = (int)g->frame;
                    g->lastNew = g->frame;
                }
            }
            g->lastCh = ch;
        }
        runFrame(g->audio, 24000);
        if (g->thumbFor >= 0 && currentChapter() != g->thumbFor && g->frame - g->chStartFrame > 2) {
            g->thumbFor = -1;                               // scene too short for a thumbnail
        }
        // thumbnail ~1.2 s into the scene (past the cut from the previous one); short scenes
        // keep an early provisional one taken at ~0.3 s
        long age = g->frame - g->chStartFrame;
        if (g->thumbFor >= 0 && age == 15 && copyFrame(g->fr)) {
            char p[64];
            snprintf(p, sizeof p, "/thumbs/%03d.png", g->thumbFor);
            writeThumb(g->outDir + p, g->fr);
        }
        if (g->thumbFor >= 0 && age == 60 && copyFrame(g->fr)) {
            char p[64];
            snprintf(p, sizeof p, "/thumbs/%03d.png", g->thumbFor);
            writeThumb(g->outDir + p, g->fr);
            g->thumbFor = -1;
        }
        if (g->frame >= maxFrames) return -1;
        if ((int)g->saved.size() >= expected && g->thumbFor < 0 && g->frame - g->lastNew > 90) return -1;
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
