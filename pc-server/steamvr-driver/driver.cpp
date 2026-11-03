#include <openvr_driver.h>
#include "steamvr/bridge.hpp"

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <numbers>
#include <thread>
#include <utility>
#include <vector>

namespace {
using Microsoft::WRL::ComPtr;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;

void Log(const char* message) { if (vr::VRDriverLog()) vr::VRDriverLog()->Log(message); }
uint64_t NowUs() { return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now().time_since_epoch()).count(); }

uint64_t PreferredAdapter() {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return 0;
    SIZE_T memory = 0;
    uint64_t luid = 0;
    for (UINT i = 0; ; ++i) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(i, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        if (!adapter) break;
        DXGI_ADAPTER_DESC1 desc{};
        if (FAILED(adapter->GetDesc1(&desc)) || (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE)) continue;
        if (!luid || desc.DedicatedVideoMemory > memory) {
            memory = desc.DedicatedVideoMemory;
            std::memcpy(&luid, &desc.AdapterLuid, sizeof(luid));
        }
    }
    return luid;
}

vr::DriverPose_t MakePose(const vrbox::steamvr::State& state) {
    vr::DriverPose_t pose{};
    pose.qWorldFromDriverRotation.w = pose.qDriverFromHeadRotation.w = 1;
    pose.qRotation = {state.pose.q[3], state.pose.q[0], state.pose.q[1], state.pose.q[2]};
    pose.vecPosition[1] = 1.2; // Fixed seated head position; no fake positional tracking.
    if (state.pose.valid) {
        std::copy(std::begin(state.pose.angular_velocity), std::end(state.pose.angular_velocity), pose.vecAngularVelocity);
    }
    const auto now = GetTickCount64();
    if (state.pose.timestamp_ms && now >= state.pose.timestamp_ms) {
        pose.poseTimeOffset = -std::min(0.25, (now - state.pose.timestamp_ms) / 1000.0);
    }
    pose.deviceIsConnected = state.server_alive;
    pose.poseIsValid = state.pose.valid;
    pose.result = pose.poseIsValid ? vr::TrackingResult_Running_OK : vr::TrackingResult_Calibrating_OutOfRange;
    pose.willDriftInYaw = true;
    pose.shouldApplyHeadModel = false;
    return pose;
}

