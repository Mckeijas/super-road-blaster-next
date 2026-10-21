// Headless desktop harness for the SRB port core.
//   srb_harness shots    <rom> <chapters.txt> <outdir> <frames> <every> [qte=0|1|2] [movie=0|1]
//   srb_harness autoplay <rom> <chapters.txt> <outdir> <maxframes>
//   srb_harness gen      <rom> <chapters.txt> <outdir> [expected=43]   (scene selector data, as on the phone)
//   srb_harness state    <rom> <chapters.txt> <state> <outdir> <frames> <every> [qte] [movie] [speed]
#include "srb_core.h"
#include "scene_gen.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <set>
#include <sstream>
#include <string>
#include <vector>
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

static std::vector<uint8_t> readFile(const std::string &p) {
    std::ifstream f(p, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), {});
}
static void writeFile(const std::string &p, const std::vector<uint8_t> &d) {
    std::ofstream f(p, std::ios::binary);
    f.write((const char *)d.data(), d.size());
}
static void png(const std::string &path, const srb::Frame &fr) {
    std::vector<uint8_t> rgb(fr.width * fr.height * 3);
    for (int i = 0; i < fr.width * fr.height; ++i) {
        uint16_t p = fr.pixels[i];
        int r = (p >> 11) & 31, gg = (p >> 5) & 63, b = p & 31;
        rgb[i * 3] = (r << 3) | (r >> 2);
        rgb[i * 3 + 1] = (gg << 2) | (gg >> 4);
        rgb[i * 3 + 2] = (b << 3) | (b >> 2);
    }
    stbi_write_png(path.c_str(), fr.width, fr.height, 3, rgb.data(), fr.width * 3);
}
struct Wav {
    FILE *f = nullptr; uint32_t n = 0;
    void open(const std::string &p) { f = fopen(p.c_str(), "wb"); uint8_t h[44] = {0}; fwrite(h, 1, 44, f); }
    void add(const int16_t *s, int frames) { if (f) { fwrite(s, 4, frames, f); n += frames; } }
    void close() {
        if (!f) return;
        auto w32 = [&](uint32_t v) { fwrite(&v, 4, 1, f); }; auto w16 = [&](uint16_t v) { fwrite(&v, 2, 1, f); };
        fseek(f, 0, SEEK_SET); fwrite("RIFF", 1, 4, f); w32(36 + n * 4); fwrite("WAVEfmt ", 1, 8, f);
        w32(16); w16(1); w16(2); w32(48000); w32(48000 * 4); w16(4); w16(16); fwrite("data", 1, 4, f); w32(n * 4); fclose(f);
    }
};

