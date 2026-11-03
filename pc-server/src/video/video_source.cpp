#include "video_source.hpp"
#include "steamvr/bridge.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <iterator>
#include <map>
#include <stdexcept>
#include <string>

#ifdef _WIN32
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <codecapi.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>

namespace vr::video {
namespace {
using Microsoft::WRL::ComPtr;

void Check(HRESULT hr, const char* operation) {
    if (FAILED(hr)) {
        char code[16];
        snprintf(code, sizeof(code), "0x%08lX", static_cast<unsigned long>(hr));
        throw std::runtime_error(std::string(operation) + " failed (" + code + ")");
    }
}

struct MfRuntime {
    MfRuntime() {
        Check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "CoInitializeEx");
        const HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
        if (FAILED(hr)) {
            CoUninitialize();
            Check(hr, "MFStartup");
        }
    }
    ~MfRuntime() { MFShutdown(); CoUninitialize(); }
};

uint64_t NowUs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

bool HasNal(const std::vector<uint8_t>& bytes, uint8_t type) {
    for (size_t i = 0; i + 3 < bytes.size(); ++i) {
        if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 &&
            (bytes[i + 3] & 31) == type) return true;
    }
    return false;
}

void SetCodecOption(ICodecAPI* codec, const GUID& option, uint32_t value, const char* operation) {
    VARIANT setting;
    VariantInit(&setting);
    setting.vt = VT_UI4;
    setting.ulVal = value;
    Check(codec->SetValue(&option, &setting), operation);
}
} // namespace

struct VideoSource::Impl {
    MfRuntime runtime;
    Settings settings;
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<IDXGIOutput1> output;
    ComPtr<IDXGIOutputDuplication> duplication;
    ComPtr<ID3D11Texture2D> staging;
    ComPtr<IMFTransform> encoder;
    ComPtr<ICodecAPI> codec;
    std::vector<uint8_t> nv12;
    std::vector<uint8_t> sequence_header;
    std::map<LONGLONG, uint64_t> capture_times;
    DXGI_MODE_ROTATION rotation = DXGI_MODE_ROTATION_IDENTITY;
    uint64_t frame_index = 0;
    bool have_image = false;
    bool pending_keyframe = false;
    std::unique_ptr<vrbox::steamvr::Bridge> vr_bridge;
    vrbox::steamvr::Frame vr_frame;

