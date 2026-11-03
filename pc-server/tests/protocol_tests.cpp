#include "vr/protocol.hpp"

#include <array>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace vr::proto;
namespace {
int checks = 0;
void Require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
void FixHeaderCrc(std::vector<uint8_t>& packet) {
    const uint16_t crc = crc16(packet.data(), 12);
    std::memcpy(packet.data() + 12, &crc, 2);
}
}

int main() {
    try {
        Require(crc16(reinterpret_cast<const uint8_t*>("123456789"), 9) == 0x29B1, "CCITT-FALSE check vector");
        Require(crc16(nullptr, 0) == 0xFFFF, "Empty CRC");
        std::vector<uint8_t> packet, payload;
        DatagramHeader header{};
        Require(BuildTimingPacket(0x0102030405060708ULL, 0x0A0B0C0D, packet), "Build timing");
        const std::vector<uint8_t> timing_golden{
            0x52, 0x56, 1, 3, 0, 0, 0x0D, 0x0C, 0x0B, 0x0A, 14, 0, 0xA7, 0xF5,
            8, 7, 6, 5, 4, 3, 2, 1, 0x0D, 0x0C, 0x0B, 0x0A, 0, 0
        };
        Require(packet == timing_golden, "Timing wire golden (shared with Kotlin)");
        Require(DatagramBuilder::Parse(packet.data(), packet.size(), header, payload), "Parse timing");
        Require(payload.size() == 14 && header.sequence == 0x0A0B0C0D, "Timing lengths and sequence");
        for (size_t i = 0; i < 14; ++i) {
            auto bad = packet;
            bad[i] ^= 1;
            Require(!DatagramBuilder::Parse(bad.data(), bad.size(), header, payload), "Corrupted header accepted");
        }
        for (size_t i = 0; i < packet.size(); ++i) {
            Require(!DatagramBuilder::Parse(packet.data(), i, header, payload), "Truncated datagram accepted");
        }
        auto bad = packet;
        bad.push_back(0);
        Require(!DatagramBuilder::Parse(bad.data(), bad.size(), header, payload), "Trailing data accepted");
        bad = packet;
        bad[4] = 2;
        FixHeaderCrc(bad);
        Require(!DatagramBuilder::Parse(bad.data(), bad.size(), header, payload), "Unknown flags accepted");
        bad = packet;
        bad[5] = 1;
        FixHeaderCrc(bad);
        Require(!DatagramBuilder::Parse(bad.data(), bad.size(), header, payload), "Reserved byte accepted");
        bad = packet;
        bad[3] = 9;
        FixHeaderCrc(bad);
        Require(!DatagramBuilder::Parse(bad.data(), bad.size(), header, payload), "Unknown channel accepted");

        auto checked = packet;
        const uint16_t payload_crc = crc16(checked.data() + 14, checked.size() - 14);
        checked.push_back(static_cast<uint8_t>(payload_crc));
        checked.push_back(static_cast<uint8_t>(payload_crc >> 8));
        checked[4] = 1;
        checked[10] = 16;
        FixHeaderCrc(checked);
        Require(DatagramBuilder::Parse(checked.data(), checked.size(), header, payload) && payload.size() == 14,
                "Payload CRC must be verified and removed");
        checked[14] ^= 1;
        Require(!DatagramBuilder::Parse(checked.data(), checked.size(), header, payload), "Bad payload CRC accepted");

        Require(BuildNackPacket(0x01020304, nullptr, 0, packet), "Whole-frame NACK must be valid");
        Require(packet.size() == 20, "NACK zero count size");
        Require(DatagramBuilder::Parse(packet.data(), packet.size(), header, payload), "Parse NACK");
        Require(payload == std::vector<uint8_t>({4, 3, 2, 1, 0, 0}), "NACK wire payload");
        const uint16_t frags[]{1, 3, 5};
        Require(BuildNackPacket(4, frags, 3, packet) && packet.size() == 26, "Fragment NACK size");
        Require(!BuildNackPacket(4, nullptr, 1, packet), "Null NACK array");
        Require(!BuildNackPacket(4, frags, 65536, packet), "Oversized NACK count");

        ImuSample sample{};
        sample.q[3] = 1;
        Require(BuildImuPacket(123, 7, &sample, 1, packet) && packet.size() == 70, "IMU 16 + 40 byte payload");
        Require(DatagramBuilder::Parse(packet.data(), packet.size(), header, payload) && header.sequence == 7, "IMU sequence");
        Require(payload[12] == 1 && payload[13] == 0 && payload[14] == 0 && payload[15] == 0, "IMU count/format");
        ImuSample decoded{};
        std::memcpy(&decoded, payload.data() + 16, sizeof(decoded));
        Require(decoded.q[3] == 1 && decoded.q[0] == 0, "IMU quaternion layout");
        Require(!BuildImuPacket(0, 0, &sample, 25, packet), "IMU oversized sample count");
        Require(!BuildImuPacket(0, 0, nullptr, 1, packet), "IMU null samples");

        std::vector<uint8_t> fragment(kMaxVideoFragmentSize, 0xAA);
        Require(BuildVideoPacket(42, 0, 2, 0, 0, 5, 50, fragment.data(), fragment.size(), packet, 17), "Build video fragment");
        Require(packet.size() == 1200, "Video datagram MTU cap includes both headers");
        Require(DatagramBuilder::Parse(packet.data(), packet.size(), header, payload) && header.sequence == 17, "Video sequence");
        VideoPayloadHeader video{};
        std::memcpy(&video, payload.data(), sizeof(video));
        Require(video.frame_id == 42 && video.frag_total == 2 && video.frame_ts_ms == 50 && video.nal_type_hint == 5,
                "Video metadata round trip");
        Require(!BuildVideoPacket(0, 1, 1, 0, 0, 5, 0, fragment.data(), 1, packet), "Invalid video fragment index");
        Require(!BuildVideoPacket(0, 0, 0, 0, 0, 5, 0, fragment.data(), 1, packet), "Zero fragment count");
        Require(!BuildVideoPacket(0, 0, 2049, 0, 0, 5, 0, fragment.data(), 1, packet), "Unbounded fragment count");
        Require(!BuildVideoPacket(0, 0, 1, 0, 0, 5, 0, nullptr, 1, packet), "Null video bytes");
        fragment.push_back(0);
        Require(!BuildVideoPacket(0, 0, 1, 0, 0, 5, 0, fragment.data(), fragment.size(), packet), "Oversized fragment");
        Require(!DatagramBuilder::Build(1, nullptr, 1, packet), "Null generic payload");

        Require(BuildDiscoveryResponse(kTcpPort, kVideoUdpPort, kImuUdpPort, 1, "PC", packet), "Discovery response");
        Require(packet.size() == 21 && packet[3] == 0x11, "Discovery envelope and fixed size");
        DiscoveryHeader discovery{};
        std::memcpy(&discovery, packet.data(), sizeof(discovery));
        Require(discovery.magic == kMagic && discovery.payload_len == 15, "Discovery payload length");
        DiscoveryResponseFixed response{};
        std::memcpy(&response, packet.data() + sizeof(discovery), sizeof(response));
        Require(response.tcp_port == 8765 && response.video_port == 8766 && response.imu_port == 8767 &&
                response.codec_mask == 1 && response.name_len == 2, "Discovery ports/codecs/name");
        Require(!BuildDiscoveryRequest(std::string(65, 'x'), packet), "Oversized discovery name");

        const auto message = SerializeControl(CtrlType::kHello, "{}");
        ControlHeader control{};
        std::string json;
        for (size_t i = 0; i < message.size(); ++i) {
            Require(!DeserializeControl(reinterpret_cast<const uint8_t*>(message.data()), i, control, json),
                    "Partial TCP message must not parse");
        }
        Require(DeserializeControl(reinterpret_cast<const uint8_t*>(message.data()), message.size(), control, json) &&
                json == "{}" && control.msg_type == 1, "Control round trip");
        const auto large = SerializeControl(CtrlType::kHello, std::string(kMaxControlPayload, 'a'));
        Require(DeserializeControl(reinterpret_cast<const uint8_t*>(large.data()), large.size(), control, json), "Control maximum body");
        bool threw = false;
        try { SerializeControl(CtrlType::kHello, std::string(kMaxControlPayload + 1, 'a')); }
        catch (const std::length_error&) { threw = true; }
        Require(threw, "Oversized control body must fail");
        std::cout << "Protocol tests passed (" << checks << " checks).\n";
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "Protocol test failed: " << exception.what() << '\n';
        return 1;
    }
}
