// Copyright 2014 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <iomanip>
#include <memory>
#include <sstream>
#include <unordered_map>
#include <INIReader.h>
#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/param_package.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/hle/service/cfg/cfg.h"
#include "core/hle/service/service.h"
#include "input_common/main.h"
#include "input_common/udp/client.h"
#include "jni/camera/ndk_camera.h"
#include "jni/config.h"
#include "jni/default_ini.h"
#include "jni/input_manager.h"
#include "network/network_settings.h"
#include "jni/id_cache.h"

Config::Config() {
    Reload();
}

Config::~Config() = default;

static const std::array<int, Settings::NativeButton::NumButtons> default_buttons = {
    InputManager::N3DS_BUTTON_A,     InputManager::N3DS_BUTTON_B,
    InputManager::N3DS_BUTTON_X,     InputManager::N3DS_BUTTON_Y,
    InputManager::N3DS_DPAD_UP,      InputManager::N3DS_DPAD_DOWN,
    InputManager::N3DS_DPAD_LEFT,    InputManager::N3DS_DPAD_RIGHT,
    InputManager::N3DS_TRIGGER_L,    InputManager::N3DS_TRIGGER_R,
    InputManager::N3DS_BUTTON_START, InputManager::N3DS_BUTTON_SELECT,
    InputManager::N3DS_BUTTON_DEBUG, InputManager::N3DS_BUTTON_GPIO14,
    InputManager::N3DS_BUTTON_ZL,    InputManager::N3DS_BUTTON_ZR,
    InputManager::N3DS_BUTTON_HOME,
};

static const std::array<int, Settings::NativeAnalog::NumAnalogs> default_analogs{{
    InputManager::N3DS_CIRCLEPAD,
    InputManager::N3DS_STICK_C,
}};

template <>
void Config::ReadSetting(const std::string& group, Settings::Setting<std::string>& setting) {
    std::string setting_value = GetStringSetting(setting.GetLabel(), setting.GetDefault());
    setting = std::move(setting_value);
}

template <>
void Config::ReadSetting(const std::string& group, Settings::Setting<bool>& setting) {
    setting = GetBooleanSetting(setting.GetLabel(), setting.GetDefault());
}

template <typename Type, bool ranged>
void Config::ReadSetting(const std::string& group, Settings::Setting<Type, ranged>& setting) {
    if constexpr (std::is_floating_point_v<Type>) {
        bool isScaled = setting.GetLabel() == "volume"; // TODO: implement proper logic later if added more settings in ScaledFloat format
        setting = GetFloatSetting(setting.GetLabel(), isScaled, setting.GetDefault());
    } else {
        setting = static_cast<Type>(GetIntegerSetting(setting.GetLabel(), static_cast<int>(setting.GetDefault())));
    }
}

