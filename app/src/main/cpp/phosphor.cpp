// phosphor.cpp — Phosphor media player: Vulkan presentation + CRT shader pipeline.
//
// How frames flow:
//   ExoPlayer (Media3) decodes video -> ImageReader Surface -> AHardwareBuffer
//   -> imported here as Vulkan external memory -> sampled with a YCbCr
//   conversion -> fullscreen triangle -> one of five CRT shader presets
//   (SPIR-V, compiled from shaders/*.glsl) -> swapchain -> the tube.
//
// Audio is handled entirely by ExoPlayer; this file only presents video.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <signal.h>
#include <sys/ucontext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>

#include <vector>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <chrono>
#include <cstring>

#define LOG_TAG "PhosphorVK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Native crash catcher.
//
// A native segfault bypasses Java's uncaught-exception handler entirely, so
// without this there is no way to learn WHERE it crashed. This installs
// async-signal-safe handlers for the fatal signals; on crash it writes
// signal/fault-address/crashing-PC plus /proc/self/maps to
// <crashDir>/native-crash-<pid>.txt, then re-raises so the system still
// produces its normal crash report. The next app launch copies the file to
// Downloads (see CrashReporter.collectNativeCrashes). Only signal-safe
// operations are used inside the handler: write(), open(), read(), close().
// ---------------------------------------------------------------------------

char g_crashDir[512] = {};
void* g_soBase = nullptr;

void crashWriteAll(int fd, const char* s) {
    size_t n = 0;
    while (s[n]) ++n;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, s + off, n - off);
        if (w <= 0) break;
        off += (size_t)w;
    }
}

void crashWriteHex(int fd, uint64_t v) {
    char buf[19];
    buf[0] = '0';
    buf[1] = 'x';
    for (int i = 0; i < 16; ++i) {
        int d = (int)((v >> ((15 - i) * 4)) & 0xF);
        buf[2 + i] = (char)(d < 10 ? '0' + d : 'a' + d - 10);
    }
    buf[18] = '\0';
    crashWriteAll(fd, buf);
}

void crashWriteU64(int fd, uint64_t v) {
    char buf[24];
    int i = 23;
    buf[i] = '\0';
    if (v == 0) buf[--i] = '0';
    while (v > 0 && i > 0) {
        buf[--i] = (char)('0' + (v % 10));
        v /= 10;
    }
    crashWriteAll(fd, buf + i);
}

void crashHandler(int sig, siginfo_t* info, void* uctx) {
    if (g_crashDir[0]) {
        char path[640];
        size_t n = 0;
        while (g_crashDir[n] && n < sizeof(path) - 64) {
            path[n] = g_crashDir[n];
            ++n;
        }
        const char* mid = "/native-crash-";
        for (size_t k = 0; mid[k] && n < sizeof(path) - 32; ++k) path[n++] = mid[k];
        // pid in decimal
        uint64_t pid = (uint64_t)getpid();
        char pbuf[24];
        int pi = 23;
        pbuf[pi] = '\0';
        if (pid == 0) pbuf[--pi] = '0';
        while (pid > 0 && pi > 0) {
            pbuf[--pi] = (char)('0' + (pid % 10));
            pid /= 10;
        }
        for (int k = pi; pbuf[k] && n < sizeof(path) - 8; ++k) path[n++] = pbuf[k];
        const char* ext = ".txt";
        for (size_t k = 0; ext[k] && n < sizeof(path) - 1; ++k) path[n++] = ext[k];
        path[n] = '\0';

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            crashWriteAll(fd, "PHOSPHOR NATIVE CRASH\nsignal ");
            crashWriteU64(fd, (uint64_t)sig);
            crashWriteAll(fd, "\nfault_addr ");
            crashWriteHex(fd, (uint64_t)info->si_addr);
#ifdef __aarch64__
            uint64_t pc = (uint64_t)((ucontext_t*)uctx)->uc_mcontext.pc;
#else
            uint64_t pc = 0;
#endif
            crashWriteAll(fd, "\ncrash_pc ");
            crashWriteHex(fd, pc);
            crashWriteAll(fd, "\nso_base ");
            crashWriteHex(fd, (uint64_t)g_soBase);
            crashWriteAll(fd, "\ncrash_offset ");
            crashWriteHex(fd, pc - (uint64_t)g_soBase);
            crashWriteAll(fd, "\n--- maps ---\n");
            int mfd = open("/proc/self/maps", O_RDONLY);
            if (mfd >= 0) {
                char mbuf[1024];
                ssize_t r;
                while ((r = read(mfd, mbuf, sizeof(mbuf))) > 0) {
                    size_t off = 0;
                    while (off < (size_t)r) {
                        ssize_t w = write(fd, mbuf + off, (size_t)(r - off));
                        if (w <= 0) break;
                        off += (size_t)w;
                    }
                }
                close(mfd);
            }
            close(fd);
        }
    }
    // Let the system take it from here (tombstone + crash dialog as usual).
    signal(sig, SIG_DFL);
    raise(sig);
}

// Step-by-step trace for the import path (diagnostic). Each line is flushed
// immediately so the last line present is the call that crashed. Only the
// first few frames are traced to bound file I/O.
void traceImport(const char* msg) {
    static int framesTraced = 0;
    if (!g_crashDir[0] || framesTraced >= 5) return;
    char path[640];
    size_t n = 0;
    while (g_crashDir[n] && n < sizeof(path) - 32) {
        path[n] = g_crashDir[n];
        ++n;
    }
    const char* tail = "/vk-import-trace.txt";
    for (size_t k = 0; tail[k] && n < sizeof(path) - 1; ++k) path[n++] = tail[k];
    path[n] = '\0';
    FILE* f = fopen(path, "a");
    if (f) {
        fprintf(f, "%s\n", msg);
        fflush(f);
        fclose(f);
    }
    if (msg[0] == '=' ) ++framesTraced;  // "= frame N" marks a new frame
}

