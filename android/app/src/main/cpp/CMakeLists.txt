cmake_minimum_required(VERSION 3.18)
project(srbcore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(S9X ${CMAKE_CURRENT_SOURCE_DIR}/snes9x)
set(SNES9X_SOURCES
    ${S9X}/filter/snes_ntsc.c
    ${S9X}/apu/apu.cpp ${S9X}/apu/bapu/dsp/sdsp.cpp ${S9X}/apu/bapu/smp/smp.cpp ${S9X}/apu/bapu/smp/smp_state.cpp
    ${S9X}/bsx.cpp ${S9X}/c4.cpp ${S9X}/c4emu.cpp ${S9X}/cheats.cpp ${S9X}/cheats2.cpp ${S9X}/clip.cpp
    ${S9X}/conffile.cpp ${S9X}/controls.cpp ${S9X}/cpu.cpp ${S9X}/cpuexec.cpp ${S9X}/cpuops.cpp
    ${S9X}/crosshairs.cpp ${S9X}/dma.cpp ${S9X}/dsp.cpp ${S9X}/dsp1.cpp ${S9X}/dsp2.cpp ${S9X}/dsp3.cpp
    ${S9X}/dsp4.cpp ${S9X}/fxinst.cpp ${S9X}/fxemu.cpp ${S9X}/gfx.cpp ${S9X}/globals.cpp ${S9X}/memmap.cpp
    ${S9X}/obc1.cpp ${S9X}/msu1.cpp ${S9X}/ppu.cpp ${S9X}/stream.cpp ${S9X}/sa1.cpp ${S9X}/sa1cpu.cpp
    ${S9X}/screenshot.cpp ${S9X}/sdd1.cpp ${S9X}/sdd1emu.cpp ${S9X}/seta.cpp ${S9X}/seta010.cpp
    ${S9X}/seta011.cpp ${S9X}/seta018.cpp ${S9X}/snapshot.cpp ${S9X}/snes9x.cpp ${S9X}/spc7110.cpp
    ${S9X}/srtc.cpp ${S9X}/tile.cpp ${S9X}/tileimpl-n1x1.cpp ${S9X}/tileimpl-n2x1.cpp ${S9X}/tileimpl-h2x1.cpp
    ${S9X}/sha256.cpp ${S9X}/bml.cpp ${S9X}/movie.cpp ${S9X}/fscompat.cpp ${S9X}/libretro/libretro.cpp)

add_library(srbengine STATIC
    ${SNES9X_SOURCES}
    sonic/sonic.c
    srb/srb_core.cpp)
target_include_directories(srbengine PUBLIC
    ${S9X} ${S9X}/apu ${S9X}/apu/bapu ${S9X}/libretro ${S9X}/libretro/libretro-common/include
    sonic srb third_party)
target_compile_definitions(srbengine PUBLIC __LIBRETRO__ HAVE_STRINGS_H RIGHTSHIFT_IS_SAR)
target_compile_options(srbengine PRIVATE -O3 -fno-strict-aliasing -Wno-narrowing -Wno-write-strings
    $<$<COMPILE_LANGUAGE:CXX>:-Wno-register>)
set_property(TARGET srbengine PROPERTY POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    target_compile_definitions(srbengine PUBLIC ANDROID)
    add_library(srbport SHARED jni_bridge.cpp)
    target_link_libraries(srbport srbengine android log)
else()
    # Desktop test harness (headless): screenshots, autoplay, state generation.
    add_executable(srb_harness ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../harness/main.cpp)
    target_include_directories(srb_harness PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../harness)
    target_link_libraries(srb_harness srbengine z)
endif()