static bool boot(const char *rom, const char *chapters) {
    srb::Config c;
    c.romPath = rom;
    c.rom = readFile(rom);
    auto t = readFile(chapters);
    c.chaptersTxt.assign(t.begin(), t.end());
    c.saveDir = "/tmp";
    if (const char *gp = getenv("SRB_GAINS")) {      // enhanced_gains.json
        auto j = readFile(gp);
        std::string js(j.begin(), j.end());
        size_t p = js.find('[', js.find("gainDb"));
        while (p != std::string::npos && p < js.size() && js[p] != ']') {
            char *end; float v = strtof(js.c_str() + p + 1, &end);
            if (end == js.c_str() + p + 1) break;
            c.enhancedGainDb.push_back(v);
            p = end - js.c_str();
        }
    }
    return srb::init(c);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: see source\n"); return 1; }
    std::string cmd = argv[1];
    static int16_t audio[48000];
    srb::Frame fr;

    if (cmd == "shots" || cmd == "state") {
        int a = 2;
        const char *rom = argv[a++], *chap = argv[a++];
        std::string state = cmd == "state" ? argv[a++] : "";
        std::string out = argv[a++];
        int frames = atoi(argv[a++]), every = atoi(argv[a++]);
        int qte = argc > a ? atoi(argv[a++]) : 0;
        int movie = argc > a ? atoi(argv[a++]) : 0;
        float speed = argc > a ? atof(argv[a++]) : 1.0f;
        int lives = argc > a ? atoi(argv[a++]) : 0;
        int enh = argc > a ? atoi(argv[a++]) : 0;
        if (!boot(rom, chap)) return 2;
        if (!state.empty() && !srb::loadState(readFile(state))) { fprintf(stderr, "loadState failed\n"); return 3; }
        srb::setAutoQte(qte); srb::setMovieMode(movie); srb::setSpeed(speed); srb::setInfiniteLives(lives); srb::setEnhancedAudio(enh);
        Wav wav; wav.open(out + "/audio.wav");
        int lastCh = -99;
        for (int i = 0; i < frames; ++i) {
            // tap START now and then so the title/attract screens advance
            bool start = cmd == "shots" && (i % 120) < 4 && srb::currentChapter() < 3;
            srb::setButtons(start ? srb::BTN_START : 0);
            int n = srb::runFrame(audio, 24000);
            wav.add(audio, n);
            int ch = srb::currentChapter();
            if (ch != lastCh) {
                const srb::Chapter *c = srb::chapter(ch);
                printf("frame %d: chapter %d %s\n", i, ch, c ? c->name.c_str() : "-");
                lastCh = ch;
            }
            if (every > 0 && i % every == 0 && srb::copyFrame(fr)) {
                char p[512]; snprintf(p, sizeof p, "%s/f%06d_c%03d_v%04d.png", out.c_str(), i, ch, srb::currentVideoFrame());
                png(p, fr);
            }
        }
        wav.close();
        srb::shutdown();
        return 0;
    }

    if (cmd == "gen") {
        auto t0 = std::chrono::steady_clock::now();
        std::string rom = argv[2], out = argv[4];
        int expected = argc > 5 ? atoi(argv[5]) : 43;
        auto t = readFile(argv[3]);
        std::string dir = rom.substr(0, rom.find_last_of('/'));
        if (!srb::genBegin(readFile(rom), std::string(t.begin(), t.end()), dir, "/tmp", out)) return 2;
        int r;
        while ((r = srb::genRun(3000, 70000, expected)) >= 0) { printf("frame %d scenes %d\n", srb::genFrame(), r); fflush(stdout); }
        printf("done at frame %d in %.1f s\n", srb::genFrame(),
               std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count());
        srb::genEnd();
        return 0;
    }

    if (cmd == "crash") {
        // crash <rom> <chapters> <srcState> <srcChapter> <eventStart> <target> <outState>
        const char *rom = argv[2], *chap = argv[3];
        int src = atoi(argv[5]), evStart = atoi(argv[6]), target = atoi(argv[7]);
        if (!boot(rom, chap) || !srb::loadState(readFile(argv[4]))) return 2;
        srb::setAutoQte(srb::QTE_FULL);
        for (int i = 0; i < 20000; ++i) {
            int ch = srb::currentChapter();
            if (ch == src && srb::currentVideoFrame() >= evStart - 2) srb::setAutoQte(srb::QTE_OFF);
            if (ch >= 0 && ch != src) {
                if (ch != target) { printf("FAIL got chapter %d expected %d\n", ch, target); return 4; }
                std::vector<uint8_t> st;
                srb::saveState(st);
                writeFile(argv[8], st);
                printf("OK %d at frame %d\n", ch, i);
                return 0;
            }
            srb::runFrame(audio, 24000);
        }
        printf("FAIL timeout\n");
        return 5;
    }

    if (cmd == "autoplay") {
        const char *rom = argv[2], *chap = argv[3];
        std::string out = argv[4];
        int maxFrames = atoi(argv[5]);
        if (!boot(rom, chap)) return 2;
        srb::setAutoQte(srb::QTE_FULL);
        std::set<int> saved;
        uint32_t lastCounter = 0;
        int lastCh = -99;
        for (int i = 0; i < maxFrames; ++i) {
            int ch = srb::currentChapter();
            bool start = (i % 90) < 3 && (ch < 3);
            srb::setButtons(start ? srb::BTN_START : 0);
            // Save a state right at the start of every chapter (before running it).
            if (srb::chapterChangeCounter() != lastCounter || ch != lastCh) {
                lastCounter = srb::chapterChangeCounter();
                if (ch >= 0 && !saved.count(ch)) {
                    std::vector<uint8_t> st;
                    srb::saveState(st);
                    char p[512]; snprintf(p, sizeof p, "%s/%03d.state", out.c_str(), ch);
                    writeFile(p, st);
                    saved.insert(ch);
                }
                const srb::Chapter *c = srb::chapter(ch);
                printf("frame %d: chapter %d %s (saved %zu)\n", i, ch, c ? c->name.c_str() : "-", saved.size());
                fflush(stdout);
                lastCh = ch;
            }
            srb::runFrame(audio, 24000);
            if (i % 250 == 0 && srb::copyFrame(fr)) {
                char p[512]; snprintf(p, sizeof p, "%s/auto_%07d_c%03d.png", out.c_str(), i, ch);
                png(p, fr);
            }
        }
        srb::shutdown();
        return 0;
    }
    return 1;
}