void installCrashHandler(const char* dir) {
    size_t n = 0;
    while (dir && dir[n] && n < sizeof(g_crashDir) - 1) {
        g_crashDir[n] = dir[n];
        ++n;
    }
    g_crashDir[n] = '\0';
    Dl_info di{};
    if (dladdr((void*)installCrashHandler, &di) && di.dli_fbase) {
        g_soBase = di.dli_fbase;
    }
    struct sigaction sa{};
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

constexpr int kPresetCount = 5;
const char* kFragAssets[kPresetCount] = {
    "shaders/crt_aperture.frag.spv",
    "shaders/crt_shadow.frag.spv",
    "shaders/crt_slot.frag.spv",
    "shaders/crt_composite.frag.spv",
    "shaders/crt_mono.frag.spv",
};
const char* kVertAsset = "shaders/fullscreen.vert.spv";

// Push constants — MUST match shaders/common.glsl exactly (64 bytes).
struct PushConstants {
    float resolution[2];
    float time;
    float scanline;
    float mask;
    float curvature;
    float brightness;
    float contrast;
    float saturation;
    float vignette;
    float aspectMode;    // 0 = fit, 1 = fill, 2 = stretch
    float videoAspect;
    float phosphor;      // mono tint: 0 = green, 1 = amber, 2 = white
    float rotation;      // 0/1/2/3 = 0/90/180/270 degrees clockwise
    float zoom;          // 1 = normal, >1 zooms in, <1 zooms out
    float colorTemp;     // 0 = Cool (~9300K, factory), 1 = Medium, 2 = Warm (~6500K)
    float flip;          // 0 = normal, 1 = mirror horizontally (selfie fix)
    float colorMatrix;   // 0 = BT.601 (SD), 1 = BT.709 (HD) -- shader conversion
    float scanlineTvl;   // virtual CRT lines for scanline period (e.g. 240)
};
static_assert(sizeof(PushConstants) == 76, "push constant layout mismatch");

// User-tunable picture parameters (defaults = a healthy 2000s tube).
struct PictureParams {
    float scanline = 0.35f;
    float mask = 0.5f;
    float curvature = 0.05f;
    float brightness = 0.0f;
    float contrast = 1.05f;
    float saturation = 1.0f;
    float vignette = 0.15f;
    float aspectMode = 0.0f;
    float phosphor = 0.0f;
    float rotation = 0.0f;   // 0/1/2/3 = 0/90/180/270 deg clockwise
    float zoom = 1.0f;       // 1 = normal
    float colorTemp = 0.0f;  // 0 cool (factory), 1 medium, 2 warm
    float flip = 0.0f;
    float colorMatrix = 1.0f;  // HD default; VideoEngine sets 601 for SD
    float scanlineTvl = 240.0f;
};

struct VideoFrame {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    AHardwareBuffer* buffer = nullptr;
    bool layoutTransitioned = false;
};

// One packed planar frame: Y (w*h) then U (cw*ch) then V (cw*ch).
// state: 0 = free, 1 = writer is copying into it, 2 = pending upload,
// 3 = render thread is copying it to the staging buffer.
struct PlaneFrame {
    uint8_t* data = nullptr;
    uint32_t w = 0, h = 0, cw = 0, ch = 0;
    int state = 0;
};

class Renderer {
public:
    bool init(ANativeWindow* window, AAssetManager* assets);
    void shutdown();
    void shutdownLocked();  // lifecycleMutex_ must be held
    void onFrame(AHardwareBuffer* buffer);
    void onFrameYUV(const uint8_t* y, int yRowStride, int yPixStride,
                    const uint8_t* u, int uRowStride, int uPixStride,
                    const uint8_t* v, int vRowStride, int vPixStride,
                    uint32_t width, uint32_t height);
    void setPreset(int index);
    void setParam(int id, float value);
    void requestResize(int w, int h);

private:
    // setup
    bool createInstance();
    bool createSurface();
    bool pickPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createDescriptorSetLayout();
    bool createPipelineLayout();
    VkShaderModule loadShader(const char* assetPath);
    bool createPipelines();
    bool createDescriptorPoolAndSet();
    bool createFramebuffers();
    bool createCommandPoolAndBuffers();
    bool createSyncObjects();
    void destroySwapchainResources();

    // per-frame video import (AHB path; unused when the driver reports
    // VK_FORMAT_UNDEFINED — see the CPU upload path below)
    void destroyFrame(VideoFrame& f);
    bool importFrame(AHardwareBuffer* buffer);
    bool ensureConversion(VkFormat format,
                          const VkAndroidHardwareBufferFormatPropertiesANDROID& fmtProps);

    // CPU-upload video path: YUV_420_888 planes are converted to RGBA on the
    // CPU and uploaded to a plain sampled image. Used because the Mali driver
    // on some devices reports VK_FORMAT_UNDEFINED for decoder AHBs and then
    // crashes in vkCreateSamplerYcbcrConversion.
    bool createPlainSampler();
    bool createStagingResources();
    bool ensureVideoImages(uint32_t w, uint32_t h);
    void destroyVideoResources();

    // render loop
    void renderLoop();
    void recordCommands(VkCommandBuffer cmd, uint32_t imageIndex);

    ANativeWindow* window_ = nullptr;
    AAssetManager* assets_ = nullptr;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{0, 0};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainViews_;
    std::vector<VkFramebuffer> framebuffers_;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule vertModule_ = VK_NULL_HANDLE;
    VkShaderModule fragModules_[kPresetCount] = {};
    VkPipeline pipelines_[kPresetCount] = {};

    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion conversion_ = VK_NULL_HANDLE;
    VkFormat conversionFormat_ = VK_FORMAT_UNDEFINED;

    // CPU-upload video resources.
    // NOTE: ALL Vulkan queue/device work happens on the render thread. An
    // earlier design did uploads on the frame thread, but vkQueueSubmit /
    // vkQueueWaitIdle on one queue from two threads without external sync is
    // undefined behavior and hung the Mali GPU (freeze -> app kill -> reboot).
    VkSampler plainSampler_ = VK_NULL_HANDLE;
    // Planar YUV video images: [0]=Y (w x h), [1]=U, [2]=V (cw x ch, R8).
    VkImage videoImages_[3] = {};
    VkDeviceMemory videoMemories_[3] = {};
    VkImageView videoViews_[3] = {};
    uint32_t videoW_ = 0;
    uint32_t videoH_ = 0;
    uint32_t videoCw_ = 0;
    uint32_t videoCh_ = 0;
    VkBuffer stagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory_ = VK_NULL_HANDLE;
    void* stagingMapped_ = nullptr;
    bool videoInShaderRead_ = false;
    // Ping-pong planar frames. The writer claims a FREE slot, copies the
    // Y/U/V planes into it (plain byte copies, no colour maths), marks it
    // PENDING; the render thread claims a PENDING slot, copies it to the
    // staging buffer, then frees it. A frame is dropped, never torn, when
    // both slots are busy -- the old single-buffer race (writer converting
    // into cpuFrame_ while the render thread memcpy'd it) is gone.
    PlaneFrame planeSlots_[2];
    uint32_t uploadW_ = 0;
    uint32_t uploadH_ = 0;
    uint32_t uploadCw_ = 0;
    uint32_t uploadCh_ = 0;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;

    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    VkSemaphore renderFinished_ = VK_NULL_HANDLE;
    VkFence inFlightFences_[2] = {};
    int currentFrame_ = 0;

    // video frame state (written by JNI thread, read by render thread)
    std::mutex frameMutex_;
    VideoFrame frame_;
    bool frameReady_ = false;
    float videoAspect_ = 16.0f / 9.0f;
    // Wake-on-frame: the render loop sleeps on frameCv_ until a frame is
    // pending, a setting changes, or a resize is requested, instead of
    // redrawing the same frame at display refresh rate while paused.
    // (Composite is the only preset that animates with time, so it wakes
    // on a 33 ms timeout as well; the others sleep indefinitely.)
    std::atomic<bool> framePending_{false};
    std::atomic<bool> paramsDirty_{false};
    std::condition_variable frameCv_;
    std::mutex frameCvMutex_;

    // picture state
    std::mutex paramsMutex_;
    PictureParams params_;
    int preset_ = 0;

    std::thread renderThread_;
    std::atomic<bool> running_{false};
    // Serializes init/shutdown so a surface recreation (or double
    // surfaceCreated) can never destroy a joinable thread or shut down
    // twice concurrently. Both are called on the UI thread via JNI.
    std::mutex lifecycleMutex_;
    std::atomic<bool> resizePending_{false};
    int pendingW_ = 0, pendingH_ = 0;
    std::chrono::steady_clock::time_point startTime_;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID vkGetAHBProps_ = nullptr;
};

Renderer g_renderer;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

bool checkDeviceExtensions(VkPhysicalDevice dev, const std::vector<const char*>& required) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> props(count);
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &count, props.data());
    for (const char* name : required) {
        bool found = false;
        for (const auto& p : props) {
            if (strcmp(p.extensionName, name) == 0) { found = true; break; }
        }
        if (!found) {
            LOGE("missing device extension %s", name);
            return false;
        }
    }
    return true;
}

