#include "video/video_source.hpp"
#include "steamvr/bridge.hpp"
#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>

using Microsoft::WRL::ComPtr;
namespace {
void Check(HRESULT result, const char* operation) {
    if (FAILED(result)) throw std::runtime_error(operation);
}
void Require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
bool HasNal(const std::vector<uint8_t>& bytes, uint8_t type) {
    for (size_t i = 0; i + 3 < bytes.size(); ++i) {
        if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 && (bytes[i + 3] & 31) == type) return true;
    }
    return false;
}
void ChooseOutput(IMFTransform* decoder) {
    for (DWORD i = 0; ; ++i) {
        ComPtr<IMFMediaType> type;
        Check(decoder->GetOutputAvailableType(0, i, &type), "No decoder output type");
        GUID subtype{};
        Check(type->GetGUID(MF_MT_SUBTYPE, &subtype), "Decoder output subtype");
        if (subtype == MFVideoFormat_NV12) {
            Check(decoder->SetOutputType(0, type.Get(), 0), "Configure NV12 decoder output");
            return;
        }
    }
}
int Drain(IMFTransform* decoder, int mode) {
    int count = 0;
    for (;;) {
        MFT_OUTPUT_STREAM_INFO info{};
        Check(decoder->GetOutputStreamInfo(0, &info), "Decoder stream info");
        ComPtr<IMFSample> sample;
        if (!(info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
            Check(MFCreateSample(&sample), "Create decoded sample");
            ComPtr<IMFMediaBuffer> buffer;
            Check(MFCreateMemoryBuffer(std::max(info.cbSize, DWORD(320 * 240 * 4)), &buffer), "Create decoded buffer");
            Check(sample->AddBuffer(buffer.Get()), "Attach decoded buffer");
        }
        MFT_OUTPUT_DATA_BUFFER output{};
        output.pSample = sample.Get();
        DWORD status = 0;
        const HRESULT hr = decoder->ProcessOutput(0, 1, &output, &status);
        if (output.pEvents) output.pEvents->Release();
        if (!sample && output.pSample) sample.Attach(output.pSample);
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return count;
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) { ChooseOutput(decoder); continue; }
        Check(hr, "Decode H.264 output");
        Require(sample != nullptr, "Missing decoded frame");
        ComPtr<IMFMediaBuffer> data;
        Check(sample->ConvertToContiguousBuffer(&data), "Read decoded frame");
        BYTE* bytes = nullptr;
        DWORD length = 0;
        Check(data->Lock(&bytes, nullptr, &length), "Lock decoded frame");
        const bool large_enough = length >= 320 * 240 * 3 / 2;
        const auto range = std::minmax_element(bytes, bytes + length);
        const bool visible = length && *range.second > *range.first + 20;
        bool correct_eyes = true;
        if (mode && large_enough) {
            const int left = bytes[120 * 320 + 80], right = bytes[120 * 320 + 240];
            const int expected_left = mode == 1 ? 65 : 73;
            const int expected_right = mode == 1 ? 175 : 127;
            correct_eyes = std::abs(left - expected_left) < 10 && std::abs(right - expected_right) < 10;
        }
        Check(data->Unlock(), "Unlock decoded frame");
        Require(large_enough && visible, "Decoded synthetic picture is empty/incorrect");
        Require(correct_eyes, "Decoded SBS eyes were duplicated, swapped or converted incorrectly");
        ++count;
    }
}
}

