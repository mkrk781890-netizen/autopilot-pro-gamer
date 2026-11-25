#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG_TAG "AutopilotEngineCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace autopilot {

enum HudButtonId : jint {
    HUD_FIRE = 0,
    HUD_LEFT_FIRE = 1,
    HUD_JUMP = 2,
    HUD_CROUCH = 3,
    HUD_PRONE = 4,
    HUD_SCOPE = 5,
    HUD_GLOO_WALL = 6,
    HUD_MEDKIT = 7,
    HUD_INHALER = 8,
    HUD_SPRINT = 9,
    HUD_RELOAD = 10,
    HUD_SECONDARY_WEAPON = 11,
    HUD_PRIMARY_WEAPON = 12,
    HUD_JOYSTICK = 13
};

enum WeaponClassId : jint {
    WEAPON_SMG = 0,
    WEAPON_AR = 1,
    WEAPON_SHOTGUN = 2,
    WEAPON_SNIPER = 3,
    WEAPON_LMG = 4
};

struct Vec3 {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

struct EnemyTarget {
    int id = 0;
    float normX = 0.5f;
    float normY = 0.5f;
    float headX = 0.5f;
    float headY = 0.45f;
    float width = 0.05f;
    float height = 0.12f;
    float distanceEst = 20.0f;
    float estimatedDps = 120.0f;
    float azimuthRad = 0.0f;
    bool isFiring = false;
    bool isReloading = false;
    bool isBehindCover = false;
    bool isFightingAnother = false;
    bool inZoneGap = false;
    int64_t lastSeenNs = 0;
    int64_t lastBurstEndNs = 0;
};

struct LootItem {
    float normX = 0.5f;
    float normY = 0.5f;
    float distanceEst = 10.0f;
    int tier = 1;
    int weaponClass = WEAPON_AR;
    bool isWeapon = false;
    bool isMedkit = false;
    bool isAmmo = false;
    bool isLootBox = false;
};

struct ObstacleInfo {
    float normX = 0.5f;
    float normY = 0.5f;
    float distanceEst = 5.0f;
    bool isLowVaultable = false;
    bool isTallBuilding = false;
    bool isDoor = false;
    bool isWindow = false;
    bool isElevated = false;
    bool providesHardCover = false;
};

struct AudioCue3D {
    float azimuthRad = 0.0f;
    float distanceEst = 15.0f;
    bool isFootstep = false;
    bool isGunfire = false;
    int64_t timestampNs = 0;
};

struct KalmanTrack {
    int id = 0;
    bool initialized = false;
    std::array<float, 6> x{}; // x, y, z, vx, vy, vz
    std::array<float, 36> P{};
    int64_t lastUpdateNs = 0;

    void init(int trackId, float mx, float my, float mz, int64_t nowNs) {
        id = trackId;
        initialized = true;
        x = {mx, my, mz, 0.0f, 0.0f, 0.0f};
        P.fill(0.0f);
        for (int i = 0; i < 6; ++i) {
            P[i * 6 + i] = (i < 3) ? 0.02f : 0.5f;
        }
        lastUpdateNs = nowNs;
    }

    void update(float mx, float my, float mz, int64_t nowNs) {
        if (!initialized) {
            init(id, mx, my, mz, nowNs);
            return;
        }
        float dt = static_cast<float>(nowNs - lastUpdateNs) * 1e-9f;
        dt = std::clamp(dt, 0.004f, 0.25f);
        lastUpdateNs = nowNs;

        x[0] += x[3] * dt;
        x[1] += x[4] * dt;
        x[2] += x[5] * dt;

        std::array<float, 36> F{};
        for (int i = 0; i < 6; ++i) F[i * 6 + i] = 1.0f;
        F[0 * 6 + 3] = dt;
        F[1 * 6 + 4] = dt;
        F[2 * 6 + 5] = dt;

        std::array<float, 36> FP{};
        for (int r = 0; r < 6; ++r) {
            for (int c = 0; c < 6; ++c) {
                float sum = 0.0f;
                for (int k = 0; k < 6; ++k) sum += F[r * 6 + k] * P[k * 6 + c];
                FP[r * 6 + c] = sum;
            }
        }
        std::array<float, 36> nextP{};
        for (int r = 0; r < 6; ++r) {
            for (int c = 0; c < 6; ++c) {
                float sum = 0.0f;
                for (int k = 0; k < 6; ++k) sum += FP[r * 6 + k] * F[c * 6 + k];
                nextP[r * 6 + c] = sum;
            }
        }
        const float qPos = 0.0015f * dt;
        const float qVel = 0.18f * dt;
        nextP[0 * 6 + 0] += qPos;
        nextP[1 * 6 + 1] += qPos;
        nextP[2 * 6 + 2] += qPos;
        nextP[3 * 6 + 3] += qVel;
        nextP[4 * 6 + 4] += qVel;
        nextP[5 * 6 + 5] += qVel;
        P = nextP;

        const float meas[3] = {mx, my, mz};
        const float R = 0.008f;
        for (int i = 0; i < 3; ++i) {
            float innovation = meas[i] - x[i];
            float S = P[i * 6 + i] + R;
            float invS = (S > 1e-6f) ? (1.0f / S) : 0.0f;
            float Kpos = P[i * 6 + i] * invS;
            float Kvel = P[(i + 3) * 6 + i] * invS;

            x[i] += Kpos * innovation;
            x[i + 3] = std::clamp(x[i + 3] + Kvel * innovation, -1.8f, 1.8f);

            float pii = P[i * 6 + i];
            float pvi = P[(i + 3) * 6 + i];
            P[i * 6 + i] = (1.0f - Kpos) * pii;
            P[(i + 3) * 6 + i] = (1.0f - Kpos) * pvi;
            P[i * 6 + (i + 3)] = P[(i + 3) * 6 + i];
            P[(i + 3) * 6 + (i + 3)] = std::max(0.0001f, P[(i + 3) * 6 + (i + 3)] - Kvel * pvi);
        }
    }