class Hmd final : public vr::ITrackedDeviceServerDriver, public vr::IVRDisplayComponent {
public:
    explicit Hmd(std::wstring name = L"VRBoxSteamVR_v1") : bridge_(std::move(name)) {}
    ~Hmd() { Deactivate(); }
    vr::EVRInitError Activate(uint32_t index) override {
        try {
            if (!bridge_.Open(false)) return vr::VRInitError_Init_HmdNotFound;
            const auto state = bridge_.ReadState();
            if (!state.server_alive) return vr::VRInitError_Init_HmdNotFound;
            config_ = state.config;
            index_ = index;
            const auto container = vr::VRProperties()->TrackedDeviceToPropertyContainer(index);
            auto* properties = vr::VRProperties();
            properties->SetStringProperty(container, vr::Prop_ModelNumber_String, "VR Box Phone 3DoF");
            properties->SetStringProperty(container, vr::Prop_ManufacturerName_String, "VR Box Connect");
            properties->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "vrbox");
            properties->SetStringProperty(container, vr::Prop_SerialNumber_String, "vrbox-phone-001");
            properties->SetBoolProperty(container, vr::Prop_DeviceIsWireless_Bool, true);
            properties->SetBoolProperty(container, vr::Prop_IsOnDesktop_Bool, false);
            properties->SetBoolProperty(container, vr::Prop_ReportsTimeSinceVSync_Bool, true);
            properties->SetFloatProperty(container, vr::Prop_DisplayFrequency_Float, float(config_.fps));
            properties->SetFloatProperty(container, vr::Prop_SecondsFromVsyncToPhotons_Float, 0.f);
            properties->SetFloatProperty(container, vr::Prop_UserIpdMeters_Float, config_.ipd_m);
            properties->SetFloatProperty(container, vr::Prop_UserHeadToEyeDepthMeters_Float, 0.f);
            properties->SetUint64Property(container, vr::Prop_CurrentUniverseId_Uint64, 0x56524231);
            properties->SetUint64Property(container, vr::Prop_GraphicsAdapterLuid_Uint64, PreferredAdapter());
            vr::HmdMatrix34_t left{}, right{};
            for (int i = 0; i < 3; ++i) left.m[i][i] = right.m[i][i] = 1.f;
            left.m[0][3] = -config_.ipd_m / 2.f;
            right.m[0][3] = config_.ipd_m / 2.f;
            vr::VRServerDriverHost()->SetDisplayEyeToHead(index, left, right);
            thread_ = std::jthread([this](std::stop_token stop) {
                while (!stop.stop_requested()) {
                    const auto pose = GetPose();
                    vr::VRServerDriverHost()->TrackedDevicePoseUpdated(index_, pose, sizeof(pose));
                    std::unique_lock lock(wait_mutex_);
                    wake_.wait_for(lock, 10ms, [&] { return stop.stop_requested(); });
                }
            });
            Log("vrbox: 3DoF HMD activated; tracking comes from authenticated phone IMU.\n");
            return vr::VRInitError_None;
        } catch (...) { Deactivate(); return vr::VRInitError_Driver_Failed; }
    }
    void Deactivate() override {
        if (thread_.joinable()) { thread_.request_stop(); wake_.notify_all(); thread_.join(); }
        index_ = vr::k_unTrackedDeviceIndexInvalid;
        bridge_.Close();
    }
    void EnterStandby() override {}
    void* GetComponent(const char* version) override {
        return version && !std::strcmp(version, vr::IVRDisplayComponent_Version) ? static_cast<vr::IVRDisplayComponent*>(this) : nullptr;
    }
    void DebugRequest(const char*, char* output, uint32_t size) override { if (output && size) output[0] = 0; }
    vr::DriverPose_t GetPose() override {
        auto state = bridge_.ReadState();
        if (state.config != config_) state.pose.valid = false;
        return MakePose(state);
    }
    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* width, uint32_t* height) override {
        *x = *y = 0; *width = config_.width; *height = config_.height;
    }
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* width, uint32_t* height) override { *width = config_.width / 2; *height = config_.height; }
    void GetEyeOutputViewport(vr::EVREye eye, uint32_t* x, uint32_t* y, uint32_t* width, uint32_t* height) override {
        *x = eye == vr::Eye_Left ? 0 : config_.width / 2; *y = 0;
        *width = config_.width / 2; *height = config_.height;
    }
    void GetProjectionRaw(vr::EVREye, float* left, float* right, float* top, float* bottom) override {
        const float vertical = std::tan(config_.fov_y_degrees * std::numbers::pi_v<float> / 360.f);
        const float horizontal = vertical * (config_.width / 2.f) / config_.height;
        *left = -horizontal; *right = horizontal; *top = -vertical; *bottom = vertical;
    }
    vr::DistortionCoordinates_t ComputeDistortion(vr::EVREye, float u, float v) override {
        return {{u, v}, {u, v}, {u, v}}; // Correct exactly once in the phone's calibrated renderer.
    }
    bool ComputeInverseDistortion(vr::HmdVector2_t* result, vr::EVREye, uint32_t channel, float u, float v) override {
        if (!result || channel > 2) return false;
        result->v[0] = u; result->v[1] = v; return true;
    }
private:
    vrbox::steamvr::Bridge bridge_;
    vrbox::steamvr::Config config_;
    uint32_t index_ = vr::k_unTrackedDeviceIndexInvalid;
    std::jthread thread_;
    std::mutex wait_mutex_;
    std::condition_variable wake_;
};

