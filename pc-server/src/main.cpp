#include "network/network.hpp"
#include "video/video_source.hpp"
#include "steamvr/bridge.hpp"
#include "tracking/head_pose.hpp"
#include "vr/protocol.hpp"
#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <csignal>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <iphlpapi.h>
#else
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#endif

namespace {
namespace net = vr::net;
namespace proto = vr::proto;
using Json = nlohmann::json;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
volatile std::sig_atomic_t stop_requested = 0;
void StopSignal(int) { stop_requested = 1; }

struct Socket {
    net::SocketType value = net::kInvalidSocket;
    explicit Socket(net::SocketType socket) : value(socket) {
        if (value == net::kInvalidSocket) throw std::runtime_error("Cannot create socket");
    }
    ~Socket() { net::Close(value); }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
};

struct NetworkRuntime {
    NetworkRuntime() { if (!net::Init()) throw std::runtime_error("Cannot initialize network"); }
    ~NetworkRuntime() { net::Cleanup(); }
};

bool LocalAddress(uint32_t network_address) {
    const uint32_t ip = ntohl(network_address);
    return (ip >> 24) == 127 || (ip >> 24) == 10 ||
        (ip & 0xFFF00000u) == 0xAC100000u || (ip & 0xFFFF0000u) == 0xC0A80000u ||
        (ip & 0xFFFF0000u) == 0xA9FE0000u;
}

void PrintConnectionAddresses(const std::string& bind_ip) {
    in_addr bound{};
    inet_pton(AF_INET, bind_ip.c_str(), &bound); // Already validated by Bind().
    if ((ntohl(bound.s_addr) >> 24) == 127) {
        std::cout << "PC IPv4: " << bind_ip << " (loopback: PC-only test, not reachable from a phone)\n";
        return;
    }

    std::vector<std::string> addresses;
    if (bound.s_addr != INADDR_ANY) {
        addresses.push_back(bind_ip);
    } else {
        const auto add_address = [&](const sockaddr* address) {
            if (!address || address->sa_family != AF_INET) return;
            const auto ip = reinterpret_cast<const sockaddr_in*>(address)->sin_addr;
            if (!LocalAddress(ip.s_addr) || (ntohl(ip.s_addr) >> 24) == 127) return;
            char text[INET_ADDRSTRLEN]{};
            if (inet_ntop(AF_INET, &ip, text, sizeof(text)) &&
                std::find(addresses.begin(), addresses.end(), text) == addresses.end()) {
                addresses.emplace_back(text);
            }
        };
#ifdef _WIN32
        ULONG size = 15 * 1024;
        ULONG result = ERROR_BUFFER_OVERFLOW;
        std::vector<unsigned char> buffer;
        for (int attempt = 0; attempt < 3 && result == ERROR_BUFFER_OVERFLOW; ++attempt) {
            buffer.resize(size);
            result = GetAdaptersAddresses(AF_INET,
                GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr,
                reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()), &size);
        }
        if (result == NO_ERROR) {
            for (auto* adapter = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()); adapter; adapter = adapter->Next) {
                if (adapter->OperStatus != IfOperStatusUp || adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
                for (auto* address = adapter->FirstUnicastAddress; address; address = address->Next) {
                    if (address->DadState == IpDadStatePreferred) add_address(address->Address.lpSockaddr);
                }
            }
        } else if (result != ERROR_NO_DATA) {
            std::cout << "Cannot list network adapters (Windows error " << result << "). Check IPv4 with ipconfig.\n";
        }
#else
        ifaddrs* adapters = nullptr;
        if (getifaddrs(&adapters) == 0) {
            const std::unique_ptr<ifaddrs, decltype(&freeifaddrs)> guard(adapters, freeifaddrs);
            for (auto* adapter = adapters; adapter; adapter = adapter->ifa_next) {
                if ((adapter->ifa_flags & IFF_UP) && !(adapter->ifa_flags & IFF_LOOPBACK)) add_address(adapter->ifa_addr);
            }
        } else {
            std::cout << "Cannot list network adapters. Check IPv4 with ip addr or ifconfig.\n";
        }
#endif
        std::cout << "0.0.0.0 means all local interfaces; do not enter it in the Android app.\n";
    }
    if (addresses.empty()) {
        std::cout << "No active LAN IPv4 found. Connect the PC to the phone's LAN and restart the server.\n";
    } else {
        for (const auto& address : addresses) std::cout << "PC IPv4: " << address << '\n';
        if (addresses.size() > 1) {
            std::cout << "Multiple addresses: choose the PC Wi-Fi/Ethernet address on the same LAN as the phone, not a VPN/virtual adapter.\n";
        }
        std::cout << "Android: enter only the PC IPv4, without ':' or a port. TCP port " << proto::kTcpPort << " is automatic.\n";
    }
}