    void Open(const Settings& requested) {
        settings = requested;
        if (settings.width < 160 || settings.width > 1920 || settings.height < 120 ||
            settings.height > 1080 || (settings.width % 2) || (settings.height % 2) ||
            settings.fps < 1 || settings.fps > 60 || settings.bitrate < 100'000 ||
            settings.bitrate > 50'000'000) {
            throw std::runtime_error("Invalid video dimensions, frame rate or bitrate");
        }
        if ((settings.steamvr && settings.test_pattern) || (settings.stereo_test_pattern && !settings.test_pattern) ||
            ((settings.steamvr || settings.stereo_test_pattern) && settings.width % 4)) {
            throw std::runtime_error("Invalid stereo source or per-eye width");
        }
        nv12.resize(static_cast<size_t>(settings.width) * settings.height * 3 / 2);
        if (settings.steamvr) {
            vr_bridge = std::make_unique<vrbox::steamvr::Bridge>(settings.bridge_name);
            if (!vr_bridge->Open(false)) throw std::runtime_error("SteamVR bridge unavailable; start the server in --steamvr mode");
        } else if (!settings.test_pattern) OpenDesktop();

        // The inbox synchronous encoder is a portable Windows baseline, not NVENC.
        Check(CoCreateInstance(CLSID_CMSH264EncoderMFT, nullptr, CLSCTX_INPROC_SERVER,
                               IID_PPV_ARGS(&encoder)), "Create H.264 encoder");
        Check(encoder.As(&codec), "Get encoder options");
        VARIANT low_latency;
        VariantInit(&low_latency);
        low_latency.vt = VT_BOOL;
        low_latency.boolVal = VARIANT_TRUE;
        Check(codec->SetValue(&CODECAPI_AVLowLatencyMode, &low_latency), "Set low-latency mode");
        SetCodecOption(codec.Get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR, "Set CBR mode");
        SetCodecOption(codec.Get(), CODECAPI_AVEncCommonQualityVsSpeed, 0, "Set encoder speed");
        SetCodecOption(codec.Get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0, "Disable B-frames");
        SetCodecOption(codec.Get(), CODECAPI_AVEncMPVGOPSize, settings.fps, "Set one-second GOP");

        ComPtr<IMFMediaType> type;
        Check(MFCreateMediaType(&type), "Create output media type");
        Check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "Set output major type");
        Check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264), "Set H.264 subtype");
        Check(type->SetUINT32(MF_MT_AVG_BITRATE, settings.bitrate), "Set bitrate");
        Check(type->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base), "Set baseline profile");
        SetVideoAttributes(type.Get());
        Check(encoder->SetOutputType(0, type.Get(), 0), "Set encoder output type");
        type.Reset();
        Check(MFCreateMediaType(&type), "Create input media type");
        Check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "Set input major type");
        Check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12), "Set NV12 subtype");
        SetVideoAttributes(type.Get());
        Check(encoder->SetInputType(0, type.Get(), 0), "Set encoder input type");

        type.Reset();
        Check(encoder->GetOutputCurrentType(0, &type), "Read encoder output type");
        UINT32 size = 0;
        if (SUCCEEDED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &size)) && size) {
            sequence_header.resize(size);
            Check(type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, sequence_header.data(), size, nullptr),
                  "Read H.264 sequence header");
        }
        Check(encoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0), "Begin encoder streaming");
        Check(encoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0), "Start encoder stream");
    }

    void SetVideoAttributes(IMFMediaType* type) {
        Check(MFSetAttributeSize(type, MF_MT_FRAME_SIZE, settings.width, settings.height), "Set frame size");
        Check(MFSetAttributeRatio(type, MF_MT_FRAME_RATE, settings.fps, 1), "Set frame rate");
        Check(MFSetAttributeRatio(type, MF_MT_PIXEL_ASPECT_RATIO, 1, 1), "Set pixel aspect ratio");
        Check(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive), "Set progressive scan");
        type->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT601);
        type->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
    }

    void OpenDesktop() {
        ComPtr<IDXGIFactory1> factory;
        Check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "Create DXGI factory");
        const HMONITOR primary = MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY);
        for (UINT ai = 0; ; ++ai) {
            ComPtr<IDXGIAdapter1> adapter;
            const HRESULT adapter_hr = factory->EnumAdapters1(ai, &adapter);
            if (adapter_hr == DXGI_ERROR_NOT_FOUND) break;
            Check(adapter_hr, "Enumerate display adapter");
            for (UINT oi = 0; ; ++oi) {
                ComPtr<IDXGIOutput> candidate;
                const HRESULT output_hr = adapter->EnumOutputs(oi, &candidate);
                if (output_hr == DXGI_ERROR_NOT_FOUND) break;
                Check(output_hr, "Enumerate display output");
                DXGI_OUTPUT_DESC desc{};
                Check(candidate->GetDesc(&desc), "Read display description");
                if (!desc.AttachedToDesktop || desc.Monitor != primary) continue;
                Check(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
                    &device, nullptr, &context), "Create capture device");
                Check(candidate.As(&output), "Get desktop duplication output");
                Check(output->DuplicateOutput(device.Get(), &duplication), "Duplicate primary desktop");
                rotation = desc.Rotation;
                return;
            }
        }
        throw std::runtime_error("No primary desktop available; use --test-pattern for a synthetic stream");
    }

    bool Capture() {
        if (settings.steamvr) {
            if (!vr_bridge->ReadFrame(vr_frame)) return false;
            if (vr_frame.width != settings.width || vr_frame.height != settings.height ||
                vr_frame.bgra.size() != size_t(settings.width) * settings.height * 4) {
                throw std::runtime_error("SteamVR display dimensions changed; restart SteamVR and reconnect");
            }
            Convert(vr_frame.bgra.data(), vr_frame.width * 4, vr_frame.width, vr_frame.height);
            return true;
        }
        if (settings.test_pattern) {
            const size_t luma_size = static_cast<size_t>(settings.width) * settings.height;
            for (uint32_t y = 0; y < settings.height; ++y) {
                for (uint32_t x = 0; x < settings.width; ++x) {
                    const bool marker = ((x + frame_index * 8) % settings.width) < settings.width / 12;
                    nv12[static_cast<size_t>(y) * settings.width + x] = settings.stereo_test_pattern ?
                        static_cast<uint8_t>(x < settings.width / 2 ? 65 : 175) :
                        marker ? 235 : static_cast<uint8_t>(32 + (x * 160 / settings.width));
                }
            }
            for (size_t i = luma_size; i < nv12.size(); i += 2) {
                const bool right = settings.stereo_test_pattern && (i - luma_size) % settings.width >= settings.width / 2;
                nv12[i] = right ? 180 : 96;
                nv12[i + 1] = right ? 80 : 180;
            }
            return true;
        }

        DXGI_OUTDUPL_FRAME_INFO info{};
        ComPtr<IDXGIResource> resource;
        const HRESULT hr = duplication->AcquireNextFrame(have_image ? 0 : 30, &info, &resource);
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) return have_image;
        if (hr == DXGI_ERROR_ACCESS_LOST) {
            duplication.Reset();
            staging.Reset();
            have_image = false;
            Check(output->DuplicateOutput(device.Get(), &duplication), "Reopen desktop after display change");
            DXGI_OUTPUT_DESC desc{};
            Check(output->GetDesc(&desc), "Read changed display description");
            rotation = desc.Rotation;
            return false;
        }
        Check(hr, "Acquire desktop frame");
        struct ReleaseFrame {
            IDXGIOutputDuplication* value;
            ~ReleaseFrame() { value->ReleaseFrame(); }
        } release{duplication.Get()};

        ComPtr<ID3D11Texture2D> texture;
        Check(resource.As(&texture), "Read desktop texture");
        D3D11_TEXTURE2D_DESC desc{};
        texture->GetDesc(&desc);
        if (desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM) {
            throw std::runtime_error("Unsupported desktop pixel format");
        }
        if (!staging) {
            desc.Usage = D3D11_USAGE_STAGING;
            desc.BindFlags = 0;
            desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            desc.MiscFlags = 0;
            Check(device->CreateTexture2D(&desc, nullptr, &staging), "Create capture staging texture");
        }
        context->CopyResource(staging.Get(), texture.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        Check(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "Map desktop pixels");
        Convert(static_cast<const uint8_t*>(mapped.pData), mapped.RowPitch, desc.Width, desc.Height);
        context->Unmap(staging.Get(), 0);
        have_image = true;
        return true;
    }

    void Convert(const uint8_t* pixels, UINT pitch, UINT source_w, UINT source_h) {
        const bool portrait = rotation == DXGI_MODE_ROTATION_ROTATE90 || rotation == DXGI_MODE_ROTATION_ROTATE270;
        const UINT logical_w = portrait ? source_h : source_w;
        const UINT logical_h = portrait ? source_w : source_h;
        const double scale = std::min(double(settings.width) / logical_w, double(settings.height) / logical_h);
        const UINT fit_w = std::max(2u, static_cast<UINT>(logical_w * scale) & ~1u);
        const UINT fit_h = std::max(2u, static_cast<UINT>(logical_h * scale) & ~1u);
        const UINT left = ((settings.width - fit_w) / 2) & ~1u;
        const UINT top = ((settings.height - fit_h) / 2) & ~1u;
        const size_t y_size = static_cast<size_t>(settings.width) * settings.height;
        std::fill(nv12.begin(), nv12.begin() + y_size, uint8_t{16});
        std::fill(nv12.begin() + y_size, nv12.end(), uint8_t{128});
        for (UINT y = 0; y < fit_h; y += 2) {
            for (UINT x = 0; x < fit_w; x += 2) {
                int sum_u = 0, sum_v = 0;
                for (UINT dy = 0; dy < 2; ++dy) {
                    for (UINT dx = 0; dx < 2; ++dx) {
                        const UINT lx = (x + dx) * logical_w / fit_w;
                        const UINT ly = (y + dy) * logical_h / fit_h;
                        UINT sx = lx, sy = ly;
                        switch (rotation) {
                            case DXGI_MODE_ROTATION_ROTATE90: sx = ly; sy = source_h - 1 - lx; break;
                            case DXGI_MODE_ROTATION_ROTATE180: sx = source_w - 1 - lx; sy = source_h - 1 - ly; break;
                            case DXGI_MODE_ROTATION_ROTATE270: sx = source_w - 1 - ly; sy = lx; break;
                            default: break;
                        }
                        const uint8_t* rgb = pixels + static_cast<size_t>(sy) * pitch + sx * 4;
                        const int b = rgb[0], g = rgb[1], r = rgb[2];
                        nv12[static_cast<size_t>(top + y + dy) * settings.width + left + x + dx] =
                            static_cast<uint8_t>(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                        sum_u += ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        sum_v += ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    }
                }
                const size_t uv = y_size + static_cast<size_t>((top + y) / 2) * settings.width + left + x;
                nv12[uv] = static_cast<uint8_t>((sum_u + 2) / 4);
                nv12[uv + 1] = static_cast<uint8_t>((sum_v + 2) / 4);
            }
        }
    }

    std::vector<EncodedFrame> Next(bool force_keyframe) {
        // A skipped capture must not consume the worker's one-shot recovery request.
        pending_keyframe = pending_keyframe || force_keyframe;
        if (!Capture()) return {};
        const uint64_t timestamp_us = settings.steamvr ? vr_frame.timestamp_us : NowUs();
        ComPtr<IMFMediaBuffer> buffer;
        Check(MFCreateMemoryBuffer(static_cast<DWORD>(nv12.size()), &buffer), "Allocate encoder input");
        BYTE* data = nullptr;
        Check(buffer->Lock(&data, nullptr, nullptr), "Lock encoder input");
        std::memcpy(data, nv12.data(), nv12.size());
        Check(buffer->Unlock(), "Unlock encoder input");
        Check(buffer->SetCurrentLength(static_cast<DWORD>(nv12.size())), "Set input length");
        ComPtr<IMFSample> sample;
        Check(MFCreateSample(&sample), "Create encoder sample");
        Check(sample->AddBuffer(buffer.Get()), "Attach encoder input");
        const LONGLONG pts = static_cast<LONGLONG>(frame_index * 10'000'000 / settings.fps);
        Check(sample->SetSampleTime(pts), "Set encoder timestamp");
        Check(sample->SetSampleDuration(10'000'000 / settings.fps), "Set sample duration");
        if (pending_keyframe) SetCodecOption(codec.Get(), CODECAPI_AVEncVideoForceKeyFrame, 1, "Force H.264 IDR");
        Check(encoder->ProcessInput(0, sample.Get(), 0), "Encode input frame");
        pending_keyframe = false; // The force option belongs to this accepted input, even if output is delayed.
        capture_times[pts] = timestamp_us;
        ++frame_index;
        if (capture_times.size() > 64) throw std::runtime_error("Encoder latency exceeded queue limit");

        std::vector<EncodedFrame> frames;
        for (;;) {
            MFT_OUTPUT_STREAM_INFO info{};
            Check(encoder->GetOutputStreamInfo(0, &info), "Get encoded output requirements");
            ComPtr<IMFSample> encoded;
            if (!(info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
                Check(MFCreateSample(&encoded), "Create encoded sample");
                ComPtr<IMFMediaBuffer> encoded_buffer;
                const DWORD capacity = std::max(info.cbSize, static_cast<DWORD>(nv12.size()));
                Check(MFCreateAlignedMemoryBuffer(capacity, info.cbAlignment ? info.cbAlignment - 1 : 0,
                                                  &encoded_buffer), "Allocate encoded buffer");
                Check(encoded->AddBuffer(encoded_buffer.Get()), "Attach encoded buffer");
            }
            MFT_OUTPUT_DATA_BUFFER output_data{};
            output_data.pSample = encoded.Get();
            DWORD status = 0;
            const HRESULT hr = encoder->ProcessOutput(0, 1, &output_data, &status);
            if (output_data.pEvents) output_data.pEvents->Release();
            if (!encoded && output_data.pSample) encoded.Attach(output_data.pSample);
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) break;
            Check(hr, "Read H.264 output");
            if (!encoded) throw std::runtime_error("Encoder returned an empty sample");
            ComPtr<IMFMediaBuffer> contiguous;
            Check(encoded->ConvertToContiguousBuffer(&contiguous), "Read contiguous H.264 frame");
            DWORD length = 0;
            Check(contiguous->Lock(&data, nullptr, &length), "Lock H.264 frame");
            EncodedFrame frame;
            frame.bytes.assign(data, data + length);
            Check(contiguous->Unlock(), "Unlock H.264 frame");
            LONGLONG output_pts = pts;
            encoded->GetSampleTime(&output_pts);
            const auto time = capture_times.find(output_pts);
            frame.timestamp_us = time == capture_times.end() ? timestamp_us : time->second;
            if (time != capture_times.end()) capture_times.erase(capture_times.begin(), std::next(time));
            frame.keyframe = HasNal(frame.bytes, 5);
            if (frame.keyframe && (!HasNal(frame.bytes, 7) || !HasNal(frame.bytes, 8))) {
                if (!HasNal(sequence_header, 7) || !HasNal(sequence_header, 8)) {
                    throw std::runtime_error("H.264 keyframe is missing Annex B SPS/PPS");
                }
                frame.bytes.insert(frame.bytes.begin(), sequence_header.begin(), sequence_header.end());
            }
            if (!frame.bytes.empty()) frames.push_back(std::move(frame));
        }
        return frames;
    }
};

VideoSource::VideoSource(const Settings& settings) : impl_(std::make_unique<Impl>()) { impl_->Open(settings); }
VideoSource::~VideoSource() = default;
std::vector<EncodedFrame> VideoSource::Next(bool force_keyframe) { return impl_->Next(force_keyframe); }
} // namespace vr::video

#else
namespace vr::video {
struct VideoSource::Impl {};
VideoSource::VideoSource(const Settings&) { throw std::runtime_error("Video capture requires Windows"); }
VideoSource::~VideoSource() = default;
std::vector<EncodedFrame> VideoSource::Next(bool) { return {}; }
} // namespace vr::video
#endif
