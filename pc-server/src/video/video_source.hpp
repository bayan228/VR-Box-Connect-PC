#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace vr::video {

struct Settings {
    uint32_t width = 1280;
    uint32_t height = 720;
    uint32_t fps = 30;
    uint32_t bitrate = 8'000'000;
    bool test_pattern = false;
    bool steamvr = false;
    bool stereo_test_pattern = false;
    std::wstring bridge_name = L"VRBoxSteamVR_v1";
};

struct EncodedFrame {
    std::vector<uint8_t> bytes;
    uint64_t timestamp_us = 0;
    bool keyframe = false;
};

// Own and call this on one worker thread (COM and DXGI are thread-affine).
class VideoSource {
public:
    explicit VideoSource(const Settings& settings);
    ~VideoSource();
    VideoSource(const VideoSource&) = delete;
    VideoSource& operator=(const VideoSource&) = delete;
    std::vector<EncodedFrame> Next(bool force_keyframe);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace vr::video