uint32_t findMemoryType(VkPhysicalDevice phys, uint32_t typeBits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(phys, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & flags) == flags) {
            return i;
        }
    }
    return UINT32_MAX;
}

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------

bool Renderer::init(ANativeWindow* window, AAssetManager* assets) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    // A second surfaceCreated without an intervening shutdown (surface
    // recreation race) must not leak the running thread — shut down cleanly
    // first. Destroying a joinable std::thread calls std::terminate.
    if (running_) shutdownLocked();
    window_ = window;
    assets_ = assets;
    startTime_ = std::chrono::steady_clock::now();

    if (!createInstance()) return false;
    if (!createSurface()) return false;
    if (!pickPhysicalDevice()) return false;
    if (!createDevice()) return false;
    vkGetAHBProps_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!vkGetAHBProps_) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID not found");
        return false;
    }
    if (!createSwapchain()) return false;
    if (!createRenderPass()) return false;
    if (!createDescriptorSetLayout()) return false;
    if (!createPipelineLayout()) return false;
    if (!createPipelines()) return false;
    if (!createDescriptorPoolAndSet()) return false;
    if (!createFramebuffers()) return false;
    if (!createCommandPoolAndBuffers()) return false;
    if (!createSyncObjects()) return false;
    if (!createPlainSampler()) return false;
    if (!createStagingResources()) return false;

    running_ = true;
    renderThread_ = std::thread(&Renderer::renderLoop, this);
    LOGI("Vulkan renderer initialized (%dx%d)", swapchainExtent_.width, swapchainExtent_.height);
    return true;
}

bool Renderer::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Phosphor";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &appInfo;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = extensions;

    if (vkCreateInstance(&ci, nullptr, &instance_) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }
    return true;
}

bool Renderer::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window_;
    if (vkCreateAndroidSurfaceKHR(instance_, &ci, nullptr, &surface_) != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed");
        return false;
    }
    return true;
}

bool Renderer::pickPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) {
        LOGE("no physical devices");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    const std::vector<const char*> required = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        "VK_ANDROID_external_memory_android_hardware_buffer",
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    };

    for (VkPhysicalDevice dev : devices) {
        if (!checkDeviceExtensions(dev, required)) continue;
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface_, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physDevice_ = dev;
                queueFamily_ = i;
                VkPhysicalDeviceProperties props;
                vkGetPhysicalDeviceProperties(dev, &props);
                LOGI("using GPU: %s", props.deviceName);
                return true;
            }
        }
    }
    LOGE("no suitable physical device");
    return false;
}

bool Renderer::createDevice() {
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    const char* extensions[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        "VK_ANDROID_external_memory_android_hardware_buffer",
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    };

    // Enable the YCbCr conversion feature explicitly.
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{};
    ycbcrFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;

    VkDeviceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pNext = &ycbcrFeatures;
    ci.queueCreateInfoCount = 1;
    ci.pQueueCreateInfos = &qci;
    ci.enabledExtensionCount = 5;
    ci.ppEnabledExtensionNames = extensions;

    if (vkCreateDevice(physDevice_, &ci, nullptr, &device_) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return false;
    }
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    return true;
}

// ---------------------------------------------------------------------------
// swapchain / render pass / pipelines
// ---------------------------------------------------------------------------

bool Renderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physDevice_, surface_, &caps);

    uint32_t fmtCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physDevice_, surface_, &fmtCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physDevice_, surface_, &fmtCount, formats.data());

    swapchainFormat_ = formats[0].format;
    VkColorSpaceKHR colorSpace = formats[0].colorSpace;
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_B8G8R8A8_SRGB &&
            f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            swapchainFormat_ = f.format;
            colorSpace = f.colorSpace;
            break;
        }
    }

    uint32_t modeCount = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(physDevice_, surface_, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    vkGetPhysicalDeviceSurfacePresentModesKHR(physDevice_, surface_, &modeCount, modes.data());
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    for (VkPresentModeKHR m : modes) {
        if (m == VK_PRESENT_MODE_MAILBOX_KHR) { presentMode = m; break; }
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = static_cast<uint32_t>(pendingW_ > 0 ? pendingW_ : ANativeWindow_getWidth(window_));
        extent.height = static_cast<uint32_t>(pendingH_ > 0 ? pendingH_ : ANativeWindow_getHeight(window_));
        extent.width = std::max(caps.minImageExtent.width,
                                std::min(caps.maxImageExtent.width, extent.width));
        extent.height = std::max(caps.minImageExtent.height,
                                 std::min(caps.maxImageExtent.height, extent.height));
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    VkSwapchainCreateInfoKHR ci{};
    ci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface = surface_;
    ci.minImageCount = imageCount;
    ci.imageFormat = swapchainFormat_;
    ci.imageColorSpace = colorSpace;
    ci.imageExtent = extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = caps.currentTransform;
    ci.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    ci.presentMode = presentMode;
    ci.clipped = VK_TRUE;

    if (vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed");
        return false;
    }
    swapchainExtent_ = extent;

    uint32_t imgCount = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &imgCount, nullptr);
    swapchainImages_.resize(imgCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imgCount, swapchainImages_.data());
    LOGI("swapchain: %dx%d, %u images", extent.width, extent.height, imgCount);
    return true;
}

bool Renderer::createRenderPass() {
    VkAttachmentDescription att{};
    att.format = swapchainFormat_;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorRef{};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount = 1;
    ci.pAttachments = &att;
    ci.subpassCount = 1;
    ci.pSubpasses = &subpass;
    ci.dependencyCount = 1;
    ci.pDependencies = &dep;

    if (vkCreateRenderPass(device_, &ci, nullptr, &renderPass_) != VK_SUCCESS) {
        LOGE("vkCreateRenderPass failed");
        return false;
    }
    return true;
}

bool Renderer::createDescriptorSetLayout() {
    VkDescriptorSetLayoutBinding bindings[3]{};
    for (int i = 0; i < 3; i++) {
        bindings[i].binding = (uint32_t)i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    }

    VkDescriptorSetLayoutCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount = 3;
    ci.pBindings = bindings;

    if (vkCreateDescriptorSetLayout(device_, &ci, nullptr, &setLayout_) != VK_SUCCESS) {
        LOGE("vkCreateDescriptorSetLayout failed");
        return false;
    }
    return true;
}