    Vec3 predictAhead(float leadTimeSec) const {
        return {
            std::clamp(x[0] + x[3] * leadTimeSec, 0.05f, 0.95f),
            std::clamp(x[1] + x[4] * leadTimeSec, 0.05f, 0.95f),
            std::max(1.0f, x[2] + x[5] * leadTimeSec)
        };
    }
};

struct PaceProfile {
    float aggressionFactor = 0.80f;
    float coverFrequency = 0.55f;
    float rushSpeed = 0.85f;
};

struct EngineStateCache {
    int frameWidth = 1280;
    int frameHeight = 720;
    std::string targetPackage;
    std::string directive;
    PaceProfile pace;

    float hp = 100.0f;
    float ep = 50.0f;
    int primaryAmmo = 30;
    int primaryMaxAmmo = 30;
    int secondaryAmmo = 15;
    int weaponClass = WEAPON_AR;
    int weaponTier = 2;
    bool isArmed = true;
    bool inPlane = false;
    bool inVehicle = false;
    bool justExitedVehicle = false;
    bool isScoped = false;
    bool isProne = false;
    bool underFire = false;
    bool hasNearbyCover = false;
    bool inZoneGap = false;
    float zoneDistanceRatio = 0.20f;
    float backpackUsage = 0.45f;
    int medkitCount = 3;
    int inhalerCount = 4;
    int glooWallCount = 4;
    int grenadeCount = 2;

    bool isCookingGrenade = false;
    int64_t grenadeCookStartNs = 0;

    float roamHeadingRad = 0.0f;
    float zigZagPhase = 0.0f;
    int64_t vehicleExitNs = 0;
    int64_t ambushStartNs = 0;
    int64_t peekPhaseStartNs = 0;
    int64_t lastCookedGrenadeNs = 0;
    int64_t lastCleanupNs = 0;
    int64_t lastBaitNs = 0;
    int64_t lastHotDropNs = 0;
    int64_t lastHealNs = 0;

    std::vector<EnemyTarget> enemies;
    std::vector<LootItem> lootItems;
    std::vector<ObstacleInfo> obstacles;
    std::vector<AudioCue3D> audioCues;
    std::unordered_map<int, KalmanTrack> tracks;
};

struct TFLiteRuntimeHook {
    void* handle = nullptr;
    void* (*modelCreateFromFile)(const char*) = nullptr;
    void (*modelDelete)(void*) = nullptr;
    void* (*interpreterCreate)(void*, void*) = nullptr;
    void (*interpreterDelete)(void*) = nullptr;
    int (*interpreterAllocateTensors)(void*) = nullptr;
    int (*interpreterInvoke)(void*) = nullptr;
    void* model = nullptr;
    void* interpreter = nullptr;
    bool ready = false;

    void init(const char* modelPath) {
        if (handle != nullptr) return;
        handle = dlopen("libtensorflowlite_c.so", RTLD_LAZY);
        if (!handle) {
            ready = false;
            return;
        }
        modelCreateFromFile = reinterpret_cast<void* (*)(const char*)>(
            dlsym(handle, "TfLiteModelCreateFromFile"));
        modelDelete = reinterpret_cast<void (*)(void*)>(
            dlsym(handle, "TfLiteModelDelete"));
        interpreterCreate = reinterpret_cast<void* (*)(void*, void*)>(
            dlsym(handle, "TfLiteInterpreterCreate"));
        interpreterDelete = reinterpret_cast<void (*)(void*)>(
            dlsym(handle, "TfLiteInterpreterDelete"));
        interpreterAllocateTensors = reinterpret_cast<int (*)(void*)>(
            dlsym(handle, "TfLiteInterpreterAllocateTensors"));
        interpreterInvoke = reinterpret_cast<int (*)(void*)>(
            dlsym(handle, "TfLiteInterpreterInvoke"));

        if (modelCreateFromFile && interpreterCreate &&
            interpreterAllocateTensors && interpreterInvoke && modelPath != nullptr) {
            model = modelCreateFromFile(modelPath);
            if (model) {
                interpreter = interpreterCreate(model, nullptr);
                if (interpreter && interpreterAllocateTensors(interpreter) == 0) {
                    ready = true;
                }
            }
        }
    }

    void shutdown() {
        if (interpreter && interpreterDelete) interpreterDelete(interpreter);
        if (model && modelDelete) modelDelete(model);
        interpreter = nullptr;
        model = nullptr;
        ready = false;
        if (handle) {
            dlclose(handle);
            handle = nullptr;
        }
    }
};

struct FrameSlot {
    std::vector<uint8_t> rgba;
    int width = 0;
    int height = 0;
    int pixelStride = 4;
    int rowStride = 0;
    int64_t timestampNs = 0;
    uint64_t sequence = 0;
};

static JavaVM* g_jvm = nullptr;
static jclass g_touchServiceClass = nullptr;
static jmethodID g_midRegisterHud = nullptr;
static jmethodID g_midTapButton = nullptr;
static jmethodID g_midUpwardDragShot = nullptr;
static jmethodID g_midRecoilDrag = nullptr;
static jmethodID g_midSwipe = nullptr;
static jmethodID g_midMultiTouchCombo = nullptr;

static std::mutex g_stateMutex;
static EngineStateCache g_state;
static TFLiteRuntimeHook g_tflite;

static std::mutex g_frameMutex;
static std::condition_variable g_frameCv;
static FrameSlot g_latestFrame;
static uint64_t g_lastProcessedSeq = 0;

static std::atomic<bool> g_engineActive{false};
static std::thread g_visionThread;
static std::thread g_audioPathThread;
static std::thread g_decisionThread;

static int64_t nowMonotonicNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

static JNIEnv* acquireJniEnv(bool& didAttach) {
    didAttach = false;
    if (!g_jvm) return nullptr;
    JNIEnv* env = nullptr;
    jint status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            didAttach = true;
            return env;
        }
        return nullptr;
    }
    return (status == JNI_OK) ? env : nullptr;
}

