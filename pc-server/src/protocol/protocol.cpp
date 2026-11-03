// protocol.cpp — реализация протокола VR Box Connect v1
// Соответствует spec docs/PROTOCOL.md.

#include "vr/protocol.hpp"
#include <cstring>
#include <algorithm>
#include <bit>
#include <stdexcept>

namespace vr::proto {

// ── CRC16-CCITT-FALSE ───────────────────────────────────────────────────────
static_assert(std::endian::native == std::endian::little, "Wire structs require a little-endian host");
// Non-reflected CCITT-FALSE: check value for "123456789" is 0x29B1.
uint16_t crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int b = 0; b < 8; ++b) {
            crc = static_cast<uint16_t>((crc & 0x8000) ? (crc << 1) ^ 0x1021 : (crc << 1));
        }
    }
    return crc;
}

// ── Общие датаграммы (§2) ────────────────────────────────────────────────────

bool DatagramBuilder::Build(uint8_t channel, const uint8_t* payload, size_t plen,
                            std::vector<uint8_t>& out, uint32_t sequence) {
    if (plen > kMaxDatagramSize - sizeof(DatagramHeader) || (plen && !payload) ||
        channel < 1 || channel > 4) return false;

    out.assign(14 + plen, 0);
    auto* hdr = reinterpret_cast<DatagramHeader*>(out.data());
    hdr->magic     = kMagic;
    hdr->version   = kVersion;
    hdr->channel   = channel;
    hdr->flags     = 0;
    hdr->reserved  = 0;
    hdr->sequence  = sequence;
    hdr->payload_len = static_cast<uint16_t>(plen);

    if (plen) std::memcpy(out.data() + 14, payload, plen);

    // CRC вычисляется над первыми 12 байтами заголовка (§2)
    hdr->crc = crc16(out.data(), 12);
    return true;
}

bool DatagramBuilder::Parse(const uint8_t* buf, size_t len,
                            DatagramHeader& hdr, std::vector<uint8_t>& payload) {
    if (!buf || len < 14 || len > kMaxDatagramSize) return false;

    std::memcpy(&hdr, buf, sizeof(hdr));

    if (hdr.magic != kMagic) return false;
    if (hdr.version != kVersion) return false;
    if (hdr.channel < 1 || hdr.channel > 4 || hdr.reserved != 0 ||
        (hdr.flags & ~kFlagPayloadCrc) != 0) return false;

    uint16_t computed_crc = crc16(buf, 12);
    if (computed_crc != hdr.crc) return false;

    if (hdr.payload_len != len - 14) return false;

    size_t payload_size = hdr.payload_len;
    if (hdr.flags & kFlagPayloadCrc) {
        if (payload_size < 2) return false;
        payload_size -= 2;
        uint16_t expected = 0;
        std::memcpy(&expected, buf + 14 + payload_size, 2);
        if (crc16(buf + 14, payload_size) != expected) return false;
    }
    payload.assign(buf + 14, buf + 14 + payload_size);
    return true;
}

// ── Video (§3) ───────────────────────────────────────────────────────────────

bool BuildVideoPacket(uint32_t frame_id, uint16_t frag_idx, uint16_t frag_total,
                      uint8_t codec, uint8_t frame_type, uint8_t nal_hint,
                      uint32_t frame_ts_ms, const uint8_t* nalu, size_t nalu_len,
                      std::vector<uint8_t>& out, uint32_t sequence) {
    if (!nalu || nalu_len == 0 || nalu_len > kMaxVideoFragmentSize ||
        frag_total == 0 || frag_total > kMaxVideoFragments || frag_idx >= frag_total ||
        codec > 1 || frame_type > 3) return false;
    VideoPayloadHeader vph{};
    vph.frag_index   = frag_idx;
    vph.frag_total   = frag_total;
    vph.frame_id     = frame_id;
    vph.codec        = codec;
    vph.frame_type   = frame_type;
    vph.nal_type_hint = nal_hint;
    vph.frame_ts_ms  = frame_ts_ms;

    std::vector<uint8_t> payload(sizeof(vph) + nalu_len);
    std::memcpy(payload.data(), &vph, sizeof(vph));
    if (nalu_len > 0 && nalu != nullptr)
        std::memcpy(payload.data() + sizeof(vph), nalu, nalu_len);

    return DatagramBuilder::Build(static_cast<uint8_t>(Channel::kVideo), payload.data(), payload.size(), out, sequence);
}

// ── IMU (§6) ─────────────────────────────────────────────────────────────────

bool BuildImuPacket(uint64_t timestamp_ns, uint32_t sample_seq,
                    const ImuSample* samples, size_t count,
                    std::vector<uint8_t>& out) {
    if (count == 0 || count > 24) return false;
    if (samples == nullptr) return false;

    std::vector<uint8_t> payload(sizeof(ImuPayloadHeader) + sizeof(ImuSample) * count);
    auto* hdr = reinterpret_cast<ImuPayloadHeader*>(payload.data());
    hdr->timestamp_ns = timestamp_ns;
    hdr->sample_seq   = sample_seq;
    hdr->sample_count = static_cast<uint8_t>(count);
    hdr->sample_format = 0;
    std::memset(hdr->reserved, 0, sizeof(hdr->reserved));

    std::memcpy(payload.data() + sizeof(ImuPayloadHeader),
                samples, sizeof(ImuSample) * count);

    return DatagramBuilder::Build(static_cast<uint8_t>(Channel::kImu), payload.data(), payload.size(), out, sample_seq);
}