bool Renderer::createPipelineLayout() {
    VkPushConstantRange range{};
    range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    range.offset = 0;
    range.size = sizeof(PushConstants);

    VkPipelineLayoutCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    ci.setLayoutCount = 1;
    ci.pSetLayouts = &setLayout_;
    ci.pushConstantRangeCount = 1;
    ci.pPushConstantRanges = &range;

    if (vkCreatePipelineLayout(device_, &ci, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        LOGE("vkCreatePipelineLayout failed");
        return false;
    }
    return true;
}

VkShaderModule Renderer::loadShader(const char* assetPath) {
    AAsset* asset = AAssetManager_open(assets_, assetPath, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("asset not found: %s", assetPath);
        return VK_NULL_HANDLE;
    }
    size_t size = AAsset_getLength(asset);
    std::vector<uint8_t> data(size);
    if (size > 0 && AAsset_read(asset, data.data(), size) != static_cast<off_t>(size)) {
        LOGE("asset read failed: %s", assetPath);
        AAsset_close(asset);
        return VK_NULL_HANDLE;
    }
    AAsset_close(asset);
    VkShaderModuleCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = size;
    ci.pCode = reinterpret_cast<const uint32_t*>(data.data());
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device_, &ci, nullptr, &module) != VK_SUCCESS) {
        LOGE("vkCreateShaderModule failed: %s", assetPath);
    }
    return module;
}

bool Renderer::createPipelines() {
    vertModule_ = loadShader(kVertAsset);
    if (vertModule_ == VK_NULL_HANDLE) return false;
    for (int i = 0; i < kPresetCount; i++) {
        fragModules_[i] = loadShader(kFragAssets[i]);
        if (fragModules_[i] == VK_NULL_HANDLE) return false;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule_;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo inputAsm{};
    inputAsm.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAsm.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkViewport viewport{0, 0, 0, 0, 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, {0, 0}};
    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = &viewport;
    viewportState.scissorCount = 1;
    viewportState.pScissors = &scissor;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo msaa{};
    msaa.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    msaa.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAtt{};
    blendAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                              VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAtt;

    VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};
    dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynStates;

    for (int i = 0; i < kPresetCount; i++) {
        stages[1].module = fragModules_[i];
        VkGraphicsPipelineCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        ci.stageCount = 2;
        ci.pStages = stages;
        ci.pVertexInputState = &vertexInput;
        ci.pInputAssemblyState = &inputAsm;
        ci.pViewportState = &viewportState;
        ci.pRasterizationState = &raster;
        ci.pMultisampleState = &msaa;
        ci.pColorBlendState = &blend;
        ci.pDynamicState = &dynamic;
        ci.layout = pipelineLayout_;
        ci.renderPass = renderPass_;
        ci.subpass = 0;
        if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &ci, nullptr,
                                      &pipelines_[i]) != VK_SUCCESS) {
            LOGE("pipeline %d creation failed", i);
            return false;
        }
    }
    LOGI("created %d CRT pipelines", kPresetCount);
    return true;
}

bool Renderer::createDescriptorPoolAndSet() {
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 3;

    VkDescriptorPoolCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.maxSets = 1;
    ci.poolSizeCount = 1;
    ci.pPoolSizes = &poolSize;

    if (vkCreateDescriptorPool(device_, &ci, nullptr, &descriptorPool_) != VK_SUCCESS) {
        LOGE("vkCreateDescriptorPool failed");
        return false;
    }

    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = descriptorPool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &setLayout_;
    if (vkAllocateDescriptorSets(device_, &ai, &descriptorSet_) != VK_SUCCESS) {
        LOGE("vkAllocateDescriptorSets failed");
        return false;
    }
    return true;
}

bool Renderer::createFramebuffers() {
    swapchainViews_.resize(swapchainImages_.size());
    for (size_t i = 0; i < swapchainImages_.size(); i++) {
        VkImageViewCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        ci.image = swapchainImages_[i];
        ci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        ci.format = swapchainFormat_;
        ci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        ci.subresourceRange.levelCount = 1;
        ci.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &ci, nullptr, &swapchainViews_[i]) != VK_SUCCESS) {
            LOGE("swapchain image view %zu failed", i);
            return false;
        }
    }

    framebuffers_.resize(swapchainViews_.size());
    for (size_t i = 0; i < swapchainViews_.size(); i++) {
        VkFramebufferCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        ci.renderPass = renderPass_;
        ci.attachmentCount = 1;
        ci.pAttachments = &swapchainViews_[i];
        ci.width = swapchainExtent_.width;
        ci.height = swapchainExtent_.height;
        ci.layers = 1;
        if (vkCreateFramebuffer(device_, &ci, nullptr, &framebuffers_[i]) != VK_SUCCESS) {
            LOGE("framebuffer %zu failed", i);
            return false;
        }
    }
    return true;
}

bool Renderer::createCommandPoolAndBuffers() {
    VkCommandPoolCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.queueFamilyIndex = queueFamily_;
    ci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(device_, &ci, nullptr, &commandPool_) != VK_SUCCESS) {
        LOGE("vkCreateCommandPool failed");
        return false;
    }

    commandBuffers_.resize(framebuffers_.size());
    VkCommandBufferAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = commandPool_;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = static_cast<uint32_t>(commandBuffers_.size());
    if (vkAllocateCommandBuffers(device_, &ai, commandBuffers_.data()) != VK_SUCCESS) {
        LOGE("vkAllocateCommandBuffers failed");
        return false;
    }
    return true;
}

bool Renderer::createSyncObjects() {
    VkSemaphoreCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateSemaphore(device_, &sci, nullptr, &imageAvailable_) != VK_SUCCESS ||
        vkCreateSemaphore(device_, &sci, nullptr, &renderFinished_) != VK_SUCCESS) {
        LOGE("semaphore creation failed");
        return false;
    }
    for (int i = 0; i < 2; i++) {
        if (vkCreateFence(device_, &fci, nullptr, &inFlightFences_[i]) != VK_SUCCESS) {
            LOGE("fence creation failed");
            return false;
        }
    }
    return true;
}

void Renderer::destroySwapchainResources() {
    for (VkFramebuffer fb : framebuffers_) vkDestroyFramebuffer(device_, fb, nullptr);
    framebuffers_.clear();
    for (VkImageView v : swapchainViews_) vkDestroyImageView(device_, v, nullptr);
    swapchainViews_.clear();
    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
    swapchainImages_.clear();
}

// ---------------------------------------------------------------------------
// video frame import (AHardwareBuffer -> Vulkan external memory)
// ---------------------------------------------------------------------------