void Bind(net::SocketType socket, const std::string& ip, uint16_t port) {
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    if (inet_pton(AF_INET, ip.c_str(), &address.sin_addr) != 1 ||
        (address.sin_addr.s_addr != INADDR_ANY && !LocalAddress(address.sin_addr.s_addr))) {
        throw std::runtime_error("--bind must be a local IPv4 address (or 0.0.0.0)");
    }
    if (bind(socket, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0) {
        throw std::runtime_error("Cannot bind port " + std::to_string(port) + "; another server may be running");
    }
    if (!net::SetTimeouts(socket, 1000)) throw std::runtime_error("Cannot set socket timeouts");
}

bool Send(net::SocketType socket, proto::CtrlType type, const std::string& payload) {
    const auto message = proto::SerializeControl(type, payload);
    return net::SendAll(socket, message.data(), message.size());
}

bool Error(net::SocketType socket, proto::ErrorCode code, const std::string& message) {
    Send(socket, proto::CtrlType::kError, Json{{"code", static_cast<int>(code)}, {"msg", message}}.dump());
    return false;
}

Json ActualSettings(const vr::video::Settings& settings) {
    return {{"codec", "h264"}, {"width", settings.width}, {"height", settings.height},
        {"fps", settings.fps}, {"bitrate_kbps", settings.bitrate / 1000}, {"gop_s", 1},
        {"b_frames", 0}, {"resilience", 1}, {"layout", settings.steamvr || settings.stereo_test_pattern ? "stereo_sbs" : "mono"},
        {"source", settings.steamvr ? "steamvr" : settings.test_pattern ? "test_pattern" : "desktop"},
        {"encoder", "media_foundation_software"}};
}

class VideoWorker {
public:
    std::atomic<bool> ready{false};
    std::atomic<bool> failed{false};
    std::atomic<bool> keyframe{true};
    std::atomic<uint64_t> frames{0}, bytes{0}, work_us{0};

    VideoWorker(net::SocketType socket, sockaddr_in destination, vr::video::Settings settings)
        : worker_([this, socket, destination, settings](std::stop_token stop) {
            try {
                vr::video::VideoSource source(settings);
                ready.store(true);
                {
                    std::unique_lock lock(mutex_);
                    condition_.wait(lock, [&] { return enabled_ || stop.stop_requested(); });
                }
                uint32_t frame_id = 0, sequence = 0;
                uint64_t first_timestamp = 0;
                auto deadline = Clock::now();
                const auto period = std::chrono::microseconds(1'000'000 / settings.fps);
                while (!stop.stop_requested()) {
                    const auto start = Clock::now();
                    auto encoded = source.Next(keyframe.exchange(false));
                    work_us.store(static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                        Clock::now() - start).count()));
                    for (const auto& frame : encoded) {
                        if (stop.stop_requested()) break;
                        if (frame.bytes.size() > proto::kMaxAccessUnitBytes) {
                            throw std::runtime_error("Encoded access unit exceeds 2 MiB limit; lower bitrate/resolution");
                        }
                        if (!first_timestamp) first_timestamp = frame.timestamp_us;
                        const auto timestamp = static_cast<uint32_t>((frame.timestamp_us - first_timestamp) / 1000);
                        const auto total = static_cast<uint16_t>((frame.bytes.size() + proto::kMaxVideoFragmentSize - 1) /
                            proto::kMaxVideoFragmentSize);
                        bool complete = true;
                        for (uint16_t i = 0; i < total && !stop.stop_requested(); ++i) {
                            const size_t offset = i * proto::kMaxVideoFragmentSize;
                            const size_t length = std::min(proto::kMaxVideoFragmentSize, frame.bytes.size() - offset);
                            std::vector<uint8_t> packet;
                            if (!proto::BuildVideoPacket(frame_id, i, total, 0, frame.keyframe ? 0 : 1,
                                    frame.keyframe ? 5 : 1, timestamp, frame.bytes.data() + offset, length, packet, sequence++)) {
                                throw std::runtime_error("Cannot packetize encoded access unit");
                            }
                            const int sent = sendto(socket, reinterpret_cast<const char*>(packet.data()),
                                static_cast<int>(packet.size()), 0,
                                reinterpret_cast<const sockaddr*>(&destination), sizeof(destination));
                            if (sent != static_cast<int>(packet.size())) { complete = false; break; }
                            bytes.fetch_add(static_cast<uint64_t>(sent));
                        }
                        if (complete) frames.fetch_add(1);
                        else keyframe.store(true);
                        ++frame_id;
                    }
                    // Never accumulate a queue or burst old frames after a slow encode.
                    deadline = std::max(deadline + period, Clock::now());
                    std::unique_lock lock(mutex_);
                    condition_.wait_until(lock, deadline, [&] { return stop.stop_requested(); });
                }
            } catch (const std::exception& exception) {
                {
                    std::lock_guard lock(mutex_);
                    error_ = exception.what();
                }
                failed.store(true);
            }
        }) {}

    ~VideoWorker() {
        worker_.request_stop();
        condition_.notify_all();
        worker_.join();
    }

    void Start() {
        { std::lock_guard lock(mutex_); enabled_ = true; }
        condition_.notify_all();
    }
    std::string Failure() { std::lock_guard lock(mutex_); return error_; }

