#include "bridge.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>

#ifdef _WIN32
#include <windows.h>

namespace vrbox::steamvr {
namespace {
constexpr uint32_t kMagic = 0x31524256, kVersion = 1, kCapacity = 1920 * 1080 * 4;
struct Control {
    uint32_t magic, version, owner;
    uint64_t generation, heartbeat, frames;
    bool connected;
    Config config;
    Pose pose;
};
struct Pixels {
    uint32_t magic, version, width, height, bytes;
    uint64_t generation, counter, sequence, timestamp_us, written_ms;
    uint8_t data[kCapacity];
};
struct Lock {
    HANDLE handle;
    bool acquired = false, abandoned = false;
    explicit Lock(HANDLE value, DWORD wait = 2) : handle(value) {
        if (!handle) return;
        const auto result = WaitForSingleObject(handle, wait);
        acquired = result == WAIT_OBJECT_0 || result == WAIT_ABANDONED;
        abandoned = result == WAIT_ABANDONED;
    }
    ~Lock() { if (acquired) ReleaseMutex(handle); }
    explicit operator bool() const { return acquired; }
};
bool Valid(const Config& config) {
    return config.width >= 160 && config.width <= 1920 && config.width % 4 == 0 &&
        config.height >= 120 && config.height <= 1080 && config.height % 2 == 0 &&
        config.fps >= 1 && config.fps <= 60 && std::isfinite(config.ipd_m) &&
        config.ipd_m >= 0.045f && config.ipd_m <= 0.080f && std::isfinite(config.fov_y_degrees) &&
        config.fov_y_degrees >= 35.f && config.fov_y_degrees <= 110.f;
}
bool Fresh(uint64_t timestamp, uint64_t max_age) {
    const auto now = GetTickCount64();
    return timestamp && timestamp <= now && now - timestamp <= max_age;
}
}

struct Bridge::Impl {
    std::wstring name;
    HANDLE control_map = nullptr, frame_map = nullptr, control_mutex = nullptr, frame_mutex = nullptr;
    Control* control = nullptr;
    Pixels* pixels = nullptr;
    bool owner = false;
    std::atomic<uint64_t> generation{0};
    explicit Impl(std::wstring value) : name(L"Local\\" + value) {}
    bool Header() const { return control && control->magic == kMagic && control->version == kVersion; }
};

Bridge::Bridge(std::wstring name) : impl_(std::make_unique<Impl>(std::move(name))) {}
Bridge::~Bridge() { Close(); }

bool Bridge::Open(bool create_server) {
    Close();
    auto& p = *impl_;
    const auto control_name = p.name + L"_control", frame_name = p.name + L"_frame";
    const auto cm = p.name + L"_pose_lock", fm = p.name + L"_frame_lock";
    p.control_mutex = create_server ? CreateMutexW(nullptr, FALSE, cm.c_str()) :
        OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, cm.c_str());
    p.frame_mutex = create_server ? CreateMutexW(nullptr, FALSE, fm.c_str()) :
        OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, fm.c_str());
    p.control_map = create_server ? CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
        sizeof(Control), control_name.c_str()) : OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, control_name.c_str());
    p.frame_map = create_server ? CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
        sizeof(Pixels), frame_name.c_str()) : OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, frame_name.c_str());
    if (!p.control_mutex || !p.frame_mutex || !p.control_map || !p.frame_map) { Close(); return false; }
    p.control = static_cast<Control*>(MapViewOfFile(p.control_map, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Control)));
    p.pixels = static_cast<Pixels*>(MapViewOfFile(p.frame_map, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Pixels)));
    if (!p.control || !p.pixels) { Close(); return false; }
    bool success = false;
    {
        Lock lock(p.control_mutex, 50);
        if (lock) {
            if (create_server) {
                if (!p.Header() || !Fresh(p.control->heartbeat, 2000)) {
                    const uint64_t previous = p.Header() ? p.control->generation : 0;
                    *p.control = {};
                    p.control->version = kVersion;
                    p.control->owner = GetCurrentProcessId();
                    p.control->generation = std::max(previous + 1, GetTickCount64());
                    p.control->config = Config{};
                    p.control->heartbeat = GetTickCount64();
                    p.control->magic = kMagic;
                    p.owner = true;
                    success = true;
                }
            } else success = p.Header();
            if (success) p.generation = p.control->generation;
        }
    }
    if (!success) { Close(); return false; }
    return true;
}

void Bridge::Close() {
    auto& p = *impl_;
    if (p.owner && p.Header()) {
        Lock lock(p.control_mutex, 20);
        if (lock && p.control->owner == GetCurrentProcessId() && p.control->generation == p.generation) {
            p.control->connected = false;
            p.control->pose.valid = false;
            p.control->heartbeat = 0;
        }
    }
    p.owner = false;
    if (p.control) UnmapViewOfFile(p.control);
    if (p.pixels) UnmapViewOfFile(p.pixels);
    p.control = nullptr; p.pixels = nullptr;
    for (HANDLE* handle : {&p.control_map, &p.frame_map, &p.control_mutex, &p.frame_mutex}) {
        if (*handle) CloseHandle(*handle);
        *handle = nullptr;
    }
}

bool Bridge::Configure(const Config& config) {
    auto& p = *impl_;
    if (!p.owner || !Valid(config)) return false;
    Lock lock(p.control_mutex);
    if (!lock || !p.Header() || p.control->generation != p.generation) return false;
    p.control->config = config;
    return true;
}