static void releaseJniEnv(bool didAttach) {
    if (didAttach && g_jvm) {
        g_jvm->DetachCurrentThread();
    }
}

static void cacheTouchServiceClass(JNIEnv* env, jclass cls) {
    if (!env || !cls) return;
    if (!g_touchServiceClass) {
        g_touchServiceClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
    }
    if (g_touchServiceClass) {
        g_midRegisterHud = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeRegisterHudButton", "(IFFFF)V");
        g_midTapButton = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeTapButton", "(I)Z");
        g_midUpwardDragShot = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeExecuteUpwardDragShot", "(FFFFJ)Z");
        g_midRecoilDrag = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeExecuteRecoilDrag", "(FFFIJ)Z");
        g_midSwipe = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeExecuteSwipe", "(FFFFJ)Z");
        g_midMultiTouchCombo = env->GetStaticMethodID(
            g_touchServiceClass, "onNativeExecuteMultiTouchCombo", "([IFFFFFI)Z");
    }
}

static void jniRegisterHudButton(jint buttonId, float normX, float normY, float radiusNorm, float confidence) {
    if (!g_touchServiceClass || !g_midRegisterHud) return;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return;
    env->CallStaticVoidMethod(
        g_touchServiceClass, g_midRegisterHud, buttonId, normX, normY, radiusNorm, confidence);
    if (env->ExceptionCheck()) env->ExceptionClear();
    releaseJniEnv(attached);
}

static bool jniTapButton(jint buttonId) {
    if (!g_touchServiceClass || !g_midTapButton) return false;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return false;
    jboolean ok = env->CallStaticBooleanMethod(g_touchServiceClass, g_midTapButton, buttonId);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = JNI_FALSE;
    }
    releaseJniEnv(attached);
    return ok == JNI_TRUE;
}

static bool jniUpwardDragShot(float startX, float startY, float deltaY, float biasX, int64_t durationMs) {
    if (!g_touchServiceClass || !g_midUpwardDragShot) return false;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return false;
    jboolean ok = env->CallStaticBooleanMethod(
        g_touchServiceClass, g_midUpwardDragShot, startX, startY, deltaY, biasX,
        static_cast<jlong>(durationMs));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = JNI_FALSE;
    }
    releaseJniEnv(attached);
    return ok == JNI_TRUE;
}

static bool jniRecoilDrag(float startX, float startY, float strength, int weaponClass, int64_t durationMs) {
    if (!g_touchServiceClass || !g_midRecoilDrag) return false;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return false;
    jboolean ok = env->CallStaticBooleanMethod(
        g_touchServiceClass, g_midRecoilDrag, startX, startY, strength,
        static_cast<jint>(weaponClass), static_cast<jlong>(durationMs));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = JNI_FALSE;
    }
    releaseJniEnv(attached);
    return ok == JNI_TRUE;
}

static bool jniExecuteSwipe(float startX, float startY, float endX, float endY, int64_t durationMs) {
    if (!g_touchServiceClass || !g_midSwipe) return false;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return false;
    jboolean ok = env->CallStaticBooleanMethod(
        g_touchServiceClass, g_midSwipe, startX, startY, endX, endY,
        static_cast<jlong>(durationMs));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = JNI_FALSE;
    }
    releaseJniEnv(attached);
    return ok == JNI_TRUE;
}

static bool jniExecuteMultiTouchCombo(
    const std::vector<jint>& buttons,
    float moveDirX,
    float moveDirY,
    float aimDeltaX,
    float aimDeltaY,
    float recoilStrength,
    int weaponClass
) {
    if (!g_touchServiceClass || !g_midMultiTouchCombo) return false;
    bool attached = false;
    JNIEnv* env = acquireJniEnv(attached);
    if (!env) return false;

    jintArray arr = env->NewIntArray(static_cast<jsize>(buttons.size()));
    if (!arr) {
        releaseJniEnv(attached);
        return false;
    }
    if (!buttons.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(buttons.size()), buttons.data());
    }
    jboolean ok = env->CallStaticBooleanMethod(
        g_touchServiceClass, g_midMultiTouchCombo, arr,
        moveDirX, moveDirY, aimDeltaX, aimDeltaY, recoilStrength, static_cast<jint>(weaponClass));
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = JNI_FALSE;
    }
    releaseJniEnv(attached);
    return ok == JNI_TRUE;
}

static PaceProfile parseDirectiveToPace(const std::string& raw) {
    PaceProfile profile;
    std::string lower = raw;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });

    if (lower.find("aggress") != std::string::npos ||
        lower.find("rush") != std::string::npos ||
        lower.find("damage") != std::string::npos) {
        profile.aggressionFactor = 0.95f;
        profile.rushSpeed = 0.95f;
        profile.coverFrequency = 0.45f;
    }
    if (lower.find("cover") != std::string::npos ||
        lower.find("smart") != std::string::npos) {
        profile.coverFrequency = std::max(profile.coverFrequency, 0.72f);
    }
    if (lower.find("defens") != std::string::npos ||
        lower.find("surviv") != std::string::npos ||
        lower.find("safe") != std::string::npos ||
        lower.find("passive") != std::string::npos) {
        profile.aggressionFactor = 0.38f;
        profile.rushSpeed = 0.45f;
        profile.coverFrequency = 0.92f;
    }
    return profile;
}

static float weaponDpsEstimate(int weaponClass, int weaponTier) {
    float base = 115.0f;
    switch (weaponClass) {
        case WEAPON_SMG: base = 148.0f; break;
        case WEAPON_AR: base = 135.0f; break;
        case WEAPON_SHOTGUN: base = 185.0f; break;
        case WEAPON_SNIPER: base = 160.0f; break;
        case WEAPON_LMG: base = 152.0f; break;
        default: break;
    }
    return base * (0.85f + 0.15f * static_cast<float>(weaponTier));
}