private:
    std::mutex mutex_;
    std::condition_variable condition_;
    bool enabled_ = false;
    std::string error_;
    std::jthread worker_;
};

struct Client {
    Socket socket;
    sockaddr_in peer{}, video_address{}, imu_address{};
    bool authenticated = false, streaming = false, imu_enabled = false, have_imu_sequence = false;
    uint32_t last_imu_sequence = 0;
    uint64_t imu_samples = 0, previous_frames = 0, previous_bytes = 0;
    Clock::time_point connected = Clock::now(), last_read = connected, telemetry_time = connected;
    Clock::time_point last_keyframe_request{};
    std::vector<uint8_t> pending;
    std::unique_ptr<VideoWorker> video;
    vrbox::steamvr::Bridge* vr_bridge = nullptr;
    vrbox::tracking::HeadPose head;
    Clock::time_point last_pose_received{};
    uint64_t pose_received_ms = 0;
    Client(net::SocketType accepted, sockaddr_in address, vrbox::steamvr::Bridge* bridge)
        : socket(accepted), peer(address), vr_bridge(bridge) {}
    ~Client() { if (vr_bridge) { vr_bridge->PublishPose({}); vr_bridge->Heartbeat(false); } }
};

void PublishHead(Client& client) {
    if (!client.vr_bridge || !client.head.HasSample()) return;
    vrbox::steamvr::Pose pose;
    std::copy(client.head.Orientation().begin(), client.head.Orientation().end(), pose.q);
    std::copy(client.head.AngularVelocity().begin(), client.head.AngularVelocity().end(), pose.angular_velocity);
    pose.valid = client.streaming && client.imu_enabled;
    pose.timestamp_ms = client.pose_received_ms;
    client.vr_bridge->PublishPose(pose);
}

uint32_t Number(const Json& object, const char* key, uint32_t minimum, uint32_t maximum) {
    if (!object.contains(key) || !object[key].is_number_integer()) {
        throw std::runtime_error(std::string("Expected integer field: ") + key);
    }
    const auto value = object[key].get<int64_t>();
    if (value < minimum || value > maximum) throw std::runtime_error(std::string("Out of range field: ") + key);
    return static_cast<uint32_t>(value);
}

void RequestKeyframe(Client& client) {
    const auto now = Clock::now();
    if (client.video && now - client.last_keyframe_request >= 500ms) {
        client.video->keyframe.store(true);
        client.last_keyframe_request = now;
    }
}