void Renderer::destroyFrame(VideoFrame& f) {
    if (f.view != VK_NULL_HANDLE) vkDestroyImageView(device_, f.view, nullptr);
    if (f.image != VK_NULL_HANDLE) vkDestroyImage(device_, f.image, nullptr);
    if (f.memory != VK_NULL_HANDLE) vkFreeMemory(device_, f.memory, nullptr);
    if (f.buffer) AHardwareBuffer_release(f.buffer);
    f = VideoFrame{};
}

bool Renderer::ensureConversion(
        VkFormat format, const VkAndroidHardwareBufferFormatPropertiesANDROID& fmtProps) {
    if (conversion_ != VK_NULL_HANDLE && conversionFormat_ == format) return true;
    if (conversion_ != VK_NULL_HANDLE) {
        vkDestroySampler(device_, sampler_, nullptr);
        vkDestroySamplerYcbcrConversion(device_, conversion_, nullptr);
        sampler_ = VK_NULL_HANDLE;
        conversion_ = VK_NULL_HANDLE;
    }

    VkSamplerYcbcrConversionCreateInfo ycc{};
    ycc.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    ycc.format = format;
    ycc.ycbcrModel = fmtProps.suggestedYcbcrModel;
    ycc.ycbcrRange = fmtProps.suggestedYcbcrRange;
    ycc.components = fmtProps.samplerYcbcrConversionComponents;
    ycc.xChromaOffset = fmtProps.suggestedXChromaOffset;
    ycc.yChromaOffset = fmtProps.suggestedYChromaOffset;
    ycc.chromaFilter = VK_FILTER_LINEAR;
    ycc.forceExplicitReconstruction = VK_FALSE;

    if (vkCreateSamplerYcbcrConversion(device_, &ycc, nullptr, &conversion_) != VK_SUCCESS) {
        LOGE("vkCreateSamplerYcbcrConversion failed");
        return false;
    }

    VkSamplerYcbcrConversionInfo convInfo{};
    convInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    convInfo.conversion = conversion_;

    VkSamplerCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.pNext = &convInfo;
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

    if (vkCreateSampler(device_, &sci, nullptr, &sampler_) != VK_SUCCESS) {
        LOGE("video sampler creation failed");
        return false;
    }
    conversionFormat_ = format;
    return true;
}

// ---------------------------------------------------------------------------
// CPU-upload video path
// ---------------------------------------------------------------------------

static inline uint8_t clamp8(int v) {
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

// YUV420 (with arbitrary row/pixel strides) -> RGBA, BT.601.
// Copy one YUV_420_888 plane into a tightly packed destination buffer.
// No colour maths here: conversion to RGB happens in the fragment shader
// (see sampleVideo in shaders/common.glsl). Fast path is a plain memcpy
// per row; the strided path only copies one byte per sample.
void copyPlane(const uint8_t* src, int rowStride, int pixStride,
               uint8_t* dst, uint32_t width, uint32_t height) {
    if (width == 0 || height == 0 || src == nullptr || dst == nullptr) return;
    if (pixStride == 1) {
        if ((uint32_t)rowStride == width) {
            memcpy(dst, src, (size_t)width * height);
            return;
        }
        for (uint32_t j = 0; j < height; ++j) {
            memcpy(dst + j * width, src + j * (uint32_t)rowStride, width);
        }
        return;
    }
    for (uint32_t j = 0; j < height; ++j) {
        const uint8_t* srow = src + j * (uint32_t)rowStride;
        uint8_t* drow = dst + j * width;
        for (uint32_t i = 0; i < width; ++i) {
            drow[i] = srow[i * (uint32_t)pixStride];
        }
    }
}

bool Renderer::createPlainSampler() {
    VkSamplerCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(device_, &sci, nullptr, &plainSampler_) != VK_SUCCESS) {
        LOGE("plain sampler creation failed");
        return false;
    }
    return true;
}

bool Renderer::createStagingResources() {
    // Packed planar staging: Y (3840x2160) + U + V (1920x1080 each).
    const VkDeviceSize stagingSize =
        (VkDeviceSize)3840u * 2160u + 2u * (VkDeviceSize)1920u * 1080u;
    VkBufferCreateInfo bci{};
    bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size = stagingSize;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device_, &bci, nullptr, &stagingBuffer_) != VK_SUCCESS) {
        LOGE("staging buffer creation failed");
        return false;
    }
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, stagingBuffer_, &req);
    uint32_t memType = findMemoryType(physDevice_, req.memoryTypeBits,
                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                     VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGE("no host-visible memory for staging");
        return false;
    }
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memType;
    if (vkAllocateMemory(device_, &ai, nullptr, &stagingMemory_) != VK_SUCCESS) {
        LOGE("staging memory allocation failed");
        return false;
    }
    if (vkBindBufferMemory(device_, stagingBuffer_, stagingMemory_, 0) != VK_SUCCESS) {
        LOGE("staging bind failed");
        return false;
    }
    if (vkMapMemory(device_, stagingMemory_, 0, req.size, 0, &stagingMapped_) != VK_SUCCESS) {
        LOGE("staging map failed");
        return false;
    }
    for (int i = 0; i < 2; i++) {
        planeSlots_[i].data = (uint8_t*)malloc((size_t)stagingSize);
        if (!planeSlots_[i].data) {
            LOGE("planar frame buffer malloc failed");
            return false;
        }
        planeSlots_[i].state = 0;
    }
    return true;
}

bool Renderer::ensureVideoImages(uint32_t w, uint32_t h) {
    uint32_t cw = (w + 1) / 2;
    uint32_t ch = (h + 1) / 2;
    if (videoImages_[0] != VK_NULL_HANDLE && videoW_ == w && videoH_ == h) return true;
    for (int i = 0; i < 3; i++) {
        if (videoViews_[i] != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, videoViews_[i], nullptr);
            videoViews_[i] = VK_NULL_HANDLE;
        }
        if (videoImages_[i] != VK_NULL_HANDLE) {
            vkDestroyImage(device_, videoImages_[i], nullptr);
            videoImages_[i] = VK_NULL_HANDLE;
        }
        if (videoMemories_[i] != VK_NULL_HANDLE) {
            vkFreeMemory(device_, videoMemories_[i], nullptr);
            videoMemories_[i] = VK_NULL_HANDLE;
        }
    }
    videoW_ = w;
    videoH_ = h;
    videoCw_ = cw;
    videoCh_ = ch;
    videoInShaderRead_ = false;

    uint32_t extents[3][2] = {{w, h}, {cw, ch}, {cw, ch}};
    for (int i = 0; i < 3; i++) {
        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = VK_FORMAT_R8_UNORM;
        ici.extent = {extents[i][0], extents[i][1], 1};
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(device_, &ici, nullptr, &videoImages_[i]) != VK_SUCCESS) {
            LOGE("planar video image %d creation failed", i);
            return false;
        }
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(device_, videoImages_[i], &req);
        uint32_t memType = findMemoryType(physDevice_, req.memoryTypeBits,
                                          VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memType == UINT32_MAX) {
            LOGE("no device-local memory for planar video image %d", i);
            return false;
        }
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = memType;
        if (vkAllocateMemory(device_, &ai, nullptr, &videoMemories_[i]) != VK_SUCCESS ||
            vkBindImageMemory(device_, videoImages_[i], videoMemories_[i], 0) != VK_SUCCESS) {
            LOGE("planar video image %d memory failed", i);
            return false;
        }
        VkImageViewCreateInfo vci{};
        vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image = videoImages_[i];
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = VK_FORMAT_R8_UNORM;
        vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vci.subresourceRange.levelCount = 1;
        vci.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &vci, nullptr, &videoViews_[i]) != VK_SUCCESS) {
            LOGE("planar video image view %d failed", i);
            return false;
        }
    }
    VkDescriptorImageInfo imgInfos[3]{};
    VkWriteDescriptorSet writes[3]{};
    for (int i = 0; i < 3; i++) {
        imgInfos[i].sampler = plainSampler_;
        imgInfos[i].imageView = videoViews_[i];
        imgInfos[i].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = (uint32_t)i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[i].pImageInfo = &imgInfos[i];
    }
    vkUpdateDescriptorSets(device_, 3, writes, 0, nullptr);
    return true;
}