// ── Timing (§7) ──────────────────────────────────────────────────────────────

bool BuildTimingPacket(uint64_t send_ns, uint32_t seq, std::vector<uint8_t>& out) {
    TimingPayload tp{};
    tp.send_ns = send_ns;
    tp.seq     = seq;
    return DatagramBuilder::Build(static_cast<uint8_t>(Channel::kTiming),
                                  reinterpret_cast<uint8_t*>(&tp), sizeof(tp),
                                  out, seq);
}

// ── NACK (§3.4) ──────────────────────────────────────────────────────────────

bool BuildNackPacket(uint32_t frame_id, const uint16_t* frags, size_t count,
                     std::vector<uint8_t>& out) {
    if (count > (kMaxDatagramSize - 14 - sizeof(NackPayload)) / sizeof(uint16_t) ||
        (count && !frags)) return false;
    size_t plen = sizeof(NackPayload) + sizeof(uint16_t) * count;
    std::vector<uint8_t> payload(plen, 0);
    auto* np = reinterpret_cast<NackPayload*>(payload.data());
    np->frame_id = frame_id;
    np->frag_count = static_cast<uint16_t>(count);

    if (frags != nullptr)
        std::memcpy(reinterpret_cast<uint8_t*>(np) + sizeof(NackPayload),
                    frags, sizeof(uint16_t) * count);

    return DatagramBuilder::Build(static_cast<uint8_t>(Channel::kNack), payload.data(), plen, out);
}

// ── TCP Control (§5) ─────────────────────────────────────────────────────────

std::string SerializeControl(CtrlType type, const std::string& json_payload) {
    if (json_payload.size() > kMaxControlPayload) throw std::length_error("Control payload exceeds 64 KiB");
    std::string buf;
    buf.resize(sizeof(ControlHeader) + json_payload.size());

    auto* hdr = reinterpret_cast<ControlHeader*>(buf.data());
    hdr->magic     = kMagic;
    hdr->version   = kVersion;
    hdr->msg_type  = static_cast<uint8_t>(type);
    hdr->payload_len = static_cast<uint32_t>(json_payload.size());

    std::memcpy(buf.data() + sizeof(ControlHeader),
                json_payload.data(), json_payload.size());
    return buf;
}

bool DeserializeControl(const uint8_t* buf, size_t len,
                        ControlHeader& hdr, std::string& payload) {
    if (!buf || len < sizeof(ControlHeader)) return false;
    std::memcpy(&hdr, buf, sizeof(hdr));
    if (hdr.magic != kMagic) return false;
    if (hdr.version != kVersion) return false;
    if (hdr.payload_len > kMaxControlPayload || hdr.payload_len != len - sizeof(ControlHeader)) return false;
    if (hdr.msg_type < 1 || hdr.msg_type > static_cast<uint8_t>(CtrlType::kRecenter)) return false;

    payload.assign(reinterpret_cast<const char*>(buf + sizeof(ControlHeader)),
                   hdr.payload_len);
    return true;
}

// ── Discovery (§4) ───────────────────────────────────────────────────────────

bool BuildDiscoveryRequest(const std::string& device_name, std::vector<uint8_t>& out) {
    if (device_name.size() > 64) return false;
    std::vector<uint8_t> payload(6 + device_name.size());
    auto* hdr = reinterpret_cast<DiscoveryHeader*>(payload.data());
    hdr->magic     = kMagic;
    hdr->version   = kVersion;
    hdr->type      = static_cast<uint8_t>(DiscoveryType::kReq);
    hdr->payload_len = static_cast<uint16_t>(device_name.size());

    if (!device_name.empty())
        std::memcpy(payload.data() + sizeof(DiscoveryHeader),
                    device_name.data(), device_name.size());

    out.swap(payload);
    return true;
}

bool BuildDiscoveryResponse(uint16_t tcp_port, uint16_t video_port, uint16_t imu_port,
                             uint32_t codec_mask, const std::string& server_name,
                             std::vector<uint8_t>& out) {
    size_t name_len = std::min(server_name.size(), size_t(255));
    std::vector<uint8_t> payload(sizeof(DiscoveryHeader) + sizeof(DiscoveryResponseFixed) + name_len);
    auto* header = reinterpret_cast<DiscoveryHeader*>(payload.data());
    header->magic = kMagic;
    header->version = kVersion;
    header->type = static_cast<uint8_t>(DiscoveryType::kRes);
    header->payload_len = static_cast<uint16_t>(sizeof(DiscoveryResponseFixed) + name_len);
    auto* resp = reinterpret_cast<DiscoveryResponseFixed*>(payload.data() + sizeof(DiscoveryHeader));
    resp->protocol_version = 1;
    resp->tcp_port         = tcp_port;
    resp->video_port       = video_port;
    resp->imu_port         = imu_port;
    resp->codec_mask       = codec_mask;
    resp->name_len         = static_cast<uint8_t>(name_len);

    if (name_len > 0)
        std::memcpy(reinterpret_cast<uint8_t*>(resp) + sizeof(DiscoveryResponseFixed),
                    server_name.data(), name_len);

    out.swap(payload);
    return true;
}

}  // namespace vr::proto