bool HandleControl(Client& client, proto::CtrlType type, const std::string& payload,
                   const std::string& pin, const vr::video::Settings& settings,
                   net::SocketType video_socket, Clock::time_point& authentication_retry) {
    if (type == proto::CtrlType::kPing && client.authenticated) {
        return payload.size() == 8 && Send(client.socket.value, type, payload);
    }
    try {
        const auto object = Json::parse(payload, [](int depth, Json::parse_event_t, Json&) {
            if (depth > 4) throw std::runtime_error("Control JSON nesting limit exceeded");
            return true;
        });
        if (!object.is_object()) throw std::runtime_error("Control payload must be a JSON object");
        if (!client.authenticated) {
            if (type != proto::CtrlType::kHello) throw std::runtime_error("HELLO must be the first message");
            if (Number(object, "proto", 1, 1) != 1) return false;
            if (object.value("pin", std::string{}) != pin) {
                authentication_retry = Clock::now() + 1s;
                return Error(client.socket.value, proto::ErrorCode::kAuthenticationFailed, "Incorrect pairing PIN");
            }
            if (settings.steamvr || settings.stereo_test_pattern) {
                if (!object.contains("layouts") || !object["layouts"].is_array() ||
                    std::find(object["layouts"].begin(), object["layouts"].end(), Json("stereo_sbs")) == object["layouts"].end()) {
                    return Error(client.socket.value, proto::ErrorCode::kCodecUnsupported, "Update the APK: this mode requires stereo_sbs support");
                }
            }
            if (settings.steamvr) {
                const auto rotation = Number(object, "display_rotation", 0, 270);
                client.head.SetDisplayRotation(rotation);
            }
            client.video_address = client.peer;
            client.imu_address = client.peer;
            client.video_address.sin_port = htons(static_cast<uint16_t>(Number(object, "video_port", 1, 65535)));
            client.imu_address.sin_port = htons(static_cast<uint16_t>(Number(object, "imu_port", 1, 65535)));
            client.authenticated = true;
            return Send(client.socket.value, proto::CtrlType::kHelloAck, Json{
                {"server", "VR Box Server"}, {"proto", 1}, {"codecs", {"h264"}},
                {"features", settings.steamvr ? Json::array({"recenter"}) : Json::array()},
                {"max_res", {settings.width, settings.height}},
                {"ports", {{"tcp", proto::kTcpPort}, {"video", proto::kVideoUdpPort}, {"imu", proto::kImuUdpPort}}}
            }.dump());
        }
        switch (type) {
            case proto::CtrlType::kSettingsRequest:
                if (client.video) throw std::runtime_error("Reconnect to change video settings");
                if (object.value("codec", std::string("h264")) != "h264") {
                    return Error(client.socket.value, proto::ErrorCode::kCodecUnsupported, "Only H.264 is available");
                }
                // The server's CLI preset is authoritative; client fields are bounded preferences.
                if (object.contains("width")) Number(object, "width", 160, 1920);
                if (object.contains("height")) Number(object, "height", 120, 1080);
                if (object.contains("fps")) Number(object, "fps", 1, 60);
                if (object.contains("bitrate_kbps")) Number(object, "bitrate_kbps", 100, 50000);
                client.video = std::make_unique<VideoWorker>(video_socket, client.video_address, settings);
                return true;
            case proto::CtrlType::kKeyframeRequest:
                RequestKeyframe(client);
                return true;
            case proto::CtrlType::kImuStart:
                if (!client.streaming) throw std::runtime_error("Start video before IMU");
                client.imu_enabled = true;
                return Send(client.socket.value, proto::CtrlType::kSettingsAck, R"({"imu_rate_hz":100})");
            case proto::CtrlType::kImuStop:
                client.imu_enabled = false;
                if (client.vr_bridge) client.vr_bridge->PublishPose({});
                return true;
            case proto::CtrlType::kRecenter: {
                const bool ready = settings.steamvr && client.streaming && client.imu_enabled &&
                    Clock::now() - client.last_pose_received < 250ms && client.head.Recenter();
                if (ready) PublishHead(client);
                return Send(client.socket.value, proto::CtrlType::kSettingsAck,
                    Json{{"recentered", ready}, {"reason", ready ? "View centered" : "Waiting for fresh head tracking"}}.dump());
            }
            case proto::CtrlType::kClientTelemetry:
                return true;
            case proto::CtrlType::kSessionEnd:
                return false;
            default:
                throw std::runtime_error("Unexpected control message for current session state");
        }
    } catch (const std::exception& exception) {
        return Error(client.socket.value, proto::ErrorCode::kInvalidRequest, exception.what());
    }
}

bool ReadControl(Client& client, const std::string& pin, const vr::video::Settings& settings,
                 net::SocketType video_socket, Clock::time_point& authentication_retry) {
    std::array<uint8_t, 8192> data{};
    const int count = recv(client.socket.value, reinterpret_cast<char*>(data.data()), static_cast<int>(data.size()), 0);
    if (count <= 0) return false;
    client.last_read = Clock::now();
    client.pending.insert(client.pending.end(), data.begin(), data.begin() + count);
    size_t consumed = 0;
    while (client.pending.size() - consumed >= sizeof(proto::ControlHeader)) {
        proto::ControlHeader header{};
        std::memcpy(&header, client.pending.data() + consumed, sizeof(header));
        if (header.magic != proto::kMagic || header.version != proto::kVersion ||
            header.payload_len > proto::kMaxControlPayload) return false;
        const size_t length = sizeof(header) + header.payload_len;
        if (length > client.pending.size() - consumed) break;
        std::string payload;
        if (!proto::DeserializeControl(client.pending.data() + consumed, length, header, payload) ||
            !HandleControl(client, static_cast<proto::CtrlType>(header.msg_type), payload,
                           pin, settings, video_socket, authentication_retry)) return false;
        consumed += length;
    }
    if (consumed) client.pending.erase(client.pending.begin(), client.pending.begin() + consumed);
    return client.pending.size() <= proto::kMaxControlPayload + sizeof(proto::ControlHeader);
}