void Renderer::onFrameYUV(const uint8_t* y, int yRowStride, int yPixStride,
                           const uint8_t* u, int uRowStride, int uPixStride,
                           const uint8_t* v, int vRowStride, int vPixStride,
                           uint32_t width, uint32_t height) {
    // Frame thread: plain plane copies only -- no per-pixel colour maths.
    // The GPU does YUV->RGB in the fragment shader (sampleVideo).
    if (width == 0 || height == 0 || width > 3840 || height > 2160) return;
    uint32_t cw = (width + 1) / 2;
    uint32_t ch = (height + 1) / 2;
    int slot = -1;
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        if (!running_) return;
        for (int i = 0; i < 2; i++) {
            if (planeSlots_[i].state == 0 && planeSlots_[i].data != nullptr) {
                planeSlots_[i].state = 1;
                slot = i;
                break;
            }
        }
    }
    if (slot < 0) return;  // both slots busy: drop this frame, never tear one
    uint8_t* dst = planeSlots_[slot].data;
    copyPlane(y, yRowStride, yPixStride, dst, width, height);
    copyPlane(u, uRowStride, uPixStride, dst + (size_t)width * height, cw, ch);
    copyPlane(v, vRowStride, vPixStride,
              dst + (size_t)width * height + (size_t)cw * ch, cw, ch);
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        planeSlots_[slot].w = width;
        planeSlots_[slot].h = height;
        planeSlots_[slot].cw = cw;
        planeSlots_[slot].ch = ch;
        planeSlots_[slot].state = 2;
    }
    framePending_ = true;
    frameCv_.notify_all();
    std::lock_guard<std::mutex> lock(frameMutex_);
    if (!running_) return;
    videoAspect_ = (float)width / (float)height;
}

void Renderer::destroyVideoResources() {
    for (int i = 0; i < 2; i++) {
        if (planeSlots_[i].data) {
            free(planeSlots_[i].data);
            planeSlots_[i].data = nullptr;
        }
        planeSlots_[i].state = 0;
    }
    if (stagingMapped_) {
        vkUnmapMemory(device_, stagingMemory_);
        stagingMapped_ = nullptr;
    }
    for (int i = 0; i < 3; i++) {
        if (videoViews_[i] != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, videoViews_[i], nullptr);
            videoViews_[i] = VK_NULL_HANDLE;
        }
        if (videoImages_[i] != VK_NULL_HANDLE) {
            vkDestroyImage(device_, videoImages_[i], nullptr);
            videoImages_[i] = VK_NULL_HANDLE;
        }
        if (videoMemories_[i] != VK_NULL_HANDLE) {
            vkFreeMemory(device_, videoMemories_[i], nullptr);
            videoMemories_[i] = VK_NULL_HANDLE;
        }
    }
}

bool Renderer::importFrame(AHardwareBuffer* buffer) {
    static int frameNo = 0;
    ++frameNo;
    char fmsg[64];
    snprintf(fmsg, sizeof(fmsg), "= frame %d", frameNo);
    traceImport(fmsg);

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    snprintf(fmsg, sizeof(fmsg), "describe: %dx%d", (int)desc.width, (int)desc.height);
    traceImport(fmsg);

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
    fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &fmtProps;
    traceImport("calling vkGetAHBProps");
    if (vkGetAHBProps_(device_, buffer, &props) != VK_SUCCESS) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID failed");
        return false;
    }
    snprintf(fmsg, sizeof(fmsg), "props ok: format=%d", (int)fmtProps.format);
    traceImport(fmsg);

    // The YCbCr conversion must exist BEFORE the image view is created: a
    // multi-planar view sampled through a conversion sampler without
    // VkSamplerYcbcrConversionInfo in its pNext chain is undefined behavior
    // and crashes drivers on first use.
    traceImport("calling ensureConversion");
    if (!ensureConversion(fmtProps.format, fmtProps)) {
        return false;
    }
    traceImport("ensureConversion ok");

    VkExternalMemoryImageCreateInfo extInfo{};
    extInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.pNext = &extInfo;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = fmtProps.format;
    ici.extent = {desc.width, desc.height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    traceImport("calling vkCreateImage");
    if (vkCreateImage(device_, &ici, nullptr, &image) != VK_SUCCESS) {
        LOGE("video VkImage creation failed");
        return false;
    }
    traceImport("createImage ok");

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, image, &memReq);
    uint32_t memType = findMemoryType(physDevice_, props.memoryTypeBits,
                                      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        LOGE("no suitable memory type for video frame");
        vkDestroyImage(device_, image, nullptr);
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;

    VkMemoryDedicatedAllocateInfo dedInfo{};
    dedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedInfo.pNext = &importInfo;
    dedInfo.image = image;

    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.pNext = &dedInfo;
    ai.allocationSize = memReq.size;
    ai.memoryTypeIndex = memType;

    VkDeviceMemory memory = VK_NULL_HANDLE;
    traceImport("calling vkAllocateMemory");
    if (vkAllocateMemory(device_, &ai, nullptr, &memory) != VK_SUCCESS ||
        vkBindImageMemory(device_, image, memory, 0) != VK_SUCCESS) {
        LOGE("video frame memory import failed");
        if (memory) vkFreeMemory(device_, memory, nullptr);
        vkDestroyImage(device_, image, nullptr);
        return false;
    }
    traceImport("allocate+bind ok");

    VkSamplerYcbcrConversionInfo viewConvInfo{};
    viewConvInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    viewConvInfo.conversion = conversion_;

    VkImageViewCreateInfo vci{};
    vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.pNext = &viewConvInfo;
    vci.image = image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = fmtProps.format;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;

    VkImageView view = VK_NULL_HANDLE;
    traceImport("calling vkCreateImageView");
    if (vkCreateImageView(device_, &vci, nullptr, &view) != VK_SUCCESS) {
        LOGE("video image view failed");
        vkFreeMemory(device_, memory, nullptr);
        vkDestroyImage(device_, image, nullptr);
        return false;
    }
    traceImport("createImageView ok");

    AHardwareBuffer_acquire(buffer);
    destroyFrame(frame_);
    frame_.image = image;
    frame_.memory = memory;
    frame_.view = view;
    frame_.buffer = buffer;
    frame_.layoutTransitioned = false;
    videoAspect_ = static_cast<float>(desc.width) / static_cast<float>(desc.height);

    VkDescriptorImageInfo imgInfo{};
    imgInfo.sampler = sampler_;
    imgInfo.imageView = view;
    imgInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descriptorSet_;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &imgInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

    frameReady_ = true;
    return true;
}

