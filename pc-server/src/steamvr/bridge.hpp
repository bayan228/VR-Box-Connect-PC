#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace vrbox::steamvr {
struct Config {
    uint32_t width = 1280, height = 720, fps = 60;
    float ipd_m = 0.064f, fov_y_degrees = 75.f;
    bool operator==(const Config&) const = default;
};
struct Pose {
    double q[4] = {0, 0, 0, 1};
    double angular_velocity[3] = {};
    bool valid = false;
    uint64_t timestamp_ms = 0;
};
struct State {
    Config config;
    Pose pose;
    bool server_alive = false, client_connected = false;
    uint64_t frame_count = 0, generation = 0;
};
struct Frame {
    uint32_t width = 0, height = 0;
    uint64_t sequence = 0, timestamp_us = 0, generation = 0;
    std::vector<uint8_t> bgra;
};

// Local, same-user Windows IPC. Pose and frame locks are separate; video never blocks the tracking lock.
class Bridge {
public:
    explicit Bridge(std::wstring name = L"VRBoxSteamVR_v1");
    ~Bridge();
    Bridge(const Bridge&) = delete;
    Bridge& operator=(const Bridge&) = delete;
    bool Open(bool create_server);
    void Close();
    bool Configure(const Config&);
    bool Heartbeat(bool client_connected);
    bool PublishPose(const Pose&);
    State ReadState();
    bool PublishFrame(const uint8_t* bgra, uint32_t width, uint32_t height, uint32_t row_pitch,
                      uint64_t timestamp_us, uint64_t generation);
    bool ReadFrame(Frame&);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace vrbox::steamvr