class Display final : public vr::ITrackedDeviceServerDriver, public vr::IVRVirtualDisplay {
public:
    explicit Display(std::wstring name = L"VRBoxSteamVR_v1") : bridge_(std::move(name)) {}
    vr::EVRInitError Activate(uint32_t index) override {
        try {
            std::lock_guard lock(mutex_);
            if (!bridge_.Open(false)) return vr::VRInitError_Init_HmdNotFound;
            const auto state = bridge_.ReadState();
            if (!state.server_alive) return vr::VRInitError_Init_HmdNotFound;
            config_ = state.config;
            const auto container = vr::VRProperties()->TrackedDeviceToPropertyContainer(index);
            vr::VRProperties()->SetStringProperty(container, vr::Prop_ModelNumber_String, "VR Box Stereo Display");
            vr::VRProperties()->SetFloatProperty(container, vr::Prop_SecondsFromVsyncToPhotons_Float, 0.04f);
            vr::VRProperties()->SetUint64Property(container, vr::Prop_GraphicsAdapterLuid_Uint64, PreferredAdapter());
            start_ = Clock::now();
            active_ = true;
            callbacks_ = 0;
            logged_frame_ = false;
            Log("vrbox: compositor display redirect activated.\n");
            TraceCallback(8, "Activate");
            return vr::VRInitError_None;
        } catch (...) { return vr::VRInitError_Driver_Failed; }
    }
    void Deactivate() override {
        std::lock_guard lock(mutex_);
        if (active_) TraceCallback(16, "Deactivate");
        active_ = pending_ = false;
        wake_.notify_all();
        cache_.clear(); staging_.Reset(); context_.Reset(); device_.Reset(); bridge_.Close();
    }
    void EnterStandby() override {}
    void* GetComponent(const char* version) override {
        return version && !std::strcmp(version, vr::IVRVirtualDisplay_Version) ? static_cast<vr::IVRVirtualDisplay*>(this) : nullptr;
    }
    void DebugRequest(const char*, char* output, uint32_t size) override { if (output && size) output[0] = 0; }
    vr::DriverPose_t GetPose() override { vr::DriverPose_t pose{}; pose.qRotation.w = 1; return pose; }

    void Present(const vr::PresentInfo_t* info, uint32_t size) override {
        try {
            TraceCallback(1, "Present");
            std::lock_guard lock(mutex_);
            if (!active_) return;
            if (pending_ || !info || size < sizeof(vr::PresentInfo_t) || !info->backbufferTextureHandle) return;
            const auto state = bridge_.ReadState();
            if (!state.server_alive || !state.client_connected) return;
            if (state.config != config_) {
                Report("vrbox: display settings changed; restart SteamVR to apply them.\n"); return;
            }
            const auto texture = OpenTexture(info->backbufferTextureHandle);
            if (!texture) { Report("vrbox: cannot open compositor texture on a local graphics adapter.\n"); return; }
            D3D11_TEXTURE2D_DESC desc{};
            texture->GetDesc(&desc);
            if (desc.Width != config_.width || desc.Height != config_.height || desc.ArraySize != 1 || desc.SampleDesc.Count != 1) {
                char message[192];
                std::snprintf(message, sizeof(message), "vrbox: expected %ux%u SBS, got %ux%u (array %u, samples %u); restart SteamVR after display changes.\n",
                    config_.width, config_.height, desc.Width, desc.Height, desc.ArraySize, desc.SampleDesc.Count);
                Report(message); return;
            }
            const bool rgba = desc.Format == DXGI_FORMAT_R8G8B8A8_UNORM || desc.Format == DXGI_FORMAT_R8G8B8A8_UNORM_SRGB || desc.Format == DXGI_FORMAT_R8G8B8A8_TYPELESS;
            const bool bgra = desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM || desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM_SRGB || desc.Format == DXGI_FORMAT_B8G8R8A8_TYPELESS;
            if (!rgba && !bgra) { Report("vrbox: unsupported compositor pixel format; SDR RGBA/BGRA8 is required.\n"); return; }
            ComPtr<IDXGIKeyedMutex> keyed;
            texture.As(&keyed);
            if (keyed && keyed->AcquireSync(0, 5) != S_OK) return;
            struct Unlock { IDXGIKeyedMutex* value; ~Unlock() { if (value) value->ReleaseSync(0); } } unlock{keyed.Get()};
            if (!staging_ || format_ != desc.Format) {
                staging_.Reset();
                desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.MiscFlags = 0;
                desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
                if (FAILED(device_->CreateTexture2D(&desc, nullptr, &staging_))) return;
                format_ = desc.Format;
            }
            // Submit a bounded copy now; do not map/read pixels in the compositor's Present callback.
            context_->CopyResource(staging_.Get(), texture.Get());
            context_->Flush();
            rgba_ = rgba;
            timestamp_ = NowUs();
            generation_ = state.generation;
            pending_ = true;
        } catch (...) { Report("vrbox: compositor capture failed.\n"); }
    }

