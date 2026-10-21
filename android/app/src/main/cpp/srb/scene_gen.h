// Builds the scene selector data from the imported game: plays the whole adventure headless
// with the automatic QTE and saves, for every chapter reached, a start state
// (<out>/states/NNN.state) and a thumbnail (<out>/thumbs/NNN.png, native resolution).
// Nothing derived from the game ships with the app: it is all made on the device.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace srb {
bool genBegin(const std::vector<uint8_t> &rom, const std::string &chaptersTxt, const std::string &gameDir,
              const std::string &saveDir, const std::string &outDir);
// Runs up to `frames` frames. Returns the number of scenes saved so far, or -1 when done
// (all `expected` scenes saved with their thumbnails, or maxFrames reached).
int genRun(int frames, int maxFrames, int expected);
int genFrame();
void genEnd();
} // namespace srb