void Config::ReadValues() {
    // Controls
    for (int i = 0; i < Settings::NativeButton::NumButtons; ++i) {
        std::string default_param = InputManager::GenerateButtonParamPackage(default_buttons[i]);
        Settings::values.current_input_profile.buttons[i] = default_param;
        if (Settings::values.current_input_profile.buttons[i].empty())
            Settings::values.current_input_profile.buttons[i] = default_param;
    }

    for (int i = 0; i < Settings::NativeAnalog::NumAnalogs; ++i) {
        std::string default_param = InputManager::GenerateAnalogParamPackage(default_analogs[i]);
        Settings::values.current_input_profile.analogs[i] = default_param;
        if (Settings::values.current_input_profile.analogs[i].empty())
            Settings::values.current_input_profile.analogs[i] = default_param;
    }

    Settings::values.current_input_profile.motion_device = "engine:motion_emu,update_period:100,sensitivity:0.01,tilt_clamp:90.0";
    Settings::values.current_input_profile.touch_device = "engine:emu_window";
    Settings::values.current_input_profile.udp_input_address = InputCommon::CemuhookUDP::DEFAULT_ADDR;
    Settings::values.current_input_profile.udp_input_port = InputCommon::CemuhookUDP::DEFAULT_PORT;

    ReadSetting("Controls", Settings::values.use_artic_base_controller);

    // Core
    ReadSetting("Core", Settings::values.use_cpu_jit);
    ReadSetting("Core", Settings::values.cpu_clock_percentage);

    // Renderer
    Settings::values.use_gles = GetBooleanSetting("use_gles", true);
    Settings::values.shaders_accurate_mul = GetBooleanSetting("shaders_accurate_mul", false);
    ReadSetting("Renderer", Settings::values.graphics_api);
    ReadSetting("Renderer", Settings::values.async_presentation);
    ReadSetting("Renderer", Settings::values.async_shader_compilation);
    ReadSetting("Renderer", Settings::values.spirv_shader_gen);
    ReadSetting("Renderer", Settings::values.use_hw_shader);
    ReadSetting("Renderer", Settings::values.use_shader_jit);
    ReadSetting("Renderer", Settings::values.resolution_factor);
    ReadSetting("Renderer", Settings::values.use_disk_shader_cache);
    ReadSetting("Renderer", Settings::values.use_vsync_new);
    ReadSetting("Renderer", Settings::values.texture_filter);
    ReadSetting("Renderer", Settings::values.texture_sampling);

    // Work around to map Android setting for enabling the frame limiter to the format Citra expects
    if (GetBooleanSetting("use_frame_limit", true)) {
        ReadSetting("Renderer", Settings::values.frame_limit);
    } else {
        Settings::values.frame_limit = 0;
    }

    ReadSetting("Renderer", Settings::values.render_3d);
    ReadSetting("Renderer", Settings::values.factor_3d);
    std::string default_shader = "none (builtin)";
    if (Settings::values.render_3d.GetValue() == Settings::StereoRenderOption::Anaglyph)
        default_shader = "dubois (builtin)";
    else if (Settings::values.render_3d.GetValue() == Settings::StereoRenderOption::Interlaced)
        default_shader = "horizontal (builtin)";
    Settings::values.pp_shader_name = default_shader;
    ReadSetting("Renderer", Settings::values.filter_mode);

    ReadSetting("Renderer", Settings::values.bg_red);
    ReadSetting("Renderer", Settings::values.bg_green);
    ReadSetting("Renderer", Settings::values.bg_blue);
    ReadSetting("Renderer", Settings::values.delay_game_render_thread_us);
    ReadSetting("Renderer", Settings::values.disable_right_eye_render);

    // Layout
    // Somewhat inelegant solution to ensure layout value is between 0 and 5 on read
    // since older config files may have other values
    int layoutInt = GetIntegerSetting("layout_option");
    if (layoutInt < 0 || layoutInt > 5) {
        layoutInt = static_cast<int>(Settings::LayoutOption::LargeScreen);
    }
    Settings::values.layout_option = static_cast<Settings::LayoutOption>(layoutInt);
    Settings::values.large_screen_proportion = 2.25;
    Settings::values.small_screen_position = static_cast<Settings::SmallScreenPosition>(
            GetIntegerSetting("small_screen_position",
                                static_cast<int>(Settings::SmallScreenPosition::TopRight)));
    ReadSetting("Layout", Settings::values.custom_top_x);
    ReadSetting("Layout", Settings::values.custom_top_y);
    ReadSetting("Layout", Settings::values.custom_top_width);
    ReadSetting("Layout", Settings::values.custom_top_height);
    ReadSetting("Layout", Settings::values.custom_bottom_x);
    ReadSetting("Layout", Settings::values.custom_bottom_y);
    ReadSetting("Layout", Settings::values.custom_bottom_width);
    ReadSetting("Layout", Settings::values.custom_bottom_height);
    ReadSetting("Layout", Settings::values.cardboard_screen_size);
    ReadSetting("Layout", Settings::values.cardboard_x_shift);
    ReadSetting("Layout", Settings::values.cardboard_y_shift);

    Settings::values.portrait_layout_option =
            static_cast<Settings::PortraitLayoutOption>(GetIntegerSetting("portrait_layout_option", static_cast<int>(Settings::PortraitLayoutOption::PortraitTopFullWidth)));
    ReadSetting("Layout", Settings::values.custom_portrait_top_x);
    ReadSetting("Layout", Settings::values.custom_portrait_top_y);
    ReadSetting("Layout", Settings::values.custom_portrait_top_width);
    ReadSetting("Layout", Settings::values.custom_portrait_top_height);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_x);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_y);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_width);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_height);

    // Utility
    ReadSetting("Utility", Settings::values.dump_textures);
    ReadSetting("Utility", Settings::values.custom_textures);
    ReadSetting("Utility", Settings::values.preload_textures);
    ReadSetting("Utility", Settings::values.async_custom_loading);

    // Audio
    ReadSetting("Audio", Settings::values.audio_emulation);
    ReadSetting("Audio", Settings::values.enable_audio_stretching);
    ReadSetting("Audio", Settings::values.enable_realtime_audio);
    ReadSetting("Audio", Settings::values.volume);
    ReadSetting("Audio", Settings::values.output_type);
    ReadSetting("Audio", Settings::values.output_device);
    ReadSetting("Audio", Settings::values.input_type);
    ReadSetting("Audio", Settings::values.input_device);

    // Data Storage
    ReadSetting("Data Storage", Settings::values.use_virtual_sd);

    // System
    ReadSetting("System", Settings::values.is_new_3ds);
    ReadSetting("System", Settings::values.lle_applets);
    ReadSetting("System", Settings::values.region_value);
    ReadSetting("System", Settings::values.init_clock);
    {
        std::string time = GetStringSetting("init_time", "946681277");
        try {
            Settings::values.init_time = std::stoll(time);
        } catch (...) {
        }
    }
    ReadSetting("System", Settings::values.init_ticks_type);
    ReadSetting("System", Settings::values.init_ticks_override);
    ReadSetting("System", Settings::values.plugin_loader_enabled);
    ReadSetting("System", Settings::values.allow_plugin_loader);
    ReadSetting("System", Settings::values.steps_per_hour);

    // Camera
    using namespace Service::CAM;
    Settings::values.camera_name[OuterRightCamera] =
            GetStringSetting("camera_outer_right_name", "ndk");
    Settings::values.camera_config[OuterRightCamera] =
            GetStringSetting("camera_outer_right_config", std::string{Camera::NDK::BackCameraPlaceholder});
    Settings::values.camera_flip[OuterRightCamera] =
            GetIntegerSetting("camera_outer_right_flip", 0);
    Settings::values.camera_name[InnerCamera] =
            GetStringSetting("camera_inner_name", "ndk");
    Settings::values.camera_config[InnerCamera] =
            GetStringSetting("camera_inner_config", std::string{Camera::NDK::FrontCameraPlaceholder});
    Settings::values.camera_flip[InnerCamera] =
            GetIntegerSetting("camera_inner_flip", 0);
    Settings::values.camera_name[OuterLeftCamera] =
            GetStringSetting("camera_outer_left_name", "ndk");
    Settings::values.camera_config[OuterLeftCamera] =
            GetStringSetting("camera_outer_left_config", std::string{Camera::NDK::BackCameraPlaceholder});
    Settings::values.camera_flip[OuterLeftCamera] =
            GetIntegerSetting("camera_outer_left_flip", 0);

    // Miscellaneous
    ReadSetting("Miscellaneous", Settings::values.log_filter);
    ReadSetting("Miscellaneous", Settings::values.log_regex_filter);

    // Apply the log_filter setting as the logger has already been initialized
    // and doesn't pick up the filter on its own.
    Common::Log::Filter filter;
    filter.ParseFilterString(Settings::values.log_filter.GetValue());
    Common::Log::SetGlobalFilter(filter);
    Common::Log::SetRegexFilter(Settings::values.log_regex_filter.GetValue());

    // Debugging
    Settings::values.record_frame_times = GetBooleanSetting("record_frame_times");
    ReadSetting("Debugging", Settings::values.renderer_debug);
    ReadSetting("Debugging", Settings::values.use_gdbstub);
    ReadSetting("Debugging", Settings::values.gdbstub_port);
    ReadSetting("Debugging", Settings::values.instant_debug_log);

    for (const auto& service_module : Service::service_module_map) {
        bool use_lle = false;
        Settings::values.lle_modules.emplace(service_module.name, use_lle);
    }

    // Web Service
    NetSettings::values.web_api_url = GetStringSetting("web_api_url", "https://api.citra-emu.org");
    NetSettings::values.citra_username = GetStringSetting("citra_username", "AZAHAR");
    NetSettings::values.citra_token = GetStringSetting("citra_token", "");
}

