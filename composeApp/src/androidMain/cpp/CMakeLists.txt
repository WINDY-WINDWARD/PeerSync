cmake_minimum_required(VERSION 3.22.1)

project("peersync_audio")

add_library(
    peersync_audio
    SHARED
    jni_bridge.cpp
    audio_engine.cpp
    ring_buffer.cpp
    jitter_buffer.cpp
    mixer.cpp
    opus_codec.cpp
    webrtc_vad.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)

target_link_libraries(
    peersync_audio
    ${log-lib}
    ${aaudio-lib}
    ${opensles-lib}
)
