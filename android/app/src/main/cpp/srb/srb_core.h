// Super Road Blaster Android port - portable frontend core.
// Wraps the statically linked Snes9x libretro core and implements the
// Director's Mode on top of the MSU-1 state (no ROM rebuild needed).
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace srb {

// libretro joypad ids, used as bit positions in the button mask.
enum Button : uint16_t {
    BTN_B = 1u << 0, BTN_Y = 1u << 1, BTN_SELECT = 1u << 2, BTN_START = 1u << 3,
    BTN_UP = 1u << 4, BTN_DOWN = 1u << 5, BTN_LEFT = 1u << 6, BTN_RIGHT = 1u << 7,
    BTN_A = 1u << 8, BTN_X = 1u << 9, BTN_L = 1u << 10, BTN_R = 1u << 11,
};

enum AutoQte : int { QTE_OFF = 0, QTE_ASSIST = 1, QTE_FULL = 2 };

struct QteEvent {
    int start = 0;       // first video frame (relative to chapter start)
    int end = 0;         // last video frame (failure is triggered here)
    uint16_t button = 0; // the input that solves the event
    std::string type;
};

struct Chapter {
    int id = 0;          // == MSU-1 audio track number
    std::string name;    // e.g. level3_checkpoint2
    int level = 0;
    int durationMs = 0;
    std::vector<QteEvent> events;
    std::vector<int> rumbleFrames; // "vibrate" macro events -> controller rumble
};

// Opens a named game file ("SuperRoadBlaster.msu", "SuperRoadBlaster-12.pcm").
// Must return a readable fd and set *offset/*length (byte range of the file
// inside that fd), or return -1. Lets Android serve uncompressed APK assets.
using FileOpener = int (*)(const char *name, int64_t *offset, int64_t *length);

struct Config {
    std::string romPath;          // used for MSU-1 lookup when no opener is set
    std::vector<uint8_t> rom;     // ROM image
    std::string chaptersTxt;      // contents of chapters.txt (see tools/parse_events.py)
    std::string saveDir;          // SRAM (high scores) is stored here
    std::string overrideDir;      // optional: alternative soundtrack .pcm files
    std::vector<float> enhancedGainDb; // MSU-1 Enhanced: loudness gain per track (dB)
    FileOpener opener = nullptr;
};

struct Frame {
    std::vector<uint16_t> pixels; // RGB565, tightly packed width*height
    int width = 0, height = 0;
    uint32_t serial = 0;
    // HD add-on metadata
    bool overlay = false;         // HUD only (video layers hidden): draw the HD film below
    int track = -1;               // MSU-1 track (= chapter) of this picture
    int audioPos = -1;            // MSU-1 audio position in samples (44.1 kHz) at this picture
    uint16_t key = 0;             // backdrop colour = transparent pixels in overlay mode
};

bool init(const Config &cfg);
void shutdown();

// Emulation thread API -------------------------------------------------------
// Runs one emulated frame (or none, while paused) and returns the number of
// stereo frames written to out (interleaved int16, 48 kHz). The audio is
// already time-stretched for slow motion, so the caller just plays it.
int runFrame(int16_t *out, int maxFrames);
void setButtons(uint16_t mask);   // physical pad state
void setPaused(bool paused);

// Director's Mode -------------------------------------------------------------
void setAutoQte(int mode);        // AutoQte
void setInfiniteLives(bool on);
void setSpeed(float speed);       // 0.2 .. 2.0 (slow motion / fast forward, pitch preserved)
void setMovieMode(bool on);       // full auto + HUD hidden
void setHudVisible(bool on);
void setEnhancedAudio(bool on); // MSU-1 Enhanced processing (applies from the next track)
void setVolumes(float msu, float sfx); // reserved for the mixer (0..2)

// Scene select / states --------------------------------------------------------
bool saveState(std::vector<uint8_t> &out);
bool loadState(const std::vector<uint8_t> &in);
void saveSram();

// Introspection -----------------------------------------------------------------
int currentChapter();             // -1 when no MSU-1 track is active
int currentVideoFrame();          // video frame inside the chapter
const Chapter *chapter(int id);
int chapterCount();
uint32_t chapterChangeCounter();  // increments on every new chapter
bool copyFrame(Frame &dst);       // copies the latest frame if newer than dst.serial
double fps();
int lastQteFeedback();            // frames since last automatic input (debug/HUD)
int pendingRumble();              // 0 or strength 1..255; resets on read

// Low level (tools / harness).
uint8_t *wram();

// HD video add-on ------------------------------------------------------------------
void setHdOverlay(bool on);                         // use the HD film where available
void setHdChapters(const std::vector<uint8_t> &mask); // chapters present in the film
bool playerStats(int *score, int *lives);           // current score (x1000 on screen) and lives
uint16_t effectiveButtons();                        // buttons the game sees (pad + automatic QTE)
size_t romPatchOffsetLoseLife();
} // namespace srb
