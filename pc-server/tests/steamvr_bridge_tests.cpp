#include "steamvr/bridge.hpp"
#include <windows.h>
#include <chrono>
#include <future>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>

namespace {
using namespace std::chrono_literals;
using namespace vrbox::steamvr;
int checks = 0;
void Require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
uint64_t NowUs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
}

int main() {
    try {
        const auto name = L"VRBoxBridgeTest_" + std::to_wstring(GetCurrentProcessId()) + L"_" + std::to_wstring(GetTickCount64());
        Bridge owner(name), reader(name), contender(name);
        Require(!reader.Open(false), "A reader must not create absent IPC objects");
        Require(owner.Open(true) && reader.Open(false), "Cannot open isolated IPC endpoints");
        Require(!contender.Open(true), "A live server must have exactly one owner");
        Config config;
        config.width = 160; config.height = 120;
        Require(owner.Configure(config) && reader.ReadState().config == config, "Display configuration did not round trip");
        Require(!reader.Configure(config) && !reader.Heartbeat(true) && !reader.PublishPose({}), "Reader wrote owner-only control data");
        Require(owner.ReadState().server_alive && !owner.ReadState().client_connected, "Initial bridge state is incorrect");
        for (const auto width : {0u, 158u, 162u, 1924u}) {
            auto invalid = config; invalid.width = width;
            Require(!owner.Configure(invalid), "Invalid SBS width accepted");
        }
        for (const auto height : {0u, 118u, 121u, 1082u}) {
            auto invalid = config; invalid.height = height;
            Require(!owner.Configure(invalid), "Invalid video height accepted");
        }
        for (const auto fps : {0u, 61u}) {
            auto invalid = config; invalid.fps = fps;
            Require(!owner.Configure(invalid), "Invalid refresh rate accepted");
        }
        for (const auto ipd : {0.f, 0.044f, 0.081f, std::numeric_limits<float>::quiet_NaN()}) {
            auto invalid = config; invalid.ipd_m = ipd;
            Require(!owner.Configure(invalid), "Invalid IPD accepted");
        }
        for (const auto fov : {34.f, 111.f, std::numeric_limits<float>::infinity()}) {
            auto invalid = config; invalid.fov_y_degrees = fov;
            Require(!owner.Configure(invalid), "Invalid FOV accepted");
        }
        Require(reader.ReadState().config == config, "Rejected configuration changed shared state");
        auto boundary = config;
        boundary.width = 1920; boundary.height = 1080; boundary.ipd_m = 0.080f; boundary.fov_y_degrees = 110.f;
        Require(owner.Configure(boundary), "Valid maximum configuration rejected");
        boundary.width = 160; boundary.height = 120; boundary.fps = 1; boundary.ipd_m = 0.045f; boundary.fov_y_degrees = 35.f;
        Require(owner.Configure(boundary) && owner.Configure(config), "Valid minimum configuration rejected");

        Pose pose;
        pose.valid = true; pose.timestamp_ms = GetTickCount64(); pose.angular_velocity[1] = 0.5;
        Require(owner.PublishPose(pose) && !reader.ReadState().pose.valid, "Disconnected phone reported tracked");
        Require(owner.Heartbeat(true), "Connect first phone session");
        const auto first_generation = reader.ReadState().generation;
        Require(!reader.ReadState().pose.valid, "New session inherited old tracking");
        Require(owner.PublishPose(pose) && reader.ReadState().pose.valid, "Fresh pose did not round trip");
        Require(reader.ReadState().pose.angular_velocity[1] == 0.5, "Angular velocity lost");
        auto invalid_pose = pose; invalid_pose.q[3] = 0;
        Require(!owner.PublishPose(invalid_pose), "Zero quaternion accepted");
        invalid_pose = pose; invalid_pose.q[0] = std::numeric_limits<double>::quiet_NaN();
        Require(!owner.PublishPose(invalid_pose), "NaN quaternion accepted");
        invalid_pose = pose; invalid_pose.q[3] = 2;
        Require(!owner.PublishPose(invalid_pose), "Unnormalized quaternion accepted");
        invalid_pose = pose; invalid_pose.angular_velocity[0] = 21;
        Require(!owner.PublishPose(invalid_pose), "Unbounded prediction velocity accepted");
        invalid_pose.angular_velocity[0] = std::numeric_limits<double>::infinity();
        Require(!owner.PublishPose(invalid_pose), "Infinite prediction velocity accepted");
        invalid_pose = pose; invalid_pose.timestamp_ms = GetTickCount64() + 1000;
        Require(!owner.PublishPose(invalid_pose), "Future pose timestamp accepted");
        invalid_pose.timestamp_ms = GetTickCount64() - 300;
        Require(!owner.PublishPose(invalid_pose), "Stale pose timestamp accepted");
        invalid_pose.timestamp_ms = 0;
        Require(!owner.PublishPose(invalid_pose), "Valid pose without timestamp accepted");

        const uint32_t pitch = config.width * 4 + 16;
        std::vector<uint8_t> pixels(size_t(pitch) * config.height, 0xED), expected(size_t(config.width) * config.height * 4);
        for (uint32_t y = 0; y < config.height; ++y) for (uint32_t x = 0; x < config.width * 4; ++x) {
            const auto value = uint8_t(x + y * 7);
            pixels[size_t(y) * pitch + x] = expected[size_t(y) * config.width * 4 + x] = value;
        }
        Frame frame;
        Require(!reader.ReadFrame(frame), "Frame appeared without a producer");
        const auto publish = [&](uint64_t generation) {
            return reader.PublishFrame(pixels.data(), config.width, config.height, pitch, NowUs(), generation);
        };
        Require(publish(first_generation) && owner.ReadFrame(frame), "Padded BGRA frame did not round trip");
        Require(frame.bgra == expected && frame.width == config.width && frame.height == config.height,
            "IPC copied row padding or corrupted pixels/dimensions");
        Require(frame.sequence != 0 && frame.generation == first_generation && frame.timestamp_us != 0,
            "IPC frame metadata is missing");
        Require(!owner.ReadFrame(frame) && owner.ReadState().frame_count == 1, "Duplicate frame read or wrong frame count");
        Require(!reader.PublishFrame(nullptr, config.width, config.height, pitch, NowUs(), first_generation), "Null pixels accepted");
        Require(!reader.PublishFrame(pixels.data(), config.width + 4, config.height, pitch, NowUs(), first_generation), "Wrong frame width accepted");
        Require(!reader.PublishFrame(pixels.data(), config.width, config.height + 2, pitch, NowUs(), first_generation), "Wrong frame height accepted");
        Require(!reader.PublishFrame(pixels.data(), config.width, config.height, config.width * 4 - 1, NowUs(), first_generation), "Short row pitch accepted");
        Require(!reader.PublishFrame(pixels.data(), config.width, config.height, pitch, 0, first_generation), "Missing capture timestamp accepted");
        Require(!publish(first_generation + 1), "Foreign session generation accepted");
        Require(owner.Heartbeat(false) && !reader.ReadState().pose.valid && !reader.ReadFrame(frame), "Disconnect retained valid tracking/video");
        Require(!publish(first_generation), "Frame accepted while disconnected");
        Require(owner.Heartbeat(true), "Reconnect phone");
        const auto generation = reader.ReadState().generation;
        Require(generation != first_generation && reader.ReadState().frame_count == 0 && !reader.ReadState().pose.valid,
            "Reconnect did not clear session state");
        Require(!reader.ReadFrame(frame) && !publish(first_generation), "Previous session frame leaked through reconnect");
        Require(publish(generation) && owner.ReadFrame(frame) && frame.generation == generation, "New session frame rejected");

        // Hold only the frame lock from another thread. Control/tracking must remain independent.
        const auto frame_lock_name = L"Local\\" + name + L"_frame_lock";
        HANDLE frame_lock = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, frame_lock_name.c_str());
        Require(frame_lock != nullptr, "Open test frame mutex");
        std::promise<DWORD> acquired;
        std::promise<void> release;
        auto released = release.get_future();
        std::jthread holder([&] {
            const auto result = WaitForSingleObject(frame_lock, 1000);
            acquired.set_value(result);
            released.wait();
            if (result == WAIT_OBJECT_0 || result == WAIT_ABANDONED) ReleaseMutex(frame_lock);
        });
        const auto result = acquired.get_future().get();
        pose.timestamp_ms = GetTickCount64();
        const bool tracking_works = owner.Heartbeat(true) && owner.PublishPose(pose) && reader.ReadState().pose.valid;
        const bool frame_blocked = !publish(generation) && !reader.ReadFrame(frame);
        release.set_value(); holder.join();
        Require(result == WAIT_OBJECT_0 && tracking_works && frame_blocked, "Frame contention blocked tracking or allowed unlocked pixels");
        Require(publish(generation) && owner.ReadFrame(frame), "IPC did not recover after lock contention");

        std::jthread abandoned([&] { WaitForSingleObject(frame_lock, 1000); });
        abandoned.join();
        Frame unread;
        const bool rejected_abandoned = !reader.ReadFrame(unread);
        CloseHandle(frame_lock);
        Require(rejected_abandoned, "Abandoned partial frame was accepted");
        Require(publish(generation) && owner.ReadFrame(frame), "IPC did not recover after abandoned frame lock");

        pose.timestamp_ms = GetTickCount64();
        Require(owner.Heartbeat(true) && owner.PublishPose(pose) && publish(generation), "Prepare freshness checks");
        std::this_thread::sleep_for(550ms);
        const auto stale_pose = reader.ReadState();
        Require(stale_pose.server_alive && stale_pose.client_connected && !stale_pose.pose.valid, "Pose did not expire independently of heartbeat");
        Require(!reader.ReadFrame(frame), "Old pixels remained readable after 500 ms");
        std::this_thread::sleep_for(1600ms);
        Require(!reader.ReadState().server_alive && !reader.ReadState().client_connected, "Dead server heartbeat did not expire");
        Require(contender.Open(true) && contender.Configure(config) && contender.Heartbeat(true), "Cannot recover a stale server owner");
        const auto replacement = reader.ReadState();
        Require(replacement.server_alive && replacement.generation != generation && !replacement.pose.valid, "Replacement owner inherited stale state");
        Require(!owner.Heartbeat(true) && !owner.Configure(config) && !owner.PublishPose(pose), "Stale owner overwrote replacement control data");
        owner.Close();
        Require(reader.ReadState().server_alive, "Closing stale owner disconnected replacement server");
        Require(!reader.ReadFrame(frame), "Replacement server inherited old pixels");
        Require(publish(replacement.generation) && contender.ReadFrame(frame), "Existing reader did not follow replacement generation");
        contender.Close();
        Require(!reader.ReadState().server_alive && !reader.ReadFrame(frame), "Owner close did not invalidate the bridge");
        Require(owner.Open(true) && owner.Configure(config) && owner.Heartbeat(true), "Cannot reopen cleanly closed server");
        Require(!reader.ReadFrame(frame), "Clean reopen leaked buffered pixels");
        owner.Close(); reader.Close();
        Require(!reader.ReadState().server_alive && !reader.ReadFrame(frame), "Closed reader accessed shared memory");
        std::cout << checks << " isolated SteamVR IPC checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Bridge test failed: " << error.what() << '\n';
        return 1;
    }
}