int main() {
    try {
        // MONO, synthetic SBS, and IPC SBS all run without capturing the desktop.
        for (int mode = 0; mode < 3; ++mode) {
            vr::video::Settings settings;
            settings.width = 320;
            settings.height = 240;
            settings.fps = 30;
            settings.bitrate = 500'000;
            settings.test_pattern = mode != 2;
            settings.stereo_test_pattern = mode == 1;
            settings.steamvr = mode == 2;
            settings.bridge_name = L"VRBoxVideoTest_" + std::to_wstring(GetCurrentProcessId());
            vrbox::steamvr::Bridge bridge(settings.bridge_name);
            std::vector<uint8_t> pixels;
            if (settings.steamvr) {
                vrbox::steamvr::Config config;
                config.width = settings.width; config.height = settings.height; config.fps = settings.fps;
                Require(bridge.Open(true) && bridge.Configure(config) && bridge.Heartbeat(true), "Open test IPC producer");
                pixels.resize(size_t(settings.width) * settings.height * 4);
                for (uint32_t y = 0; y < settings.height; ++y) for (uint32_t x = 0; x < settings.width; ++x) {
                    auto* pixel = pixels.data() + (size_t(y) * settings.width + x) * 4;
                    pixel[0] = 0; pixel[1] = x >= settings.width / 2 ? 220 : 0;
                    pixel[2] = x < settings.width / 2 ? 220 : 0; pixel[3] = 255;
                }
            }
            vr::video::VideoSource source(settings);
            if (settings.steamvr) Require(source.Next(true).empty(), "SteamVR must wait for pixels, not fall back to desktop");
            ComPtr<IMFTransform> decoder;
            Check(CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_PPV_ARGS(&decoder)), "Create H.264 decoder");
            ComPtr<IMFMediaType> type;
            Check(MFCreateMediaType(&type), "Create decoder input type");
            Check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "Decoder major type");
            Check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264), "Decoder H.264 type");
            Check(MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, settings.width, settings.height), "Decoder dimensions");
            Check(decoder->SetInputType(0, type.Get(), 0), "Set decoder input type");
            ChooseOutput(decoder.Get());
            Check(decoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0), "Begin decoding");
            Check(decoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0), "Start decoding");
            int encoded_count = 0, decoded_count = 0, keyframes = 0;
            std::vector<uint64_t> timestamps;
            // End before the natural 30-frame GOP; only the request can make frame 6 an IDR.
            for (int i = 0; i < 12; ++i) {
                if (settings.steamvr) {
                    Require(source.Next(i == 6).empty(), "SteamVR encoded a duplicate or missing IPC frame");
                    const auto timestamp = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count());
                    timestamps.push_back(timestamp);
                    Require(bridge.Heartbeat(true) && bridge.PublishFrame(pixels.data(), settings.width, settings.height,
                        settings.width * 4, timestamp, bridge.ReadState().generation), "Publish test stereo frame");
                }
                // In IPC mode the force request was made while capture had no new frame.
                const auto frames = source.Next(!settings.steamvr && i == 6);
                for (const auto& frame : frames) {
                    Require(!frame.bytes.empty() && frame.timestamp_us != 0, "Missing encoded frame/timestamp");
                    if (settings.steamvr) Require(frame.timestamp_us == timestamps.at(encoded_count), "IPC capture timestamp was lost");
                    Require(frame.keyframe == (encoded_count == 0 || encoded_count == 6),
                            "Expected only the initial IDR and the forced IDR at frame 6");
                    if (frame.keyframe) {
                        Require(HasNal(frame.bytes, 5) && HasNal(frame.bytes, 7) && HasNal(frame.bytes, 8), "IDR must include Annex B SPS/PPS");
                        ++keyframes;
                    }
                    ComPtr<IMFMediaBuffer> buffer;
                    Check(MFCreateMemoryBuffer(static_cast<DWORD>(frame.bytes.size()), &buffer), "Allocate H.264 decode input");
                    BYTE* destination = nullptr;
                    Check(buffer->Lock(&destination, nullptr, nullptr), "Lock H.264 decode input");
                    std::memcpy(destination, frame.bytes.data(), frame.bytes.size());
                    Check(buffer->Unlock(), "Unlock H.264 decode input");
                    Check(buffer->SetCurrentLength(static_cast<DWORD>(frame.bytes.size())), "H.264 decode input length");
                    ComPtr<IMFSample> sample;
                    Check(MFCreateSample(&sample), "Create H.264 decode sample");
                    Check(sample->AddBuffer(buffer.Get()), "Attach H.264 decode sample");
                    Check(sample->SetSampleTime(encoded_count * 10'000'000LL / settings.fps), "Decode timestamp");
                    Check(sample->SetSampleDuration(10'000'000 / settings.fps), "Decode duration");
                    Check(decoder->ProcessInput(0, sample.Get(), 0), "Feed H.264 to decoder");
                    ++encoded_count;
                    decoded_count += Drain(decoder.Get(), mode);
                }
            }
            Check(decoder->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0), "End decoding");
            Check(decoder->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0), "Drain decoder");
            decoded_count += Drain(decoder.Get(), mode);
            Require(encoded_count >= 10, "Encoder stalled/dropped synthetic frames");
            Require(decoded_count == encoded_count, "H.264 encoder/decoder round trip lost frames");
            Require(keyframes == 2, "Force-keyframe request was ignored");
            std::cout << (mode == 0 ? "MONO" : mode == 1 ? "Synthetic SBS" : "IPC SBS")
                      << " H.264 round trip: " << encoded_count << " encoded, " << decoded_count
                      << " decoded, " << keyframes << " IDRs. No desktop capture.\n";
        }
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "Video test failed: " << exception.what() << '\n';
        return 1;
    }
}