bool Bridge::Heartbeat(bool client_connected) {
    auto& p = *impl_;
    Lock lock(p.control_mutex);
    if (!p.owner || !lock || !p.Header() || p.control->generation != p.generation) return false;
    if (client_connected && !p.control->connected) {
        // A new phone session must never receive the previous session's buffered pixels.
        p.generation = ++p.control->generation;
        p.control->frames = 0;
        p.control->pose = {};
    }
    p.control->heartbeat = GetTickCount64();
    p.control->connected = client_connected;
    if (!client_connected) p.control->pose.valid = false;
    return true;
}

bool Bridge::PublishPose(const Pose& pose) {
    auto& p = *impl_;
    if (!p.owner) return false;
    double norm = 0;
    for (double value : pose.q) { if (!std::isfinite(value)) return false; norm += value * value; }
    for (double value : pose.angular_velocity) if (!std::isfinite(value) || std::abs(value) > 20.001) return false;
    if (norm < 0.99 || norm > 1.01 || (pose.valid && !Fresh(pose.timestamp_ms, 250))) return false;
    Lock lock(p.control_mutex);
    if (!lock || !p.Header() || p.control->generation != p.generation) return false;
    p.control->pose.valid = false;
    p.control->pose = pose;
    return true;
}

State Bridge::ReadState() {
    auto& p = *impl_;
    State result;
    Lock lock(p.control_mutex);
    if (!lock || !p.Header() || !Valid(p.control->config)) return result;
    if (lock.abandoned) { p.control->pose.valid = false; return result; }
    result.config = p.control->config;
    result.server_alive = Fresh(p.control->heartbeat, 2000);
    result.client_connected = result.server_alive && p.control->connected;
    result.pose = p.control->pose;
    result.pose.valid = result.client_connected && result.pose.valid && Fresh(result.pose.timestamp_ms, 250);
    double norm = 0;
    for (double value : result.pose.q) { if (!std::isfinite(value)) result.pose.valid = false; norm += value * value; }
    for (double value : result.pose.angular_velocity) if (!std::isfinite(value) || std::abs(value) > 20.001) result.pose.valid = false;
    if (!std::isfinite(norm) || norm < 0.99 || norm > 1.01) result.pose.valid = false;
    result.frame_count = p.control->frames;
    result.generation = p.control->generation;
    if (!p.owner) p.generation = result.generation;
    return result;
}

bool Bridge::PublishFrame(const uint8_t* bgra, uint32_t width, uint32_t height, uint32_t row_pitch,
                          uint64_t timestamp_us, uint64_t generation) {
    auto& p = *impl_;
    const auto state = ReadState();
    if (!state.client_connected || generation != state.generation || !bgra ||
        width != state.config.width || height != state.config.height ||
        row_pitch < width * 4 || !timestamp_us) return false;
    {
        Lock lock(p.frame_mutex, 2);
        if (!lock || !p.pixels) return false;
        auto& frame = *p.pixels;
        frame.sequence = 0; // An abandoned partial write can never be mistaken for a completed frame.
        frame.magic = kMagic; frame.version = kVersion; frame.generation = state.generation;
        frame.width = width; frame.height = height; frame.bytes = width * height * 4;
        for (uint32_t y = 0; y < height; ++y) std::memcpy(frame.data + y * width * 4, bgra + size_t(y) * row_pitch, width * 4);
        frame.timestamp_us = timestamp_us;
        frame.written_ms = GetTickCount64();
        frame.sequence = ++frame.counter;
    }
    Lock lock(p.control_mutex);
    if (lock && p.Header() && p.control->generation == state.generation) ++p.control->frames;
    return true;
}

bool Bridge::ReadFrame(Frame& frame) {
    auto& p = *impl_;
    const auto state = ReadState();
    if (!state.client_connected) return false;
    Lock lock(p.frame_mutex, 2);
    if (!lock || !p.pixels) return false;
    if (lock.abandoned) { p.pixels->sequence = 0; return false; }
    const auto& data = *p.pixels;
    if (data.magic != kMagic || data.version != kVersion || data.generation != state.generation || !data.sequence ||
        (data.sequence == frame.sequence && data.generation == frame.generation) || !Fresh(data.written_ms, 500) ||
        data.width != state.config.width || data.height != state.config.height || data.bytes != data.width * data.height * 4 ||
        data.bytes > kCapacity) return false;
    frame.bgra.assign(data.data, data.data + data.bytes);
    frame.width = data.width; frame.height = data.height;
    frame.sequence = data.sequence; frame.timestamp_us = data.timestamp_us; frame.generation = data.generation;
    return true;
}
} // namespace vrbox::steamvr
#else
namespace vrbox::steamvr {
struct Bridge::Impl {};
Bridge::Bridge(std::wstring) : impl_(std::make_unique<Impl>()) {}
Bridge::~Bridge() = default;
bool Bridge::Open(bool) { return false; }
void Bridge::Close() {}
bool Bridge::Configure(const Config&) { return false; }
bool Bridge::Heartbeat(bool) { return false; }
bool Bridge::PublishPose(const Pose&) { return false; }
State Bridge::ReadState() { return {}; }
bool Bridge::PublishFrame(const uint8_t*, uint32_t, uint32_t, uint32_t, uint64_t, uint64_t) { return false; }
bool Bridge::ReadFrame(Frame&) { return false; }
}
#endif
