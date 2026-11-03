#pragma once
// protocol.hpp — определения протокола VR Box Connect v1
// Соответствует спецификации docs/PROTOCOL.md.
// Целые поля упакованы (struct packed) и размещаются в little-endian порядке.

#include <cstdint>
#include <cstring>
#include <vector>
#include <string>

namespace vr::proto {

// ── Константы ────────────────────────────────────────────────────────────────
constexpr uint16_t kMagic = 0x5652;   // Wire bytes: 0x52, 0x56 (little-endian).
constexpr uint8_t  kVersion = 0x01;

// UDP-каналы (§2)
enum class Channel : uint8_t {
    kDiscovery = 0,
    kVideo     = 1,
    kImu       = 2,
    kTiming    = 3,
    kNack      = 4,
};

// Флаги датаграммы (§2)
enum Flags : uint8_t {
    kFlagPayloadCrc = (1u << 0),
};

// Порты
constexpr uint16_t kTcpPort      = 8765;
constexpr uint16_t kVideoUdpPort = 8766;
constexpr uint16_t kImuUdpPort   = 8767;
constexpr uint16_t kDiscoveryUdpPort = 8768;
constexpr size_t kMaxDatagramSize = 1200;
constexpr size_t kMaxVideoFragmentSize = kMaxDatagramSize - 14 - 16;
constexpr size_t kMaxAccessUnitBytes = 2 * 1024 * 1024;
constexpr uint16_t kMaxVideoFragments = 2048;
constexpr size_t kMaxControlPayload = 65536;

// TCP control message types (§5.1)
enum class CtrlType : uint8_t {
    kHello               = 0x01,
    kHelloAck            = 0x02,
    kSettingsRequest     = 0x03,
    kSettingsAck         = 0x04,
    kVideoStart          = 0x05,
    kVideoStop           = 0x06,
    kImuStart            = 0x07,
    kImuStop             = 0x08,
    kTelemetry           = 0x09,
    kClientTelemetry     = 0x0A,
    kError               = 0x0B,
    kKeyframeRequest     = 0x0C,
    kSessionEnd          = 0x0D,
    kPing                = 0x0E,
    kRecenter            = 0x0F,
};

// Video frame type (§3.1)
enum class VideoFrameType : uint8_t {
    kIdr = 0,
    kP   = 1,
    kConfig = 2,   // SPS/PPS/VPS
    kSei = 3,
};

// Discovery types (§4)
enum class DiscoveryType : uint8_t {
    kReq = 0x10,
    kRes = 0x11,
};

// Коды ошибок TCP control (§5.1, msg 0x0B)
enum class ErrorCode : uint8_t {
    kWrongVersion  = 1,
    kBadMagic      = 2,
    kCodecUnsupported = 3,
    kResolutionUnsupported = 4,
    kBufferOverflow = 5,
    kSensorUnavailable = 6,
    kEncoderError  = 7,
    kSessionBusy   = 8,
    kAuthenticationFailed = 9,
    kInvalidRequest = 10,
};

// ── Структуры пакетов (packed, little-endian) ────────────────────────────────

#pragma pack(push, 1)
// Общий UDP-заголовок: 14 байт (§2)
struct DatagramHeader {
    uint16_t magic;
    uint8_t  version;
    uint8_t  channel;   // Channel enum
    uint8_t  flags;
    uint8_t  reserved;
    uint32_t sequence;
    uint16_t payload_len;
    uint16_t crc;
};

// Полезная нагрузка видео (§3.1): 16 байт заголовка перед NAL-байтами
struct VideoPayloadHeader {
    uint16_t frag_index;
    uint16_t frag_total;
    uint32_t frame_id;
    uint8_t  codec;        // 0=H.264, 1=H.265
    uint8_t  frame_type;   // VideoFrameType
    uint8_t  reserved;
    uint8_t  nal_type_hint;
    uint32_t frame_ts_ms;
};

// IMU payload: 16-byte header followed by N * 40-byte samples.
struct ImuPayloadHeader {
    uint64_t timestamp_ns;
    uint32_t sample_seq;
    uint8_t  sample_count;
    uint8_t  sample_format;   // 0 = quaternion+gyro+accel
    uint8_t  reserved[2];
};
static_assert(sizeof(ImuPayloadHeader) == 16, "ImuPayloadHeader size");

// Один IMU-семпл: 40 байт (§6.1)
struct ImuSample {
    float q[4];  // rotation quaternion x,y,z,w
    float g[3];  // gyro rad/s
    float a[3];  // accel m/s^2
};
static_assert(sizeof(ImuSample) == 40, "ImuSample size");

// Timing payload: 14 bytes, including the reserved field.
struct TimingPayload {
    uint64_t send_ns;
    uint32_t seq;
    uint16_t reserved;
};
static_assert(sizeof(TimingPayload) == 14, "TimingPayload size");

// NACK payload: 6 bytes followed by frag_count uint16 indices.
struct NackPayload {
    uint32_t frame_id;
    uint16_t frag_count;
};
static_assert(sizeof(NackPayload) == 6, "NackPayload size");

// Discovery-заголовок (§4)
struct DiscoveryHeader {
    uint16_t magic;
    uint8_t  version;
    uint8_t  type;   // DiscoveryType
    uint16_t payload_len;
};
static_assert(sizeof(DiscoveryHeader) == 6, "DiscoveryHeader size");

// Fixed discovery response payload: 13 bytes.
struct DiscoveryResponseFixed {
    uint16_t protocol_version;
    uint16_t tcp_port;
    uint16_t video_port;
    uint16_t imu_port;
    uint32_t codec_mask;  // bit0=H.264, bit1=H.265
    uint8_t  name_len;
};
static_assert(sizeof(DiscoveryResponseFixed) == 13, "DiscoveryResponseFixed size");

// TCP control header (§5.1)
struct ControlHeader {
    uint16_t magic;
    uint8_t  version;
    uint8_t  msg_type;
    uint32_t payload_len;
};
static_assert(sizeof(ControlHeader) == 8, "ControlHeader size");
#pragma pack(pop)

static_assert(sizeof(DatagramHeader) == 14, "DatagramHeader size");
static_assert(sizeof(VideoPayloadHeader) == 16, "VideoPayloadHeader size");

// ── Утилиты ──────────────────────────────────────────────────────────────────

// CRC16-CCITT-FALSE (poly 0x1021, init 0xFFFF). Табличный вариант производительнее,
// но для пакетов <2 КБ линейный проход читаемее и без table-miss риска.
uint16_t crc16(const uint8_t* data, size_t len);

// ── Построители / парсеры общих датаграмм (§2) ───────────────────────────────
class DatagramBuilder {
public:
    // Собрать: channel + payload → готовый пакет (14 bytes header + payload)
    static bool Build(uint8_t channel, const uint8_t* payload, size_t plen,
                      std::vector<uint8_t>& out, uint32_t sequence = 0);
    // Распарсить входящий буфер. header и payload заполняются при success.
    static bool Parse(const uint8_t* buf, size_t len,
                      DatagramHeader& hdr, std::vector<uint8_t>& payload);
};

// ── Канал Video (§3) ─────────────────────────────────────────────────────────
// Each frame_id identifies one complete Annex B access unit, not one NAL.
bool BuildVideoPacket(uint32_t frame_id, uint16_t frag_idx, uint16_t frag_total,
                      uint8_t codec, uint8_t frame_type, uint8_t nal_hint,
                      uint32_t frame_ts_ms, const uint8_t* nalu, size_t nalu_len,
                      std::vector<uint8_t>& out, uint32_t sequence = 0);

// ── Канал IMU (§6) ───────────────────────────────────────────────────────────
bool BuildImuPacket(uint64_t timestamp_ns, uint32_t sample_seq,
                    const ImuSample* samples, size_t count,
                    std::vector<uint8_t>& out);

// ── Канал Timing (§7) ────────────────────────────────────────────────────────
bool BuildTimingPacket(uint64_t send_ns, uint32_t seq, std::vector<uint8_t>& out);

// ── Канал NACK (§3.4) ───────────────────────────────────────────────────────
bool BuildNackPacket(uint32_t frame_id, const uint16_t* frags, size_t count,
                     std::vector<uint8_t>& out);

// ── TCP Control (§5) ─────────────────────────────────────────────────────────
// JSON передается как строка — парсинг/сериализация JSON outside scope этого модуля.
// Используем tiny-json или simdjson позже; пока сырые строки.
std::string SerializeControl(CtrlType type, const std::string& json_payload);
bool DeserializeControl(const uint8_t* buf, size_t len,
                        ControlHeader& hdr, std::string& payload);

// ── Discovery (§4) ───────────────────────────────────────────────────────────
bool BuildDiscoveryRequest(const std::string& device_name, std::vector<uint8_t>& out);
bool BuildDiscoveryResponse(uint16_t tcp_port, uint16_t video_port, uint16_t imu_port,
                             uint32_t codec_mask, const std::string& server_name,
                             std::vector<uint8_t>& out);

}  // namespace vr::proto