bool SameEndpoint(const sockaddr_in& left, const sockaddr_in& right) {
    return left.sin_addr.s_addr == right.sin_addr.s_addr && left.sin_port == right.sin_port;
}

void ReceiveUdp(net::SocketType socket, bool imu, Client* client) {
    std::array<uint8_t, proto::kMaxDatagramSize + 1> data{};
    sockaddr_in from{};
    net::AddressLength address_length = sizeof(from);
    const int count = recvfrom(socket, reinterpret_cast<char*>(data.data()), static_cast<int>(data.size()), 0,
                              reinterpret_cast<sockaddr*>(&from), &address_length);
    if (count <= 0 || !client || !client->authenticated ||
        !SameEndpoint(from, imu ? client->imu_address : client->video_address)) return;
    proto::DatagramHeader header{};
    std::vector<uint8_t> payload;
    if (!proto::DatagramBuilder::Parse(data.data(), static_cast<size_t>(count), header, payload)) return;
    if (!imu && header.channel == static_cast<uint8_t>(proto::Channel::kTiming) && payload.size() == sizeof(proto::TimingPayload)) {
        proto::TimingPayload timing{};
        std::memcpy(&timing, payload.data(), sizeof(timing));
        if (timing.reserved) return;
        sendto(socket, reinterpret_cast<const char*>(data.data()), count, 0,
               reinterpret_cast<const sockaddr*>(&from), sizeof(from));
    } else if (!imu && header.channel == static_cast<uint8_t>(proto::Channel::kNack) && payload.size() >= sizeof(proto::NackPayload)) {
        proto::NackPayload nack{};
        std::memcpy(&nack, payload.data(), sizeof(nack));
        if (payload.size() == sizeof(nack) + nack.frag_count * sizeof(uint16_t)) RequestKeyframe(*client);
    } else if (imu && client->imu_enabled && header.channel == static_cast<uint8_t>(proto::Channel::kImu) &&
               payload.size() >= sizeof(proto::ImuPayloadHeader)) {
        proto::ImuPayloadHeader info{};
        std::memcpy(&info, payload.data(), sizeof(info));
        if (info.sample_format != 0 || info.reserved[0] || info.reserved[1] || info.sample_count < 1 || info.sample_count > 24 ||
            payload.size() != sizeof(info) + info.sample_count * sizeof(proto::ImuSample)) return;
        const uint32_t delta = info.sample_seq - client->last_imu_sequence;
        if (client->have_imu_sequence && (delta == 0 || delta >= 0x80000000u)) return;
        for (uint8_t i = 0; i < info.sample_count; ++i) {
            proto::ImuSample sample{};
            std::memcpy(&sample, payload.data() + sizeof(info) + i * sizeof(sample), sizeof(sample));
            float norm = 0;
            for (float value : sample.q) { if (!std::isfinite(value)) return; norm += value * value; }
            for (float value : sample.g) if (!std::isfinite(value)) return;
            for (float value : sample.a) if (!std::isfinite(value)) return;
            if (norm < 0.5f || norm > 1.5f) return;
        }
        client->have_imu_sequence = true;
        client->last_imu_sequence = info.sample_seq;
        client->imu_samples += info.sample_count;
        if (client->vr_bridge && client->streaming) {
            proto::ImuSample sample{};
            std::memcpy(&sample, payload.data() + sizeof(info) + (info.sample_count - 1) * sizeof(sample), sizeof(sample));
            if (client->head.Update(sample.q, sample.g)) {
                client->last_pose_received = Clock::now();
#ifdef _WIN32
                client->pose_received_ms = GetTickCount64();
#endif
                PublishHead(*client);
            }
        }
    }
}