void Config::Reload() {
    ReadValues();
}

bool Config::GetBooleanSetting(const std::string& key, const bool placeholder) {
    JNIEnv* env = IDCache::GetEnvForThread();
    jstring jKey = env->NewStringUTF(key.c_str());
    jclass settingsClass = env->FindClass("org/citra/citra_emu/features/settings/model/NativeSettings");
    if (!settingsClass) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }
    jmethodID methodID = env->GetStaticMethodID(settingsClass, "getBooleanSetting", "(Ljava/lang/String;)Z");
    if (!methodID) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }
    jboolean result = env->CallStaticBooleanMethod(settingsClass, methodID, jKey);
    env->DeleteLocalRef(jKey);

    if (!result)
        return placeholder;

    return static_cast<bool>(result);
}

int Config::GetIntegerSetting(const std::string& key, const int placeholder) {
    JNIEnv* env = IDCache::GetEnvForThread();
    jstring jKey = env->NewStringUTF(key.c_str());
    jclass settingsClass = env->FindClass("org/citra/citra_emu/features/settings/model/NativeSettings");

    if (!settingsClass) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }

    jmethodID methodID = env->GetStaticMethodID(settingsClass, "getIntSetting", "(Ljava/lang/String;)I");

    if (!methodID) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }

    jint result = env->CallStaticIntMethod(settingsClass, methodID, jKey);
    env->DeleteLocalRef(jKey);

    if (!result)
        return placeholder;

    return static_cast<int>(result);
}