static float weaponRecoilGain(int weaponClass) {
    switch (weaponClass) {
        case WEAPON_SMG: return 0.85f;
        case WEAPON_AR: return 1.15f;
        case WEAPON_SHOTGUN: return 0.65f;
        case WEAPON_SNIPER: return 0.75f;
        case WEAPON_LMG: return 1.35f;
        default: return 1.0f;
    }
}

static void analyzeFrameIntoCache(const FrameSlot& frame, int64_t nowNs) {
    if (frame.rgba.empty() || frame.width <= 0 || frame.height <= 0) return;

    if (g_tflite.ready && g_tflite.interpreterInvoke) {
        g_tflite.interpreterInvoke(g_tflite.interpreter);
    }

    const int stride = 12;
    const int w = frame.width;
    const int h = frame.height;
    const int rowBytes = frame.rowStride > 0 ? frame.rowStride : (w * 4);

    int hpActiveSamples = 0;
    int hpTotalSamples = 0;
    const int hpY = static_cast<int>(h * 0.91f);
    for (int x = static_cast<int>(w * 0.36f); x < static_cast<int>(w * 0.64f); x += 6) {
        const size_t idx = static_cast<size_t>(hpY) * rowBytes + static_cast<size_t>(x) * frame.pixelStride;
        if (idx + 2 < frame.rgba.size()) {
            uint8_t r = frame.rgba[idx];
            uint8_t g = frame.rgba[idx + 1];
            uint8_t b = frame.rgba[idx + 2];
            ++hpTotalSamples;
            if ((r > 180 && g > 180 && b > 180) || (g > 150 && r < 140)) {
                ++hpActiveSamples;
            }
        }
    }

    std::vector<EnemyTarget> detectedEnemies;
    int redMarkerCount = 0;
    float accumX = 0.0f;
    float accumY = 0.0f;
    const int yMin = static_cast<int>(h * 0.18f);
    const int yMax = static_cast<int>(h * 0.72f);
    const int xMin = static_cast<int>(w * 0.12f);
    const int xMax = static_cast<int>(w * 0.88f);

    for (int y = yMin; y < yMax; y += stride) {
        for (int x = xMin; x < xMax; x += stride) {
            const size_t idx = static_cast<size_t>(y) * rowBytes + static_cast<size_t>(x) * frame.pixelStride;
            if (idx + 2 >= frame.rgba.size()) continue;
            uint8_t r = frame.rgba[idx];
            uint8_t g = frame.rgba[idx + 1];
            uint8_t b = frame.rgba[idx + 2];
            if (r > 205 && g < 72 && b < 72) {
                ++redMarkerCount;
                accumX += static_cast<float>(x) / static_cast<float>(w);
                accumY += static_cast<float>(y) / static_cast<float>(h);
            }
        }
    }

    if (redMarkerCount >= 4 && redMarkerCount <= 900) {
        EnemyTarget target;
        target.id = 1;
        target.normX = std::clamp(accumX / static_cast<float>(redMarkerCount), 0.08f, 0.92f);
        target.normY = std::clamp(accumY / static_cast<float>(redMarkerCount), 0.12f, 0.82f);
        target.headX = target.normX;
        target.headY = std::clamp(target.normY - 0.045f, 0.06f, 0.80f);
        target.width = 0.045f;
        target.height = 0.11f;
        target.distanceEst = std::clamp(32.0f / std::max(1.0f, std::sqrt(static_cast<float>(redMarkerCount))), 3.5f, 65.0f);
        target.azimuthRad = (target.normX - 0.5f) * 1.35f;
        target.isFiring = (redMarkerCount > 24);
        target.lastSeenNs = nowNs;
        detectedEnemies.push_back(target);
    }

    int blueZonePixels = 0;
    for (int y = static_cast<int>(h * 0.04f); y < static_cast<int>(h * 0.24f); y += 8) {
        for (int x = static_cast<int>(w * 0.02f); x < static_cast<int>(w * 0.18f); x += 8) {
            const size_t idx = static_cast<size_t>(y) * rowBytes + static_cast<size_t>(x) * frame.pixelStride;
            if (idx + 2 < frame.rgba.size()) {
                uint8_t r = frame.rgba[idx];
                uint8_t g = frame.rgba[idx + 1];
                uint8_t b = frame.rgba[idx + 2];
                if (b > 185 && r < 110 && g > 95) {
                    ++blueZonePixels;
                }
            }
        }
    }

    if ((frame.sequence % 45ULL) == 1ULL) {
        jniRegisterHudButton(HUD_FIRE, 0.84f, 0.72f, 0.055f, 0.70f);
        jniRegisterHudButton(HUD_SCOPE, 0.92f, 0.49f, 0.038f, 0.70f);
        jniRegisterHudButton(HUD_GLOO_WALL, 0.73f, 0.76f, 0.040f, 0.70f);
    }

    std::lock_guard<std::mutex> lock(g_stateMutex);
    g_state.frameWidth = w;
    g_state.frameHeight = h;

    if (hpTotalSamples > 0) {
        float measuredHp = (static_cast<float>(hpActiveSamples) / static_cast<float>(hpTotalSamples)) * 100.0f;
        if (measuredHp > 5.0f) {
            g_state.hp = 0.75f * g_state.hp + 0.25f * std::clamp(measuredHp, 5.0f, 100.0f);
        }
    }

    g_state.inZoneGap = (blueZonePixels > 14);
    g_state.underFire = (!detectedEnemies.empty() && detectedEnemies[0].isFiring) ||
                        (redMarkerCount > 45);

    if (!detectedEnemies.empty()) {
        for (auto& e : detectedEnemies) {
            auto& track = g_state.tracks[e.id];
            track.update(e.headX, e.headY, e.distanceEst, nowNs);
        }
        g_state.enemies = std::move(detectedEnemies);
    } else {
        g_state.enemies.erase(
            std::remove_if(
                g_state.enemies.begin(),
                g_state.enemies.end(),
                [nowNs](const EnemyTarget& e) { return (nowNs - e.lastSeenNs) > 450000000LL; }),
            g_state.enemies.end());
    }
}