void Discovery(net::SocketType socket, Clock::time_point& last_response) {
    std::array<uint8_t, 128> data{};
    sockaddr_in from{};
    net::AddressLength address_length = sizeof(from);
    const int count = recvfrom(socket, reinterpret_cast<char*>(data.data()), static_cast<int>(data.size()), 0,
                              reinterpret_cast<sockaddr*>(&from), &address_length);
    if (count < static_cast<int>(sizeof(proto::DiscoveryHeader)) || !LocalAddress(from.sin_addr.s_addr) ||
        Clock::now() - last_response < 100ms) return;
    proto::DiscoveryHeader header{};
    std::memcpy(&header, data.data(), sizeof(header));
    if (header.magic != proto::kMagic || header.version != proto::kVersion ||
        header.type != static_cast<uint8_t>(proto::DiscoveryType::kReq) || header.payload_len > 64 ||
        static_cast<size_t>(count) != sizeof(header) + header.payload_len) return;
    std::vector<uint8_t> response;
    proto::BuildDiscoveryResponse(proto::kTcpPort, proto::kVideoUdpPort, proto::kImuUdpPort, 1,
                                  "VR Box Desktop Preview", response);
    sendto(socket, reinterpret_cast<const char*>(response.data()), static_cast<int>(response.size()), 0,
           reinterpret_cast<const sockaddr*>(&from), sizeof(from));
    last_response = Clock::now();
}

uint32_t ArgumentNumber(const std::string& value, uint32_t min, uint32_t max) {
    if (value.empty() || value.find_first_not_of("0123456789") != std::string::npos) {
        throw std::runtime_error("Expected a positive numeric argument");
    }
    const auto number = std::stoull(value);
    if (number < min || number > max) throw std::runtime_error("Numeric argument out of range");
    return static_cast<uint32_t>(number);
}

float ArgumentFloat(const std::string& value, float minimum, float maximum) {
    size_t end = 0;
    const float result = std::stof(value, &end);
    if (end != value.size() || !std::isfinite(result) || result < minimum || result > maximum) {
        throw std::runtime_error("Floating-point argument out of range");
    }
    return result;
}
} // namespace