void Renderer::onFrame(AHardwareBuffer* buffer) {
    std::lock_guard<std::mutex> lock(frameMutex_);
    if (device_ == VK_NULL_HANDLE || !running_) {
        return;
    }
    // The old frame may still be in flight; wait before tearing it down.
    vkDeviceWaitIdle(device_);
    if (!importFrame(buffer)) {
        LOGE("frame import failed");
    }
}

void Renderer::setPreset(int index) {
    std::lock_guard<std::mutex> lock(paramsMutex_);
    if (index >= 0 && index < kPresetCount) preset_ = index;
    paramsDirty_ = true;
    frameCv_.notify_all();
}

void Renderer::setParam(int id, float value) {
    std::lock_guard<std::mutex> lock(paramsMutex_);
    switch (id) {
        case 0: params_.scanline = value; break;
        case 1: params_.mask = value; break;
        case 2: params_.curvature = value; break;
        case 3: params_.brightness = value; break;
        case 4: params_.contrast = value; break;
        case 5: params_.saturation = value; break;
        case 6: params_.vignette = value; break;
        case 7: params_.aspectMode = value; break;
        case 8: params_.phosphor = value; break;
        case 9: params_.rotation = value; break;
        case 10: params_.zoom = value; break;
        case 11: params_.colorTemp = value; break;
        case 12: params_.flip = value; break;
        case 13: params_.colorMatrix = value; break;
        case 14: params_.scanlineTvl = value; break;
        default: break;
    }
    paramsDirty_ = true;
    frameCv_.notify_all();
}

void Renderer::requestResize(int w, int h) {
    pendingW_ = w;
    pendingH_ = h;
    resizePending_ = true;
    frameCv_.notify_all();
}

// ---------------------------------------------------------------------------
// render loop
// ---------------------------------------------------------------------------

void Renderer::recordCommands(VkCommandBuffer cmd, uint32_t imageIndex) {
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(cmd, &begin);

    // Upload any pending planar frame. The render thread claims a PENDING
    // slot, copies its packed Y/U/V planes to the staging buffer, then
    // frees the slot -- the writer can never overwrite a frame mid-upload.
    int uploadSlot = -1;
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        for (int i = 0; i < 2; i++) {
            if (planeSlots_[i].state == 2) {
                planeSlots_[i].state = 3;
                uploadSlot = i;
                uploadW_ = planeSlots_[i].w;
                uploadH_ = planeSlots_[i].h;
                uploadCw_ = planeSlots_[i].cw;
                uploadCh_ = planeSlots_[i].ch;
                break;
            }
        }
    }
    bool doUpload = false;
    if (uploadSlot >= 0 && stagingMapped_ != nullptr) {
        uint8_t* staging = (uint8_t*)stagingMapped_;
        const uint8_t* src = planeSlots_[uploadSlot].data;
        size_t ySize = (size_t)uploadW_ * uploadH_;
        size_t cSize = (size_t)uploadCw_ * uploadCh_;
        memcpy(staging, src, ySize);
        memcpy(staging + ySize, src + ySize, cSize);
        memcpy(staging + ySize + cSize, src + ySize + cSize, cSize);
        doUpload = true;
    }
    if (doUpload && ensureVideoImages(uploadW_, uploadH_)) {
        VkImageMemoryBarrier b1[3]{};
        for (int i = 0; i < 3; i++) {
            b1[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b1[i].oldLayout = videoInShaderRead_ ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                                  : VK_IMAGE_LAYOUT_UNDEFINED;
            b1[i].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b1[i].srcAccessMask = 0;
            b1[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            b1[i].image = videoImages_[i];
            b1[i].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            b1[i].subresourceRange.levelCount = 1;
            b1[i].subresourceRange.layerCount = 1;
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                             0, nullptr, 0, nullptr, 3, b1);

        VkBufferImageCopy regions[3]{};
        size_t ySize = (size_t)uploadW_ * uploadH_;
        size_t cSize = (size_t)uploadCw_ * uploadCh_;
        regions[0].bufferOffset = 0;
        regions[0].imageExtent = {uploadW_, uploadH_, 1};
        regions[0].imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        regions[0].imageSubresource.layerCount = 1;
        regions[1].bufferOffset = ySize;
        regions[1].imageExtent = {uploadCw_, uploadCh_, 1};
        regions[1].imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        regions[1].imageSubresource.layerCount = 1;
        regions[2].bufferOffset = ySize + cSize;
        regions[2].imageExtent = {uploadCw_, uploadCh_, 1};
        regions[2].imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        regions[2].imageSubresource.layerCount = 1;
        for (int i = 0; i < 3; i++) {
            vkCmdCopyBufferToImage(cmd, stagingBuffer_, videoImages_[i],
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &regions[i]);
        }

        VkImageMemoryBarrier b2[3]{};
        for (int i = 0; i < 3; i++) {
            b2[i] = b1[i];
            b2[i].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b2[i].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b2[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            b2[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                             0, nullptr, 0, nullptr, 3, b2);
        videoInShaderRead_ = true;
        frameReady_ = true;
    }
    if (uploadSlot >= 0) {
        std::lock_guard<std::mutex> lock(frameMutex_);
        planeSlots_[uploadSlot].state = 0;
        bool anyPending = false;
        for (int i = 0; i < 2; i++) {
            if (planeSlots_[i].state == 2) anyPending = true;
        }
        framePending_ = anyPending;
    }

    bool haveFrame = false;
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        haveFrame = frameReady_ && videoImages_[0] != VK_NULL_HANDLE;
    }

    VkClearValue clear{};
    clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkRenderPassBeginInfo rp{};
    rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rp.renderPass = renderPass_;
    rp.framebuffer = framebuffers_[imageIndex];
    rp.renderArea.extent = swapchainExtent_;
    rp.clearValueCount = 1;
    rp.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &rp, VK_SUBPASS_CONTENTS_INLINE);

    if (haveFrame) {
        VkViewport viewport{};
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        VkRect2D scissor{{0, 0}, swapchainExtent_};
        vkCmdSetScissor(cmd, 0, 1, &scissor);

        int preset;
        PictureParams params;
        {
            std::lock_guard<std::mutex> lock(paramsMutex_);
            preset = preset_;
            params = params_;
        }

        auto now = std::chrono::steady_clock::now();
        float t = std::chrono::duration<float>(now - startTime_).count();

        PushConstants pc{};
        pc.resolution[0] = static_cast<float>(swapchainExtent_.width);
        pc.resolution[1] = static_cast<float>(swapchainExtent_.height);
        pc.time = t;
        pc.scanline = params.scanline;
        pc.mask = params.mask;
        pc.curvature = params.curvature;
        pc.brightness = params.brightness;
        pc.contrast = params.contrast;
        pc.saturation = params.saturation;
        pc.vignette = params.vignette;
        pc.aspectMode = params.aspectMode;
        pc.videoAspect = videoAspect_;
        pc.phosphor = params.phosphor;
        pc.rotation = params.rotation;
        pc.zoom = params.zoom;
        pc.colorTemp = params.colorTemp;
        pc.flip = params.flip;
        pc.colorMatrix = params.colorMatrix;
        pc.scanlineTvl = params.scanlineTvl;

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines_[preset]);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_,
                                0, 1, &descriptorSet_, 0, nullptr);
        vkCmdPushConstants(cmd, pipelineLayout_,
                           VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(pc), &pc);
        vkCmdDraw(cmd, 3, 1, 0, 0);
    }

    vkCmdEndRenderPass(cmd);
    vkEndCommandBuffer(cmd);
}

