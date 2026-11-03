// Exercise the real component implementation with a mock OpenVR host and real D3D11 textures.
// No SteamVR installation, default IPC name, desktop capture, or device registration is used.
#include "../steamvr-driver/driver.cpp"
#include <iostream>
#include <stdexcept>

namespace {
int checks = 0;
void Require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
void Check(HRESULT hr, const char* message) { Require(SUCCEEDED(hr), message); }

class TestHost final : public vr::IVRDriverContext, public vr::IVRProperties,
    public vr::IVRDriverLog, public vr::IVRServerDriverHost, public vr::IVRSettings,
    public vr::IVRDriverManager, public vr::IVRResources {
    struct Property { vr::PropertyTypeTag_t tag; std::vector<uint8_t> bytes; };
    std::map<std::pair<uint64_t, vr::ETrackedDeviceProperty>, Property> properties_;
    std::mutex pose_mutex_;
    std::condition_variable pose_ready_;
    vr::DriverPose_t latest_{};
public:
    vr::HmdMatrix34_t left{}, right{};
    std::vector<vr::ETrackedDeviceClass> registrations;
    bool accept_devices = true;
    void* GetGenericInterface(const char* name, vr::EVRInitError* error) override {
        void* result = nullptr;
        if (!std::strcmp(name, vr::IVRProperties_Version)) result = static_cast<vr::IVRProperties*>(this);
        if (!std::strcmp(name, vr::IVRDriverLog_Version)) result = static_cast<vr::IVRDriverLog*>(this);
        if (!std::strcmp(name, vr::IVRServerDriverHost_Version)) result = static_cast<vr::IVRServerDriverHost*>(this);
        if (!std::strcmp(name, vr::IVRSettings_Version)) result = static_cast<vr::IVRSettings*>(this);
        if (!std::strcmp(name, vr::IVRDriverManager_Version)) result = static_cast<vr::IVRDriverManager*>(this);
        if (!std::strcmp(name, vr::IVRResources_Version)) result = static_cast<vr::IVRResources*>(this);
        if (error) *error = result ? vr::VRInitError_None : vr::VRInitError_Init_InterfaceNotFound;
        return result;
    }
    vr::DriverHandle_t GetDriverHandle() override { return 100; }
    vr::ETrackedPropertyError ReadPropertyBatch(uint64_t container, vr::PropertyRead_t* batch, uint32_t count) override {
        for (uint32_t i = 0; i < count; ++i) {
            auto& entry = batch[i];
            const auto found = properties_.find({container, entry.prop});
            entry.eError = vr::TrackedProp_UnknownProperty;
            if (found == properties_.end()) continue;
            const auto& value = found->second;
            entry.unTag = value.tag;
            entry.unRequiredBufferSize = static_cast<uint32_t>(value.bytes.size());
            entry.eError = vr::TrackedProp_BufferTooSmall;
            if (entry.unBufferSize < value.bytes.size()) continue;
            std::memcpy(entry.pvBuffer, value.bytes.data(), value.bytes.size());
            entry.eError = vr::TrackedProp_Success;
        }
        return vr::TrackedProp_Success;
    }
    vr::ETrackedPropertyError WritePropertyBatch(uint64_t container, vr::PropertyWrite_t* batch, uint32_t count) override {
        for (uint32_t i = 0; i < count; ++i) {
            auto& entry = batch[i];
            const auto* bytes = static_cast<const uint8_t*>(entry.pvBuffer);
            properties_[{container, entry.prop}] = {entry.unTag, {bytes, bytes + entry.unBufferSize}};
            entry.eError = vr::TrackedProp_Success;
        }
        return vr::TrackedProp_Success;
    }
    const char* GetPropErrorNameFromEnum(vr::ETrackedPropertyError) override { return "test property error"; }
    uint64_t TrackedDeviceToPropertyContainer(uint32_t index) override { return uint64_t(index) + 1; }
    void Log(const char* message) override { std::cout << message; }
    bool TrackedDeviceAdded(const char*, vr::ETrackedDeviceClass device_class, vr::ITrackedDeviceServerDriver*) override {
        registrations.push_back(device_class);
        return accept_devices;
    }
    void TrackedDevicePoseUpdated(uint32_t, const vr::DriverPose_t& pose, uint32_t size) override {
        if (size != sizeof(pose)) return;
        { std::lock_guard lock(pose_mutex_); latest_ = pose; }
        pose_ready_.notify_all();
    }
    bool WaitPose(bool valid) {
        std::unique_lock lock(pose_mutex_);
        return pose_ready_.wait_for(lock, 1s, [&] { return latest_.poseIsValid == valid; });
    }
    void VsyncEvent(double) override {}
    void VendorSpecificEvent(uint32_t, vr::EVREventType, const vr::VREvent_Data_t&, double) override {}
    bool IsExiting() override { return false; }
    bool PollNextEvent(vr::VREvent_t*, uint32_t) override { return false; }
    void GetRawTrackedDevicePoses(float, vr::TrackedDevicePose_t*, uint32_t) override {}
    void RequestRestart(const char*, const char*, const char*, const char*) override {}
    uint32_t GetFrameTimings(vr::Compositor_FrameTiming*, uint32_t) override { return 0; }
    void SetDisplayEyeToHead(uint32_t, const vr::HmdMatrix34_t& l, const vr::HmdMatrix34_t& r) override { left = l; right = r; }
    void SetDisplayProjectionRaw(uint32_t, const vr::HmdRect2_t&, const vr::HmdRect2_t&) override {}
    void SetRecommendedRenderTargetSize(uint32_t, uint32_t, uint32_t) override {}
    const char* GetSettingsErrorNameFromEnum(vr::EVRSettingsError) override { return "test settings error"; }
    void SetBool(const char*, const char*, bool, vr::EVRSettingsError*) override {}
    void SetInt32(const char*, const char*, int32_t, vr::EVRSettingsError*) override {}
    void SetFloat(const char*, const char*, float, vr::EVRSettingsError*) override {}
    void SetString(const char*, const char*, const char*, vr::EVRSettingsError*) override {}
    bool GetBool(const char*, const char*, vr::EVRSettingsError*) override { return false; }
    int32_t GetInt32(const char*, const char*, vr::EVRSettingsError*) override { return 0; }
    float GetFloat(const char*, const char*, vr::EVRSettingsError*) override { return 0; }
    void GetString(const char*, const char*, char* value, uint32_t size, vr::EVRSettingsError*) override { if (size) value[0] = 0; }
    void RemoveSection(const char*, vr::EVRSettingsError*) override {}
    void RemoveKeyInSection(const char*, const char*, vr::EVRSettingsError*) override {}
    uint32_t GetDriverCount() const override { return 1; }
    uint32_t GetDriverName(vr::DriverId_t, char*, uint32_t) override { return 0; }
    vr::DriverHandle_t GetDriverHandle(const char*) override { return 100; }
    bool IsEnabled(vr::DriverId_t) const override { return true; }
    uint32_t LoadSharedResource(const char*, char*, uint32_t) override { return 0; }
    uint32_t GetResourceFullPath(const char*, const char*, char*, uint32_t) override { return 0; }
};
}