static void visionWorkerLoop() {
    FrameSlot localFrame;
    while (g_engineActive.load(std::memory_order_acquire)) {
        {
            std::unique_lock<std::mutex> lock(g_frameMutex);
            g_frameCv.wait_for(lock, std::chrono::milliseconds(16), [] {
                return !g_engineActive.load(std::memory_order_acquire) ||
                       g_latestFrame.sequence > g_lastProcessedSeq;
            });
            if (!g_engineActive.load(std::memory_order_acquire)) break;
            if (g_latestFrame.sequence <= g_lastProcessedSeq) continue;
            localFrame = g_latestFrame;
            g_lastProcessedSeq = g_latestFrame.sequence;
        }
        analyzeFrameIntoCache(localFrame, nowMonotonicNs());
    }
}

static void audioPathWorkerLoop() {
    while (g_engineActive.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            int64_t nowNs = nowMonotonicNs();
            g_state.audioCues.erase(
                std::remove_if(
                    g_state.audioCues.begin(),
                    g_state.audioCues.end(),
                    [nowNs](const AudioCue3D& c) { return (nowNs - c.timestampNs) > 1500000000LL; }),
                g_state.audioCues.end());

            g_state.roamHeadingRad = std::fmod(g_state.roamHeadingRad + 0.045f, 6.283185f);
            g_state.zigZagPhase = std::fmod(g_state.zigZagPhase + 0.35f, 6.283185f);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(12));
    }
}