    void WaitForPresent() override {
        try {
            TraceCallback(2, "WaitForPresent");
            std::unique_lock lock(mutex_);
            if (!active_) return;
            const auto period = std::chrono::nanoseconds(1'000'000'000 / config_.fps);
            const auto tick = (Clock::now() - start_) / period + 1;
            wake_.wait_until(lock, start_ + tick * period, [&] { return !active_; });
            if (!active_ || !pending_ || !staging_) return;
            const auto state = bridge_.ReadState();
            if (!state.client_connected || state.generation != generation_ || state.config != config_) {
                pending_ = false;
                return;
            }
            D3D11_MAPPED_SUBRESOURCE mapped{};
            // The virtual-display contract requires rendering to finish before this call returns.
            const auto hr = context_->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mapped);
            pending_ = false;
            if (FAILED(hr)) {
                cache_.clear(); staging_.Reset(); context_.Reset(); device_.Reset();
                Report("vrbox: GPU readback lost; next frame will retry the adapter.\n"); return;
            }
            struct Unmap { ID3D11DeviceContext* context; ID3D11Texture2D* texture; ~Unmap() { context->Unmap(texture, 0); } } unmap{context_.Get(), staging_.Get()};
            // Never relabel an old GPU copy as a fresh frame after a stall.
            if (NowUs() - timestamp_ > std::max<uint64_t>(500'000, 2'000'000 / config_.fps)) return;
            const auto* pixels = static_cast<const uint8_t*>(mapped.pData);
            uint32_t pitch = mapped.RowPitch;
            if (rgba_) {
                converted_.resize(size_t(config_.width) * config_.height * 4);
                for (uint32_t y = 0; y < config_.height; ++y) {
                    const auto* source = static_cast<const uint8_t*>(mapped.pData) + size_t(y) * mapped.RowPitch;
                    auto* target = converted_.data() + size_t(y) * config_.width * 4;
                    for (uint32_t x = 0; x < config_.width; ++x) {
                        target[x * 4] = source[x * 4 + 2]; target[x * 4 + 1] = source[x * 4 + 1];
                        target[x * 4 + 2] = source[x * 4]; target[x * 4 + 3] = 255;
                    }
                }
                pixels = converted_.data();
                pitch = config_.width * 4;
            }
            if (bridge_.PublishFrame(pixels, config_.width, config_.height, pitch, timestamp_, generation_) && !logged_frame_) {
                Log("vrbox: first compositor frame published to the PC streaming bridge.\n");
                logged_frame_ = true;
            }
        } catch (...) { Report("vrbox: frame transfer failed.\n"); }
    }

    bool GetTimeSinceLastVsync(float* seconds, uint64_t* counter) override {
        TraceCallback(4, "GetTimeSinceLastVsync");
        std::lock_guard lock(mutex_);
        if (!active_ || !seconds || !counter) return false;
        const double elapsed = std::chrono::duration<double>(Clock::now() - start_).count();
        *counter = static_cast<uint64_t>(elapsed * config_.fps);
        *seconds = float(elapsed - double(*counter) / config_.fps);
        return true; // PC virtual clock; end-to-end phone scanout latency is not measured yet.
    }
private:
    ComPtr<ID3D11Texture2D> OpenTexture(vr::SharedTextureHandle_t value) {
        if (const auto found = cache_.find(value); found != cache_.end()) return found->second;
        const HANDLE handle = reinterpret_cast<HANDLE>(static_cast<uintptr_t>(value));
        ComPtr<ID3D11Texture2D> texture;
        if (device_ && SUCCEEDED(device_->OpenSharedResource(handle, IID_PPV_ARGS(&texture)))) {
            if (cache_.size() >= 8) cache_.clear();
            cache_[value] = texture; return texture;
        }
        ComPtr<IDXGIFactory1> factory;
        if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return {};
        for (UINT i = 0; ; ++i) {
            ComPtr<IDXGIAdapter1> adapter;
            if (factory->EnumAdapters1(i, &adapter) == DXGI_ERROR_NOT_FOUND) break;
            if (!adapter) break;
            ComPtr<ID3D11Device> candidate;
            ComPtr<ID3D11DeviceContext> context;
            if (FAILED(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                nullptr, 0, D3D11_SDK_VERSION, &candidate, nullptr, &context))) continue;
            if (FAILED(candidate->OpenSharedResource(handle, IID_PPV_ARGS(&texture)))) continue;
            cache_.clear(); staging_.Reset(); device_ = candidate; context_ = context;
            cache_[value] = texture;
            return texture;
        }
        return {};
    }
    void TraceCallback(unsigned bit, const char* name) {
        if (callbacks_.fetch_or(bit, std::memory_order_relaxed) & bit) return;
        char message[160];
        std::snprintf(message, sizeof(message), "vrbox: display %p %s (pid %lu, thread %lu).\n",
            static_cast<void*>(this), name, GetCurrentProcessId(), GetCurrentThreadId());
        Log(message);
    }
    void Report(const char* message) {
        const auto now = GetTickCount64();
        if (now - last_error_ >= 2000) { last_error_ = now; Log(message); }
    }
    vrbox::steamvr::Bridge bridge_;
    vrbox::steamvr::Config config_;
    std::mutex mutex_;
    std::condition_variable wake_;
    bool active_ = false, pending_ = false, rgba_ = false;
    bool logged_frame_ = false;
    std::atomic<unsigned> callbacks_{0};
    Clock::time_point start_ = Clock::now();
    uint64_t timestamp_ = 0, generation_ = 0, last_error_ = 0;
    DXGI_FORMAT format_ = DXGI_FORMAT_UNKNOWN;
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11Texture2D> staging_;
    std::map<vr::SharedTextureHandle_t, ComPtr<ID3D11Texture2D>> cache_;
    std::vector<uint8_t> converted_;
};

class Provider final : public vr::IServerTrackedDeviceProvider {
public:
    explicit Provider(std::wstring name = L"VRBoxSteamVR_v1")
        : name_(std::move(name)), hmd_(name_), display_(name_) {}
    vr::EVRInitError Init(vr::IVRDriverContext* context) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(context);
        initialized_ = true;
        try {
            vrbox::steamvr::Bridge check(name_);
            if (!check.Open(false) || !check.ReadState().server_alive) {
                Log("vrbox: inactive; start vrbox_server --steamvr before SteamVR.\n");
                Cleanup();
                return vr::VRInitError_Init_HmdNotFound;
            }
            // Queue redirection before the HMD initializes its presentation path.
            if (!vr::VRServerDriverHost()->TrackedDeviceAdded("vrbox-display-001", vr::TrackedDeviceClass_DisplayRedirect, &display_) ||
                !vr::VRServerDriverHost()->TrackedDeviceAdded("vrbox-phone-001", vr::TrackedDeviceClass_HMD, &hmd_)) {
                Cleanup();
                return vr::VRInitError_Driver_Failed;
            }
            return vr::VRInitError_None;
        } catch (...) { Cleanup(); return vr::VRInitError_Driver_Failed; }
    }
    void Cleanup() override {
        hmd_.Deactivate(); display_.Deactivate();
        if (initialized_) { VR_CLEANUP_SERVER_DRIVER_CONTEXT(); initialized_ = false; }
    }
    const char* const* GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override { vr::VREvent_t event{}; while (vr::VRServerDriverHost()->PollNextEvent(&event, sizeof(event))) {} }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}
private:
    std::wstring name_;
    Hmd hmd_;
    Display display_;
    bool initialized_ = false;
};
Provider provider;
}

extern "C" __declspec(dllexport) void* HmdDriverFactory(const char* name, int* error) {
    if (name && !std::strcmp(name, vr::IServerTrackedDeviceProvider_Version)) {
        if (error) *error = vr::VRInitError_None;
        return &provider;
    }
    if (error) *error = vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