int main() {
    try {
        ComPtr<ID3D11Device> device;
        ComPtr<ID3D11DeviceContext> context;
        if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr, 0, D3D11_SDK_VERSION, &device, nullptr, &context))) {
            std::cerr << "SKIP: a hardware D3D11 adapter is required for shared-texture tests.\n";
            return 77;
        }
        TestHost host;
        Require(vr::InitServerDriverContext(&host) == vr::VRInitError_None, "Initialize test OpenVR interfaces");
        struct Cleanup { ~Cleanup() { vr::CleanupDriverContext(); } } cleanup;
        const auto name = L"VRBoxDriverTest_" + std::to_wstring(GetCurrentProcessId());
        vrbox::steamvr::Bridge server(name);
        vrbox::steamvr::Config config;
        config.width = 320; config.height = 240;
        Require(server.Open(true) && server.Configure(config), "Create isolated test bridge");
        Hmd hmd(name);
        Display display(name);
        struct Deactivate { Hmd& hmd; Display& display; ~Deactivate() { hmd.Deactivate(); display.Deactivate(); } } stop{hmd, display};
        Require(hmd.Activate(0) == vr::VRInitError_None, "Activate HMD without requiring a phone pose");
        Require(display.Activate(1) == vr::VRInitError_None, "Activate compositor redirect");
        Require(hmd.GetComponent(vr::IVRDisplayComponent_Version) != nullptr && hmd.GetComponent(vr::IVRVirtualDisplay_Version) == nullptr,
            "HMD must expose only its display geometry, not the redirect");
        Require(display.GetComponent(vr::IVRVirtualDisplay_Version) != nullptr && display.GetComponent(nullptr) == nullptr,
            "Redirect must expose the virtual display component");
        Require(!hmd.IsDisplayOnDesktop() && !hmd.IsDisplayRealDisplay(), "Phone must not pretend to be a desktop monitor");
        Require(!hmd.GetPose().poseIsValid, "Phone-less HMD must not report tracked");
        Require(std::abs(host.left.m[0][3] + config.ipd_m / 2) < 1e-6 && std::abs(host.right.m[0][3] - config.ipd_m / 2) < 1e-6,
            "Eye transforms must have independent IPD offsets");
        uint32_t x = 0, y = 0, width = 0, height = 0;
        hmd.GetRecommendedRenderTargetSize(&width, &height);
        Require(width == 160 && height == 240, "Render target size must be per eye");
        hmd.GetEyeOutputViewport(vr::Eye_Right, &x, &y, &width, &height);
        Require(x == 160 && y == 0 && width == 160 && height == 240, "Right eye must occupy the right SBS half");
        float left = 0, right = 0, top = 0, bottom = 0;
        hmd.GetProjectionRaw(vr::Eye_Left, &left, &right, &top, &bottom);
        Require(left < 0 && right == -left && top < 0 && bottom == -top, "Invalid projection bounds");
        const auto distortion = hmd.ComputeDistortion(vr::Eye_Left, 0.25f, 0.75f);
        Require(distortion.rfRed[0] == 0.25f && distortion.rfBlue[1] == 0.75f, "Driver must not apply phone lens correction twice");
        Require(vr::VRProperties()->GetFloatProperty(1, vr::Prop_DisplayFrequency_Float) == 60.f, "Wrong display frequency");
        Require(vr::VRProperties()->GetFloatProperty(1, vr::Prop_SecondsFromVsyncToPhotons_Float) == 0.f &&
            vr::VRProperties()->GetFloatProperty(2, vr::Prop_SecondsFromVsyncToPhotons_Float) == 0.040f,
            "Redirect latency must not also be added by the HMD");
        int error = 0;
        Require(HmdDriverFactory(vr::IServerTrackedDeviceProvider_Version, &error) != nullptr && error == vr::VRInitError_None,
            "Driver factory did not expose its provider");
        Require(HmdDriverFactory("unsupported", &error) == nullptr && error == vr::VRInitError_Init_InterfaceNotFound,
            "Driver factory accepted an unknown interface");

        Require(server.Heartbeat(true), "Connect simulated phone");
        vrbox::steamvr::Pose pose;
        pose.valid = true; pose.timestamp_ms = GetTickCount64();
        pose.q[1] = pose.q[3] = std::sqrt(0.5);
        pose.angular_velocity[1] = 0.4;
        Require(server.PublishPose(pose) && host.WaitPose(true), "HMD did not asynchronously publish real tracking");
        auto actual_pose = hmd.GetPose();
        Require(actual_pose.deviceIsConnected && actual_pose.poseIsValid && std::abs(actual_pose.qRotation.y - pose.q[1]) < 1e-6,
            "HMD quaternion or connection state is incorrect");
        Require(actual_pose.vecPosition[0] == 0 && actual_pose.vecPosition[1] == 1.2 && actual_pose.vecPosition[2] == 0,
            "3DoF pose must not invent positional motion");
        Require(actual_pose.vecAngularVelocity[1] == 0.4 && actual_pose.poseTimeOffset <= 0, "Wrong angular velocity or pose age");
        std::this_thread::sleep_for(280ms);
        Require(host.WaitPose(false) && !hmd.GetPose().poseIsValid && hmd.GetPose().vecAngularVelocity[1] == 0,
            "Expired tracking must not continue prediction");

        float seconds = 0;
        uint64_t before = 0, after = 0;
        Require(display.GetTimeSinceLastVsync(&seconds, &before), "Virtual vsync unavailable");
        display.WaitForPresent(); display.WaitForPresent();
        Require(display.GetTimeSinceLastVsync(&seconds, &after) && after > before && seconds >= 0 && seconds < 1.f / config.fps,
            "Vsync counter must advance even without submitted frames");
        Require(!display.GetTimeSinceLastVsync(nullptr, &after), "Null vsync output must be rejected");

        vrbox::steamvr::Frame frame;
        const auto receive = [&] {
            const auto deadline = Clock::now() + 2s;
            do {
                Require(server.Heartbeat(true), "Refresh test heartbeat");
                display.WaitForPresent();
                if (server.ReadFrame(frame)) return true;
            } while (Clock::now() < deadline);
            return false;
        };
        for (int mode = 0; mode < 4; ++mode) {
            const bool rgba = (mode & 1) != 0, keyed = mode >= 2;
            std::vector<uint8_t> expected(size_t(config.width) * config.height * 4);
            for (uint32_t row = 0; row < config.height; ++row) for (uint32_t col = 0; col < config.width; ++col) {
                auto* pixel = expected.data() + (size_t(row) * config.width + col) * 4;
                pixel[0] = uint8_t(row * 3); pixel[1] = uint8_t(col * 5);
                pixel[2] = col < config.width / 2 ? 180 : 20; pixel[3] = 255;
            }
            auto pixels = expected;
            if (rgba) for (size_t i = 0; i < pixels.size(); i += 4) std::swap(pixels[i], pixels[i + 2]);
            D3D11_TEXTURE2D_DESC desc{};
            desc.Width = config.width; desc.Height = config.height; desc.MipLevels = desc.ArraySize = desc.SampleDesc.Count = 1;
            desc.Format = rgba ? DXGI_FORMAT_R8G8B8A8_UNORM : DXGI_FORMAT_B8G8R8A8_UNORM;
            desc.Usage = D3D11_USAGE_DEFAULT; desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
            desc.MiscFlags = keyed ? D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX : D3D11_RESOURCE_MISC_SHARED;
            ComPtr<ID3D11Texture2D> texture;
            Check(device->CreateTexture2D(&desc, nullptr, &texture), "Create synthetic shared texture");
            ComPtr<IDXGIResource> resource;
            Check(texture.As(&resource), "Get shared resource interface");
            HANDLE handle = nullptr;
            Check(resource->GetSharedHandle(&handle), "Get compositor-style legacy shared handle");
            ComPtr<IDXGIKeyedMutex> keyed_mutex;
            if (keyed) {
                Check(texture.As(&keyed_mutex), "Get producer keyed mutex");
                Require(keyed_mutex->AcquireSync(0, 100) == S_OK, "Acquire producer key");
            }
            context->UpdateSubresource(texture.Get(), 0, nullptr, pixels.data(), config.width * 4, 0);
            D3D11_QUERY_DESC query_desc{D3D11_QUERY_EVENT, 0};
            ComPtr<ID3D11Query> query;
            Check(device->CreateQuery(&query_desc, &query), "Create producer GPU fence");
            context->End(query.Get()); context->Flush();
            const auto ready_deadline = Clock::now() + 2s;
            HRESULT ready = S_FALSE;
            while ((ready = context->GetData(query.Get(), nullptr, 0, 0)) == S_FALSE && Clock::now() < ready_deadline) {
                std::this_thread::sleep_for(1ms);
            }
            Require(ready == S_OK, "Producer GPU did not finish the synthetic frame");
            if (keyed_mutex) Check(keyed_mutex->ReleaseSync(0), "Release producer key");
            vr::PresentInfo_t info{};
            info.backbufferTextureHandle = reinterpret_cast<uintptr_t>(handle);
            display.Present(&info, sizeof(info));
            Require(receive() && frame.bgra == expected, "Shared RGBA/BGRA pixels or SBS ordering corrupted");
            Require(frame.width == config.width && frame.height == config.height && frame.timestamp_us != 0,
                "Compositor frame metadata lost");
            Require(!server.ReadFrame(frame), "A compositor frame was delivered twice");
            display.Present(nullptr, sizeof(info)); display.Present(&info, sizeof(info) - 1);
            display.WaitForPresent();
            Require(!server.ReadFrame(frame), "Malformed Present call produced a frame");
            if (keyed_mutex) {
                Require(keyed_mutex->AcquireSync(0, 100) == S_OK, "Driver did not release the compositor texture");
                const auto start = Clock::now();
                display.Present(&info, sizeof(info));
                const bool bounded = Clock::now() - start < 500ms;
                display.WaitForPresent();
                const bool dropped = !server.ReadFrame(frame);
                Check(keyed_mutex->ReleaseSync(0), "Release contended producer key");
                Require(bounded && dropped, "Driver read a texture whose keyed mutex was unavailable");
                display.Present(&info, sizeof(info));
                Require(receive() && frame.bgra == expected, "Capture did not recover after a keyed-mutex timeout");
            }
            if (mode == 0) {
                display.Present(&info, sizeof(info));
                std::this_thread::sleep_for(550ms);
                display.WaitForPresent();
                Require(!server.ReadFrame(frame), "Old GPU readback was relabeled as a fresh frame");
                display.Present(&info, sizeof(info));
                Require(server.Heartbeat(false) && server.Heartbeat(true), "Reconnect simulated phone");
                display.WaitForPresent();
                Require(!server.ReadFrame(frame), "Pending GPU frame leaked into a new phone session");
                display.Present(&info, sizeof(info));
                Require(receive() && frame.generation == server.ReadState().generation, "Capture did not recover after reconnect");
                auto changed = config; changed.width -= 4;
                Require(server.Configure(changed), "Change test display configuration");
                pose.timestamp_ms = GetTickCount64();
                Require(server.PublishPose(pose) && !hmd.GetPose().poseIsValid, "HMD tracked with mismatched display configuration");
                display.Present(&info, sizeof(info)); display.WaitForPresent();
                Require(!server.ReadFrame(frame), "Capture ignored changed display settings");
                Require(server.Configure(config), "Restore test display configuration");
            }
        }
        display.Deactivate();
        Require(!display.GetTimeSinceLastVsync(&seconds, &after), "Deactivated display reported vsync");
        config.fps = 1;
        Require(server.Configure(config) && display.Activate(1) == vr::VRInitError_None, "Reactivate display with slow vsync");
        const auto start = Clock::now();
        std::jthread waiter([&] { display.WaitForPresent(); });
        std::this_thread::sleep_for(20ms);
        display.Deactivate(); waiter.join();
        Require(Clock::now() - start < 500ms, "Deactivate did not wake the display wait");
        hmd.Deactivate();
        Provider registration(name);
        Require(registration.Init(&host) == vr::VRInitError_None && host.registrations ==
            std::vector<vr::ETrackedDeviceClass>{vr::TrackedDeviceClass_DisplayRedirect, vr::TrackedDeviceClass_HMD},
            "Redirect must be registered before the HMD initializes its presentation path");
        registration.Cleanup();
        host.registrations.clear(); host.accept_devices = false;
        Require(registration.Init(&host) == vr::VRInitError_Driver_Failed && host.registrations.size() == 1 &&
            vr::VRDriverContext() == nullptr, "Registration failure did not clean up its OpenVR context");
        host.registrations.clear(); server.Close();
        Require(registration.Init(&host) == vr::VRInitError_Init_HmdNotFound && host.registrations.empty() &&
            vr::VRDriverContext() == nullptr, "Driver without its PC server attempted to claim the HMD");
        std::cout << checks << " HMD/shared-texture checks passed. Mock OpenVR host, real GPU, no desktop capture.\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Driver test failed: " << error.what() << '\n';
        return 1;
    }
}