std::string Config::GetStringSetting(const std::string& key, const std::string& placeholder) {
    JNIEnv* env = IDCache::GetEnvForThread();
    jstring jKey = env->NewStringUTF(key.c_str());
    jclass settingsClass = env->FindClass("org/citra/citra_emu/features/settings/model/NativeSettings");
    jmethodID methodID = env->GetStaticMethodID(settingsClass, "getStringSetting", "(Ljava/lang/String;)Ljava/lang/String;");

    jstring jResult = (jstring) env->CallStaticObjectMethod(settingsClass, methodID, jKey);
    env->DeleteLocalRef(jKey);

    if (!jResult) {
        return placeholder;
    }

    const char* resultCStr = env->GetStringUTFChars(jResult, nullptr);
    std::string result(resultCStr);
    env->ReleaseStringUTFChars(jResult, resultCStr);
    env->DeleteLocalRef(jResult);

    return result;
}

float Config::GetFloatSetting(const std::string& key, const bool scaled, const float placeholder) {
    JNIEnv* env = IDCache::GetEnvForThread();
    jstring jKey = env->NewStringUTF(key.c_str());

    if (!scaled)
        return placeholder; // TODO: when adding any normal float setting also implement me

    jclass settingsClass = env->FindClass("org/citra/citra_emu/features/settings/model/NativeSettings");
    if (!settingsClass) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }
    jmethodID methodID;
    if (scaled) {
        methodID = env->GetStaticMethodID(settingsClass, "getScaledFloatSetting", "(Ljava/lang/String;)F");
    } else {
        methodID = env->GetStaticMethodID(settingsClass, "getFloatSetting", "(Ljava/lang/String;)F");
    }

    if (!methodID) {
        env->DeleteLocalRef(jKey);
        return placeholder;
    }

    jfloat result = env->CallStaticFloatMethod(settingsClass, methodID, jKey);
    env->DeleteLocalRef(jKey);

    if (!result)
        return placeholder;

    return result != 0 ? static_cast<float>(result) : placeholder;
}