static void evaluateAll30TacticalRules() {
    EngineStateCache snap;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        snap = g_state;
    }

    const int64_t nowNs = nowMonotonicNs();
    const float aggr = snap.pace.aggressionFactor;   // Rule 30
    const float coverFreq = snap.pace.coverFrequency; // Rule 30
    const float rushSpd = snap.pace.rushSpeed;        // Rule 30

    // Rule 28: Smart Inventory Cleanup when backpack is full
    if (snap.backpackUsage > 0.90f && (nowNs - snap.lastCleanupNs) > 4000000000LL) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_state.backpackUsage = 0.68f;
            g_state.lastCleanupNs = nowNs;
        }
        jniTapButton(HUD_RELOAD);
        return;
    }

    // Rule 1: Instant Hot-Drop upon plane arrival
    if (snap.inPlane && (nowNs - snap.lastHotDropNs) > 1200000000LL) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_state.lastHotDropNs = nowNs;
        }
        jniTapButton(HUD_JUMP);
        jniExecuteSwipe(0.55f, 0.45f, 0.55f, 0.78f, 160);
        return;
    }

    // Rule 22: Smart Health & Inhaler Management
    if (snap.hp < 82.0f && snap.inhalerCount > 0 && (nowNs - snap.lastHealNs) > 1600000000LL) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_state.inhalerCount = std::max(0, g_state.inhalerCount - 1);
            g_state.lastHealNs = nowNs;
        }
        jniExecuteMultiTouchCombo(
            {HUD_INHALER, HUD_SPRINT}, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, snap.weaponClass);
        return;
    }
    if (snap.hp < 48.0f && snap.medkitCount > 0 && (nowNs - snap.lastHealNs) > 3000000000LL) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_state.medkitCount = std::max(0, g_state.medkitCount - 1);
            g_state.lastHealNs = nowNs;
        }
        if (snap.glooWallCount > 0 && coverFreq > 0.45f) {
            jniTapButton(HUD_GLOO_WALL);
        }
        jniExecuteMultiTouchCombo({HUD_CROUCH, HUD_MEDKIT}, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, snap.weaponClass);
        return;
    }

    // Rule 19: Instant Secondary Switch when primary clip empties
    if (snap.isArmed && snap.primaryAmmo <= 0 && snap.secondaryAmmo > 0) {
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            std::swap(g_state.primaryAmmo, g_state.secondaryAmmo);
            g_state.weaponClass = WEAPON_SMG;
        }
        jniTapButton(HUD_SECONDARY_WEAPON);
        return;
    }

    // Rule 20: Tactical Escape & Re-Arm when both guns run out of ammo
    if (snap.isArmed && snap.primaryAmmo <= 0 && snap.secondaryAmmo <= 0) {
        if (snap.glooWallCount > 0) {
            jniTapButton(HUD_GLOO_WALL);
        }
        float evadeX = std::sin(snap.zigZagPhase) * 0.92f;
        jniExecuteMultiTouchCombo(
            {HUD_SPRINT}, evadeX, -0.85f, evadeX * 0.08f, 0.0f, 0.0f, snap.weaponClass);
        return;
    }

    // Rule 6 & Rule 29: Vehicle Combat & Vehicle Exit Flank
    if (snap.inVehicle) {
        if (!snap.enemies.empty()) {
            const auto& target = snap.enemies.front();
            if (std::abs(target.normX - 0.5f) < 0.18f && target.distanceEst < 22.0f) {
                jniExecuteMultiTouchCombo(
                    {HUD_SPRINT}, (target.normX - 0.5f) * 2.0f, -1.0f, 0.0f, 0.0f, 0.0f, snap.weaponClass);
            } else {
                {
                    std::lock_guard<std::mutex> lock(g_stateMutex);
                    g_state.inVehicle = false;
                    g_state.justExitedVehicle = true;
                    g_state.hasNearbyCover = true;
                    g_state.vehicleExitNs = nowNs;
                }
                jniTapButton(HUD_SECONDARY_WEAPON);
            }
            return;
        }
    }
    if (snap.justExitedVehicle && (nowNs - snap.vehicleExitNs) < 2500000000LL && !snap.enemies.empty()) {
        const auto& e = snap.enemies.front();
        jniExecuteMultiTouchCombo(
            {HUD_CROUCH, HUD_FIRE}, -0.35f, 0.0f, e.headX - 0.5f, -0.14f,
            weaponRecoilGain(snap.weaponClass), snap.weaponClass);
        return;
    }

    if (!snap.enemies.empty()) {
        const EnemyTarget* primaryTarget = &snap.enemies.front();
        int fightingCount = 0;
        for (const auto& e : snap.enemies) {
            if (e.isFightingAnother) ++fightingCount;
            if (e.distanceEst < primaryTarget->distanceEst) primaryTarget = &e;
        }

        // Rule 16 & Rule 17: 1v4 Threat Isolation vs Heavy Firepower Squad Wipe
        if (snap.enemies.size() >= 3) {
            if (snap.weaponTier >= 3 && snap.hp >= 70.0f && aggr >= 0.65f) {
                jniExecuteMultiTouchCombo(
                    {HUD_SPRINT, HUD_FIRE},
                    (primaryTarget->normX - 0.5f) * 1.4f,
                    -0.9f,
                    primaryTarget->headX - 0.5f,
                    -0.16f,
                    weaponRecoilGain(snap.weaponClass),
                    snap.weaponClass);
                return;
            } else {
                int leftCount = 0;
                int rightCount = 0;
                const EnemyTarget* isolated = nullptr;
                for (const auto& e : snap.enemies) {
                    if (e.normX < 0.5f) ++leftCount; else ++rightCount;
                }
                for (const auto& e : snap.enemies) {
                    if ((leftCount >= 3 && e.normX >= 0.5f) || (rightCount >= 3 && e.normX < 0.5f)) {
                        isolated = &e;
                        break;
                    }
                }
                if (isolated != nullptr) {
                    if (snap.glooWallCount > 0) {
                        jniTapButton(HUD_GLOO_WALL);
                    }
                    primaryTarget = isolated;
                }
            }
        }

        // Rule 21: Unarmed Melee Rush & Corner Ambush
        if (!snap.isArmed) {
            if (primaryTarget->distanceEst < 8.5f) {
                jniExecuteMultiTouchCombo(
                    {HUD_SPRINT, HUD_FIRE},
                    (primaryTarget->normX - 0.5f) * 1.5f,
                    -1.0f,
                    primaryTarget->headX - 0.5f,
                    -0.08f,
                    0.0f,
                    WEAPON_SMG);
            } else {
                if (snap.ambushStartNs == 0) {
                    std::lock_guard<std::mutex> lock(g_stateMutex);
                    g_state.ambushStartNs = nowNs;
                } else if ((nowNs - snap.ambushStartNs) > 4500000000LL) {
                    std::lock_guard<std::mutex> lock(g_stateMutex);
                    g_state.ambushStartNs = 0;
                    jniExecuteMultiTouchCombo({HUD_SPRINT}, -0.8f, -0.7f, 0.0f, 0.0f, 0.0f, WEAPON_SMG);
                }
            }
            return;
        }

        Vec3 predicted{primaryTarget->headX, primaryTarget->headY, primaryTarget->distanceEst};
        float lateralVel = 0.0f;
        auto trackIt = snap.tracks.find(primaryTarget->id);
        if (trackIt != snap.tracks.end() && trackIt->second.initialized) {
            predicted = trackIt->second.predictAhead(0.20f);
            lateralVel = trackIt->second.x[3];
        }

        // Rule 18: Zone-Edge Combat
        for (const auto& e : snap.enemies) {
            if (e.inZoneGap) {
                primaryTarget = &e;
                break;
            }
        }

        // Rule 13 & Rule 14: Cooked Grenade Timing (2-3s) & Fake Footsteps / Flank Bait
        if (snap.isCookingGrenade) {
            int64_t cookElapsedMs = (nowNs - snap.grenadeCookStartNs) / 1000000LL;
            if (cookElapsedMs >= 2350LL) {
                {
                    std::lock_guard<std::mutex> lock(g_stateMutex);
                    g_state.isCookingGrenade = false;
                    g_state.lastCookedGrenadeNs = nowNs;
                }
                jniExecuteSwipe(0.76f, 0.68f, predicted.x, predicted.y - 0.06f, 140);
                // Rule 14: Follow cooked grenade with opposite-side flank sprint
                float flankDir = (primaryTarget->normX < 0.5f) ? 0.9f : -0.9f;
                jniExecuteMultiTouchCombo(
                    {HUD_SPRINT, HUD_JUMP}, flankDir, -0.8f,
                    predicted.x - 0.5f, predicted.y - 0.5f, 0.0f, snap.weaponClass);
            }
            return;
        }

        if (primaryTarget->isBehindCover && primaryTarget->distanceEst < 26.0f &&
            snap.grenadeCount > 0 && (nowNs - snap.lastCookedGrenadeNs) > 6000000000LL) {
            {
                std::lock_guard<std::mutex> lock(g_stateMutex);
                g_state.grenadeCount = std::max(0, g_state.grenadeCount - 1);
                g_state.isCookingGrenade = true;
                g_state.grenadeCookStartNs = nowNs;
            }
            // Rule 14 decoy step while cooking
            jniExecuteMultiTouchCombo(
                {HUD_SPRINT}, (primaryTarget->normX < 0.5f ? -0.7f : 0.7f), -0.3f,
                0.0f, 0.0f, 0.0f, snap.weaponClass);
            return;
        }

        // Rule 12: Enemy Reload Trap
        if (primaryTarget->isReloading) {
            jniExecuteMultiTouchCombo(
                {HUD_SPRINT, HUD_JUMP, HUD_FIRE},
                (predicted.x - 0.5f) * 1.4f,
                -1.0f,
                predicted.x - 0.5f,
                -0.16f,
                weaponRecoilGain(snap.weaponClass),
                snap.weaponClass);
            return;
        }

        // Rule 24: Drop-Shot Surprise
        if (primaryTarget->distanceEst < 7.5f && !snap.isProne && primaryTarget->isFiring) {
            {
                std::lock_guard<std::mutex> lock(g_stateMutex);
                g_state.isProne = true;
            }
            jniExecuteMultiTouchCombo(
                {HUD_PRONE, HUD_FIRE},
                0.0f,
                0.0f,
                predicted.x - 0.5f,
                -0.12f,
                weaponRecoilGain(snap.weaponClass) * 0.7f,
                snap.weaponClass);
            return;
        }

        // Rule 8 & Rule 9: Auto-Distance Scope Toggle & Close-Range Quick-Scope Shot
        float myDps = weaponDpsEstimate(snap.weaponClass, snap.weaponTier);
        bool needQuickScope = (primaryTarget->distanceEst < 14.0f && primaryTarget->estimatedDps > myDps);
        bool needLongScope = (primaryTarget->distanceEst > 28.0f);

        if ((needQuickScope || needLongScope) && !snap.isScoped) {
            {
                std::lock_guard<std::mutex> lock(g_stateMutex);
                g_state.isScoped = true;
            }
            jniExecuteMultiTouchCombo(
                {HUD_SCOPE, HUD_FIRE},
                (snap.zigZagPhase > 3.14f ? 0.6f : -0.6f),
                -0.25f,
                predicted.x - 0.5f,
                -0.17f,
                weaponRecoilGain(snap.weaponClass),
                snap.weaponClass);
            return;
        } else if (primaryTarget->distanceEst < 9.0f && !needQuickScope && snap.isScoped) {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            g_state.isScoped = false;
            jniTapButton(HUD_SCOPE);
        }

        // Rule 11 & Rule 25: Corner Pre-Aiming & Auto-Cover Peek & Fire
        if (snap.hasNearbyCover || primaryTarget->isBehindCover) {
            float peekEdgeX = std::clamp(predicted.x + lateralVel * 0.15f, 0.12f, 0.88f);
            int64_t cycleMs = ((nowNs - snap.peekPhaseStartNs) / 1000000LL) % 1800LL;
            bool peekOut = (cycleMs < 1000LL);
            float strafeX = peekOut ? 0.85f : -0.85f;
            if (peekOut) {
                jniUpwardDragShot(0.84f, 0.72f, 0.17f, peekEdgeX - 0.5f, 82);
            } else {
                jniExecuteMultiTouchCombo(
                    {HUD_CROUCH}, strafeX, 0.0f, peekEdgeX - 0.5f, predicted.y - 0.5f,
                    0.0f, snap.weaponClass);
            }
            return;
        }

        // Rule 7, Rule 10, Rule 15 & Rule 23: Headshot Drag, Multi-Touch Combo, Third-Party Snap, Zig-Zag
        float moveX = snap.underFire
            ? (std::sin(snap.zigZagPhase) * 0.95f)
            : ((predicted.x < 0.5f ? -0.55f : 0.55f) * rushSpd);

        std::vector<jint> combatButtons = {HUD_FIRE};
        if (aggr > 0.65f && primaryTarget->distanceEst < 22.0f) {
            combatButtons.push_back(HUD_JUMP);
        } else if (coverFreq > 0.70f) {
            combatButtons.push_back(HUD_CROUCH);
        }

        float curveBias = std::clamp(predicted.x - 0.5f, -0.12f, 0.12f);
        jniUpwardDragShot(0.84f, 0.72f, 0.19f, curveBias, 78);
        jniRecoilDrag(0.72f, 0.48f, weaponRecoilGain(snap.weaponClass), snap.weaponClass, 125);
        jniExecuteMultiTouchCombo(
            combatButtons, moveX, -0.75f * rushSpd, curveBias, -0.12f,
            weaponRecoilGain(snap.weaponClass), snap.weaponClass);

        if (fightingCount >= 2 && snap.enemies.size() >= 2) {
            const auto& second = snap.enemies[1];
            jniExecuteSwipe(0.72f, 0.48f, second.headX, second.headY, 68);
        }
        return;
    }

    // Rule 3, Rule 26 & Rule 27: Auto-Parkour, High-Ground Dominance, Door & Window Breaching
    for (const auto& obs : snap.obstacles) {
        if (obs.distanceEst < 4.5f) {
            if (obs.isLowVaultable || obs.isWindow) {
                jniExecuteMultiTouchCombo(
                    {HUD_JUMP, HUD_SPRINT}, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, snap.weaponClass);
                return;
            }
            if (obs.isDoor) {
                jniTapButton(HUD_JUMP);
                return;
            }
            if (obs.isTallBuilding) {
                float detourX = (obs.normX >= 0.5f) ? -0.95f : 0.95f;
                jniExecuteMultiTouchCombo(
                    {HUD_SPRINT}, detourX, -0.7f, detourX * 0.06f, 0.0f, 0.0f, snap.weaponClass);
                return;
            }
        }
        if (obs.isElevated && obs.distanceEst < 24.0f) {
            jniExecuteMultiTouchCombo(
                {HUD_SPRINT, HUD_JUMP}, (obs.normX - 0.5f) * 1.4f, -1.0f,
                (obs.normX - 0.5f) * 0.08f, -0.04f, 0.0f, snap.weaponClass);
            return;
        }
    }

    // Rule 5: Smart Looting & Loot-Box Upgrades
    for (const auto& loot : snap.lootItems) {
        bool shouldLoot = (loot.isMedkit && snap.medkitCount < 3) ||
                          (loot.isAmmo && snap.primaryAmmo < snap.primaryMaxAmmo) ||
                          (loot.isWeapon && (!snap.isArmed || loot.tier > snap.weaponTier));
        if (shouldLoot && loot.distanceEst < 18.0f) {
            jniExecuteMultiTouchCombo(
                {HUD_SPRINT}, (loot.normX - 0.5f) * 1.6f, -0.9f, 0.0f, 0.0f, 0.0f, snap.weaponClass);
            if (loot.distanceEst < 2.5f) {
                jniTapButton(HUD_PRIMARY_WEAPON);
            }
            return;
        }
    }

    // Rule 4: 3D Sound Radar
    if (!snap.audioCues.empty()) {
        const auto& cue = snap.audioCues.front();
        float aimTurnX = std::clamp(cue.azimuthRad * 0.18f, -0.25f, 0.25f);
        jniExecuteMultiTouchCombo(
            {HUD_SPRINT}, std::sin(cue.azimuthRad), -std::cos(cue.azimuthRad),
            aimTurnX, 0.0f, 0.0f, snap.weaponClass);
        return;
    }

    // Rule 2: 360° Dynamic Map Roaming
    float roamX = std::sin(snap.roamHeadingRad) * 0.45f;
    float sweepLookX = std::cos(snap.roamHeadingRad * 2.0f) * 0.08f;
    jniExecuteMultiTouchCombo(
        {HUD_SPRINT}, roamX, -0.95f * rushSpd, sweepLookX, 0.0f, 0.0f, snap.weaponClass);
}