void Renderer::renderLoop() {
    while (running_) {
        // Sleep until there is work: a new frame, a settings change, or a
        // resize. Composite animates with time, so it also wakes every
        // 33 ms; every other preset sleeps until something actually changes.
        {
            int presetCopy = 0;
            {
                std::lock_guard<std::mutex> lock(paramsMutex_);
                presetCopy = preset_;
            }
            std::unique_lock<std::mutex> lk(frameCvMutex_);
            auto pred = [&] {
                return !running_.load() || framePending_.load() ||
                       resizePending_.load() || paramsDirty_.load();
            };
            if (presetCopy == 3) {
                frameCv_.wait_for(lk, std::chrono::milliseconds(33), pred);
            } else {
                frameCv_.wait(lk, pred);
            }
        }
        if (!running_) break;
        paramsDirty_ = false;
        if (resizePending_.exchange(false)) {
            vkDeviceWaitIdle(device_);
            destroySwapchainResources();
            vkFreeCommandBuffers(device_, commandPool_,
                                 static_cast<uint32_t>(commandBuffers_.size()),
                                 commandBuffers_.data());
            commandBuffers_.clear();
            if (!createSwapchain() || !createFramebuffers() || !createCommandPoolAndBuffers()) {
                LOGE("swapchain recreation failed");
                running_ = false;
                break;
            }
        }

        uint32_t imageIndex = 0;
        VkResult res = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                             imageAvailable_, VK_NULL_HANDLE, &imageIndex);
        if (res == VK_ERROR_OUT_OF_DATE_KHR) {
            resizePending_ = true;
            continue;
        }
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
            LOGE("acquire failed: %d", res);
            continue;
        }

        vkWaitForFences(device_, 1, &inFlightFences_[currentFrame_], VK_TRUE, UINT64_MAX);
        vkResetFences(device_, 1, &inFlightFences_[currentFrame_]);
        vkResetCommandBuffer(commandBuffers_[imageIndex], 0);
        recordCommands(commandBuffers_[imageIndex], imageIndex);

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &imageAvailable_;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commandBuffers_[imageIndex];
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderFinished_;
        vkQueueSubmit(queue_, 1, &submit, inFlightFences_[currentFrame_]);

        VkPresentInfoKHR present{};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &renderFinished_;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &imageIndex;
        res = vkQueuePresentKHR(queue_, &present);
        if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) {
            resizePending_ = true;
        }

        currentFrame_ = (currentFrame_ + 1) % 2;
    }
}

void Renderer::shutdown() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    shutdownLocked();
}

void Renderer::shutdownLocked() {
    running_ = false;
    frameCv_.notify_all();
    if (renderThread_.joinable()) renderThread_.join();
    if (device_ == VK_NULL_HANDLE) return;

    vkDeviceWaitIdle(device_);
    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        destroyFrame(frame_);
        destroyVideoResources();
    }

    vkDestroySemaphore(device_, imageAvailable_, nullptr);
    vkDestroySemaphore(device_, renderFinished_, nullptr);
    for (int i = 0; i < 2; i++) vkDestroyFence(device_, inFlightFences_[i], nullptr);
    vkDestroyCommandPool(device_, commandPool_, nullptr);
    destroySwapchainResources();
    vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
    if (sampler_ != VK_NULL_HANDLE) vkDestroySampler(device_, sampler_, nullptr);
    if (conversion_ != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(device_, conversion_, nullptr);
    }
    for (int i = 0; i < kPresetCount; i++) {
        if (pipelines_[i] != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipelines_[i], nullptr);
        if (fragModules_[i] != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragModules_[i], nullptr);
    }
    if (vertModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertModule_, nullptr);
    vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
    vkDestroyRenderPass(device_, renderPass_, nullptr);
    vkDestroyDevice(device_, nullptr);
    vkDestroySurfaceKHR(instance_, surface_, nullptr);
    vkDestroyInstance(instance_, nullptr);
    if (window_) ANativeWindow_release(window_);

    device_ = VK_NULL_HANDLE;
    LOGI("Vulkan renderer shut down");
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                        jobject surface, jobject assetManager,
                                                        jstring crashDir) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    if (!assets) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "AAssetManager_fromJava failed");
        ANativeWindow_release(window);
        return JNI_FALSE;
    }
    const char* cdir = crashDir ? env->GetStringUTFChars(crashDir, nullptr) : nullptr;
    installCrashHandler(cdir ? cdir : "");
    if (cdir) env->ReleaseStringUTFChars(crashDir, cdir);
    return g_renderer.init(window, assets) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeOnFrame(JNIEnv* env, jobject /*thiz*/,
                                                           jobject hardwareBuffer) {
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buffer) g_renderer.onFrame(buffer);
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeOnFrameYUV(
    JNIEnv* env, jobject /*thiz*/,
    jobject yBuf, jint yRowStride, jint yPixStride,
    jobject uBuf, jint uRowStride, jint uPixStride,
    jobject vBuf, jint vRowStride, jint vPixStride,
    jint width, jint height) {
    const uint8_t* y = (const uint8_t*)env->GetDirectBufferAddress(yBuf);
    const uint8_t* u = (const uint8_t*)env->GetDirectBufferAddress(uBuf);
    const uint8_t* v = (const uint8_t*)env->GetDirectBufferAddress(vBuf);
    if (!y || !u || !v) return;
    g_renderer.onFrameYUV(y, yRowStride, yPixStride,
                          u, uRowStride, uPixStride,
                          v, vRowStride, vPixStride,
                          (uint32_t)width, (uint32_t)height);
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    g_renderer.requestResize(w, h);
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeSetPreset(JNIEnv*, jobject, jint index) {
    g_renderer.setPreset(index);
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeSetParam(JNIEnv*, jobject, jint id, jfloat value) {
    g_renderer.setParam(id, value);
}

JNIEXPORT void JNICALL
Java_com_phosphor_player_vulkan_VulkanBridge_nativeRelease(JNIEnv*, jobject) {
    g_renderer.shutdown();
}

}  // extern "C"