int main(int argc, char** argv) {
    try {
        vr::video::Settings settings;
        vrbox::steamvr::Config vr_config;
        std::string bind_ip = "0.0.0.0", pin;
        uint32_t run_seconds = 0;
        bool fps_specified = false, bitrate_specified = false;
        for (int i = 1; i < argc; ++i) {
            const std::string option = argv[i];
            if (option == "--help") {
                std::cout << "VR Box desktop / experimental SteamVR 3DoF server\n"
                    "  --test-pattern         Stream generated pixels instead of the primary desktop\n"
                    "  --stereo-test-pattern  Different synthetic pictures per eye, no desktop capture\n"
                    "  --steamvr              Stream SteamVR compositor; requires registered vrbox driver\n"
                    "  --ipd-mm 45..80         VR eye separation in millimeters (default 64)\n"
                    "  --fov-y 35..110         VR vertical field of view in degrees (default 75)\n"
                    "  --bind IPv4            Local bind address (default 0.0.0.0)\n"
                    "  --pin NNNNNN           Pairing PIN (default: random, printed on startup)\n"
                    "  --width 160..1920      Even encoded width (default 1280)\n"
                    "  --height 120..1080     Even encoded height (default 720)\n"
                    "  --fps 1..60            Frame rate (default 30)\n"
                    "  --bitrate-kbps 100..50000 (default 8000)\n"
                    "  --run-seconds 1..3600  Exit automatically, useful for tests\n"
                    "Only trusted local networks: video/control are not encrypted. Ctrl+C stops.\n";
                return 0;
            }
            if (option == "--test-pattern") { settings.test_pattern = true; continue; }
            if (option == "--stereo-test-pattern") { settings.test_pattern = true; settings.stereo_test_pattern = true; continue; }
            if (option == "--steamvr") { settings.steamvr = true; continue; }
            if (i + 1 >= argc) throw std::runtime_error("Missing argument for " + option);
            const std::string value = argv[++i];
            if (option == "--bind") bind_ip = value;
            else if (option == "--pin") pin = value;
            else if (option == "--width") settings.width = ArgumentNumber(value, 160, 1920);
            else if (option == "--height") settings.height = ArgumentNumber(value, 120, 1080);
            else if (option == "--fps") { settings.fps = ArgumentNumber(value, 1, 60); fps_specified = true; }
            else if (option == "--bitrate-kbps") { settings.bitrate = ArgumentNumber(value, 100, 50000) * 1000; bitrate_specified = true; }
            else if (option == "--ipd-mm") vr_config.ipd_m = ArgumentFloat(value, 45.f, 80.f) / 1000.f;
            else if (option == "--fov-y") vr_config.fov_y_degrees = ArgumentFloat(value, 35.f, 110.f);
            else if (option == "--run-seconds") run_seconds = ArgumentNumber(value, 1, 3600);
            else throw std::runtime_error("Unknown option: " + option);
        }
        if (settings.width % 2 || settings.height % 2) throw std::runtime_error("Video dimensions must be even");
        if (settings.steamvr && settings.test_pattern) throw std::runtime_error("Choose SteamVR OR a test pattern, not both");
        if ((settings.steamvr || settings.stereo_test_pattern) && settings.width % 4) throw std::runtime_error("Stereo total width must be divisible by four");
        if (settings.steamvr) {
#ifndef _WIN32
            throw std::runtime_error("SteamVR streaming requires Windows");
#endif
            if (!fps_specified) settings.fps = 60;
            if (!bitrate_specified) settings.bitrate = 12'000'000;
        }
        if (pin.empty()) {
            std::random_device random;
            std::ostringstream text;
            text << std::setfill('0') << std::setw(6) << std::uniform_int_distribution<int>(0, 999999)(random);
            pin = text.str();
        }
        if (pin.size() != 6 || pin.find_first_not_of("0123456789") != std::string::npos) {
            throw std::runtime_error("Pairing PIN must contain exactly six digits");
        }
        NetworkRuntime network;
        Socket video_socket(net::UdpSocket(false)), imu_socket(net::UdpSocket(false));
        Socket discovery_socket(net::UdpSocket(false)), listener(net::TcpSocket(false));
        Bind(video_socket.value, bind_ip, proto::kVideoUdpPort);
        Bind(imu_socket.value, bind_ip, proto::kImuUdpPort);
        Bind(discovery_socket.value, bind_ip, proto::kDiscoveryUdpPort);
        Bind(listener.value, bind_ip, proto::kTcpPort);
        net::SetSendBufferSize(video_socket.value, 1 << 20);
        net::SetRecvBufferSize(video_socket.value, 1 << 16);
        net::SetUdpDoNotFragment(video_socket.value);
        if (!net::SetNonBlocking(listener.value, true)) throw std::runtime_error("Cannot configure TCP listener");
        if (listen(listener.value, 4) != 0) throw std::runtime_error("Cannot listen for control connections");
        std::unique_ptr<vrbox::steamvr::Bridge> vr_bridge;
        if (settings.steamvr) {
            vr_bridge = std::make_unique<vrbox::steamvr::Bridge>();
            vr_config.width = settings.width; vr_config.height = settings.height; vr_config.fps = settings.fps;
            if (!vr_bridge->Open(true) || !vr_bridge->Configure(vr_config)) {
                throw std::runtime_error("Cannot own the SteamVR bridge; another VR Box server may be active");
            }
        }
        std::signal(SIGINT, StopSignal);
        std::signal(SIGTERM, StopSignal);
        std::cout << "VR Box Server 0.3\nSource: " << (settings.steamvr ? "SteamVR compositor" : settings.test_pattern ? "test pattern" : "primary desktop")
            << "; H.264 software encoder; " << (settings.steamvr || settings.stereo_test_pattern ? "stereo SBS" : "mono") << '\n'
            << "Pairing PIN: " << pin << "\n";
        PrintConnectionAddresses(bind_ip);
        if (settings.steamvr) std::cout << "Experimental seated 3DoF. IPD " << vr_config.ipd_m * 1000 << " mm; vertical FOV "
            << vr_config.fov_y_degrees << " deg. Use Recenter in the APK while looking forward.\n"
            << "No desktop fallback. Start SteamVR after this server. Software encoding is not optimized for VR latency.\n";
        std::cout << "Trusted LAN only. Video/control are not encrypted.\n"
            << "Server ready on " << bind_ip << ":8765 (UDP 8766/8767/8768). Ctrl+C to stop.\n" << std::flush;
        const auto start = Clock::now();
        std::unique_ptr<Client> client;
        Clock::time_point authentication_retry{}, last_discovery{};
        while (!stop_requested && (!run_seconds || Clock::now() - start < std::chrono::seconds(run_seconds))) {
            if (vr_bridge) vr_bridge->Heartbeat(client && client->streaming);
            fd_set readers;
            FD_ZERO(&readers);
            for (const auto socket : {listener.value, video_socket.value, imu_socket.value, discovery_socket.value}) {
                FD_SET(socket, &readers);
            }
            if (client) FD_SET(client->socket.value, &readers);
            timeval timeout{0, 100'000};
#ifdef _WIN32
            const int nfds = 0;
#else
            const int nfds = std::max({listener.value, video_socket.value, imu_socket.value, discovery_socket.value,
                                       client ? client->socket.value : 0}) + 1;
#endif
            const int selected = select(nfds, &readers, nullptr, nullptr, &timeout);
            if (selected < 0) { if (stop_requested) break; throw std::runtime_error("Socket select failed"); }
            if (selected > 0) {
                // Read the old client before accepting a new one (fd_set describes the old snapshot).
                if (client && FD_ISSET(client->socket.value, &readers) &&
                    !ReadControl(*client, pin, settings, video_socket.value, authentication_retry)) {
                    client.reset();
                    std::cout << "Client disconnected; ready for another session.\n" << std::flush;
                }
                if (FD_ISSET(video_socket.value, &readers)) ReceiveUdp(video_socket.value, false, client.get());
                if (FD_ISSET(imu_socket.value, &readers)) ReceiveUdp(imu_socket.value, true, client.get());
                if (FD_ISSET(discovery_socket.value, &readers)) Discovery(discovery_socket.value, last_discovery);
                if (FD_ISSET(listener.value, &readers)) {
                    sockaddr_in peer{};
                    net::AddressLength length = sizeof(peer);
                    const auto accepted = accept(listener.value, reinterpret_cast<sockaddr*>(&peer), &length);
                    if (accepted != net::kInvalidSocket) {
                        if (!net::SetNonBlocking(accepted, false) || !net::SetTimeouts(accepted, 1000) ||
                            !LocalAddress(peer.sin_addr.s_addr) || Clock::now() < authentication_retry) net::Close(accepted);
                        else if (client) {
                            Error(accepted, proto::ErrorCode::kSessionBusy, "Another client is connected");
                            net::Close(accepted);
                        } else client = std::make_unique<Client>(accepted, peer, vr_bridge.get());
                    }
                }
            }
            if (!client) continue;
            const auto now = Clock::now();
            if ((!client->authenticated && now - client->connected > 3s) || now - client->last_read > 10s) {
                Error(client->socket.value, proto::ErrorCode::kInvalidRequest, "Session timed out");
                client.reset();
                continue;
            }
            if (client->video && client->video->failed.load()) {
                const auto message = client->video->Failure();
                std::cerr << "Video error: " << message << '\n';
                Error(client->socket.value, proto::ErrorCode::kEncoderError, message);
                client.reset();
                continue;
            }
            if (client->video && client->video->ready.load() && !client->streaming) {
                const auto actual = ActualSettings(settings).dump();
                if (!Send(client->socket.value, proto::CtrlType::kSettingsAck, actual) ||
                    !Send(client->socket.value, proto::CtrlType::kVideoStart, actual)) {
                    client.reset();
                    continue;
                }
                client->streaming = true;
                client->telemetry_time = now;
                client->video->Start();
                std::cout << "Streaming " << settings.width << 'x' << settings.height << " at " << settings.fps << " fps.\n" << std::flush;
            }
            if (client->streaming && now - client->telemetry_time >= 1s) {
                const double seconds = std::chrono::duration<double>(now - client->telemetry_time).count();
                const auto frames = client->video->frames.load(), bytes = client->video->bytes.load();
                Json telemetry{{"fps", (frames - client->previous_frames) / seconds},
                    {"br_kbps", (bytes - client->previous_bytes) * 8 / (seconds * 1000)},
                    {"capture_encode_ms", client->video->work_us.load() / 1000.0}, {"imu_samples", client->imu_samples}};
                if (vr_bridge) {
                    const auto state = vr_bridge->ReadState();
                    telemetry["steamvr_frames"] = state.frame_count;
                    telemetry["head_tracking"] = state.pose.valid;
                }
                if (!Send(client->socket.value, proto::CtrlType::kTelemetry, telemetry.dump())) { client.reset(); continue; }
                client->previous_frames = frames;
                client->previous_bytes = bytes;
                client->telemetry_time = now;
            }
        }
        if (client) Send(client->socket.value, proto::CtrlType::kVideoStop, "{}");
        client.reset(); // Join the encoder before destroying its UDP socket or Winsock.
        std::cout << "Server stopped.\n";
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "Server error: " << exception.what() << '\n';
        return 1;
    }
}