static void decisionWorkerLoop() {
    while (g_engineActive.load(std::memory_order_acquire)) {
        evaluateAll30TacticalRules();
        std::this_thread::sleep_for(std::chrono::milliseconds(42));
    }
}

static void startWorkerThreads() {
    if (g_engineActive.exchange(true)) return;
    g_visionThread = std::thread(visionWorkerLoop);
    g_audioPathThread = std::thread(audioPathWorkerLoop);
    g_decisionThread = std::thread(decisionWorkerLoop);
}

static void stopWorkerThreads() {
    if (!g_engineActive.exchange(false)) return;
    g_frameCv.notify_all();
    if (g_visionThread.joinable()) g_visionThread.join();
    if (g_audioPathThread.joinable()) g_audioPathThread.join();
    if (g_decisionThread.joinable()) g_decisionThread.join();
}

} // namespace autopilot

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    autopilot::g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_autopilot_pro_1gamer_AutoTouchAccessibilityService_nativeAttachTouchBridge(
    JNIEnv* env,
    jobject thiz
) {
    jclass cls = env->GetObjectClass(thiz);
    autopilot::cacheTouchServiceClass(env, cls);
    if (cls) env->DeleteLocalRef(cls);
}

JNIEXPORT void JNICALL
Java_com_autopilot_pro_1gamer_AutoTouchAccessibilityService_nativeDetachTouchBridge(
    JNIEnv* env,
    jobject thiz
) {
    if (autopilot::g_touchServiceClass) {
        env->DeleteGlobalRef(autopilot::g_touchServiceClass);
        autopilot::g_touchServiceClass = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_autopilot_pro_1gamer_ScreenCaptureService_nativeInitEngine(
    JNIEnv* env,
    jobject thiz,
    jint width,
    jint height,
    jstring targetPackage,
    jstring directive
) {
    jclass touchCls = env->FindClass("com/autopilot/pro_gamer/AutoTouchAccessibilityService");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (touchCls) {
        autopilot::cacheTouchServiceClass(env, touchCls);
        env->DeleteLocalRef(touchCls);
    }

    const char* pkgChars = targetPackage ? env->GetStringUTFChars(targetPackage, nullptr) : "";
    const char* dirChars = directive ? env->GetStringUTFChars(directive, nullptr) : "";

    {
        std::lock_guard<std::mutex> lock(autopilot::g_stateMutex);
        autopilot::g_state = autopilot::EngineStateCache{};
        autopilot::g_state.frameWidth = std::max(1, static_cast<int>(width));
        autopilot::g_state.frameHeight = std::max(1, static_cast<int>(height));
        autopilot::g_state.targetPackage = pkgChars ? pkgChars : "";
        autopilot::g_state.directive = dirChars ? dirChars : "";
        autopilot::g_state.pace = autopilot::parseDirectiveToPace(autopilot::g_state.directive);
        autopilot::g_state.peekPhaseStartNs = autopilot::nowMonotonicNs();
    }

    if (targetPackage && pkgChars) env->ReleaseStringUTFChars(targetPackage, pkgChars);
    if (directive && dirChars) env->ReleaseStringUTFChars(directive, dirChars);

    autopilot::g_tflite.init("/data/local/tmp/autopilot_hud_enemy.tflite");
    autopilot::startWorkerThreads();
    LOGI("Autopilot native engine initialized (%dx%d)", width, height);
}

JNIEXPORT void JNICALL
Java_com_autopilot_pro_1gamer_ScreenCaptureService_nativeProcessFrame(
    JNIEnv* env,
    jobject thiz,
    jobject buffer,
    jint width,
    jint height,
    jint pixelStride,
    jint rowStride,
    jlong timestampNs
) {
    if (!autopilot::g_engineActive.load(std::memory_order_acquire) || !buffer) return;

    auto* rawPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!rawPtr || capacity <= 0) return;

    const size_t copyBytes = static_cast<size_t>(
        std::min<jlong>(capacity, static_cast<jlong>(rowStride) * static_cast<jlong>(height)));

    {
        std::lock_guard<std::mutex> lock(autopilot::g_frameMutex);
        autopilot::g_latestFrame.rgba.resize(copyBytes);
        std::memcpy(autopilot::g_latestFrame.rgba.data(), rawPtr, copyBytes);
        autopilot::g_latestFrame.width = static_cast<int>(width);
        autopilot::g_latestFrame.height = static_cast<int>(height);
        autopilot::g_latestFrame.pixelStride = std::max(1, static_cast<int>(pixelStride));
        autopilot::g_latestFrame.rowStride = static_cast<int>(rowStride);
        autopilot::g_latestFrame.timestampNs = static_cast<int64_t>(timestampNs);
        autopilot::g_latestFrame.sequence++;
    }
    autopilot::g_frameCv.notify_one();
}

JNIEXPORT void JNICALL
Java_com_autopilot_pro_1gamer_ScreenCaptureService_nativeStopEngine(
    JNIEnv* env,
    jobject thiz
) {
    autopilot::stopWorkerThreads();
    autopilot::g_tflite.shutdown();
    LOGI("Autopilot native engine stopped");
}

} // extern "C"
