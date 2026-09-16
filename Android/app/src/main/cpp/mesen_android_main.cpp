#include <SDL.h>
#include <SDL_system.h>
#include <SDL_syswm.h>
#include <string>
#include <sstream>
#include <mutex>
#include <unistd.h>
#include <cstdlib>
#include <cstdint>
#include <atomic>
#include "HandheldFramePresenter.h"
#include "HandheldRuntimeSnapshot.h"
#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#endif
#include "Utilities/FolderUtilities.h"
#include "Shared/Interfaces/INotificationListener.h"
#include "Shared/KeyManager.h"

extern "C" {
	void InitDll();
	void InitializeEmu(const char* homeFolder, void* windowHandle, void* viewerHandle, bool softwareRenderer, bool noAudio, bool noVideo, bool noInput);
	bool LoadRom(char* filename, char* patchFile);
	void ApplyHandheldSnesDefaults();
	void HandheldReset();
	bool HandheldReadRuntimeSnapshot(HandheldRuntimeSnapshot& snapshot);
	void Pause();
	void Resume();
	void* RegisterNotificationCallback(void (*callback)(int, void*));
	void Release();
}

static std::string g_romPath;
static bool g_pauseComboLatched = false;

struct SoftwareRendererSurface
{
	uint32_t* Buffer;
	uint32_t Width;
	uint32_t Height;
	bool IsDirty;
};

struct SoftwareRendererFrame
{
	SoftwareRendererSurface Frame;
	SoftwareRendererSurface EmuHud;
	SoftwareRendererSurface ScriptHud;
};

static HandheldVideo::FrameMailbox g_frames;
static std::atomic<int> g_presentMode{0};
static uint32_t g_frameEvent = static_cast<uint32_t>(-1);
static std::atomic<uint64_t> g_renderCallbacks{0};
static std::atomic<uint64_t> g_renderNewFrames{0};
static std::atomic<uint64_t> g_renderUploads{0};
static std::atomic<uint64_t> g_renderPresents{0};
static std::atomic<uint64_t> g_renderErrors{0};
static std::atomic<uint64_t> g_renderSurfaceRebuilds{0};
static std::mutex g_surfaceDiagnosticMutex;
static std::string g_surfaceDiagnostic = "null";
// Only the SDL thread reads/writes these tracing flags.
static bool g_traceNextPresent = true;
static bool g_traceNextRebuild = true;
static uint64_t g_lastPresentErrorTrace = 0;
static uint64_t g_lastRebuildTrace = 0;

static std::string DiagnosticQuote(const char* value)
{
	std::string out = "\"";
	const char* hex = "0123456789abcdef";
	for(const unsigned char c : std::string(value ? value : "")) {
		if(c == '"' || c == '\\') {
			out += '\\';
			out += static_cast<char>(c);
		} else if(c < 0x20) {
			out += "\\u00";
			out += hex[c >> 4];
			out += hex[c & 15];
		} else {
			out += static_cast<char>(c);
		}
	}
	return out + "\"";
}

static void TraceSurface(const char* event, SDL_Window* window, SDL_Renderer* renderer, const char* presentError = "")
{
#ifdef __ANDROID__
	// Read actual EGL bindings without changing them. Keep one process-lifetime
	// library reference; SDL already depends on EGL for its Android framebuffer.
	static void* egl = dlopen("libEGL.so", RTLD_LAZY | RTLD_LOCAL);
	static auto getContext = egl ? reinterpret_cast<decltype(&eglGetCurrentContext)>(dlsym(egl, "eglGetCurrentContext")) : nullptr;
	static auto getSurface = egl ? reinterpret_cast<decltype(&eglGetCurrentSurface)>(dlsym(egl, "eglGetCurrentSurface")) : nullptr;
	SDL_SysWMinfo info{};
	SDL_VERSION(&info.version);
	const bool haveWindow = window && SDL_GetWindowWMInfo(window, &info) == SDL_TRUE && info.subsystem == SDL_SYSWM_ANDROID;
	const Uint32 flags = window ? SDL_GetWindowFlags(window) : 0;
	SDL_RendererInfo rendererInfo{};
	const bool haveRenderer = renderer && SDL_GetRendererInfo(renderer, &rendererInfo) == 0;
	void* storedSurface = haveWindow ? info.info.android.surface : nullptr;
	void* cachedContext = SDL_GL_GetCurrentContext();
	void* actualContext = getContext ? getContext() : nullptr;
	void* drawSurface = getSurface ? getSurface(EGL_DRAW) : nullptr;
	std::ostringstream out;
	out << "{\"event\":" << DiagnosticQuote(event) << ",\"ticks\":" << SDL_GetTicks64()
		<< ",\"windowFlags\":" << flags
		<< ",\"windowHidden\":" << ((flags & SDL_WINDOW_HIDDEN) ? "true" : "false")
		<< ",\"windowMinimized\":" << ((flags & SDL_WINDOW_MINIMIZED) ? "true" : "false")
		<< ",\"windowInputFocus\":" << ((flags & SDL_WINDOW_INPUT_FOCUS) ? "true" : "false")
		<< ",\"renderer\":" << (haveRenderer ? DiagnosticQuote(rendererInfo.name) : "null")
		<< ",\"nativeWindow\":\"" << (haveWindow ? info.info.android.window : nullptr) << "\""
		<< ",\"storedEglSurface\":\"" << storedSurface << "\""
		<< ",\"sdlWindow\":\"" << window << "\""
		<< ",\"sdlCurrentWindow\":\"" << SDL_GL_GetCurrentWindow() << "\""
		<< ",\"sdlContext\":\"" << cachedContext << "\""
		<< ",\"eglContext\":\"" << actualContext << "\""
		<< ",\"eglDrawSurface\":\"" << drawSurface << "\""
		<< ",\"eglProbeAvailable\":" << ((getContext && getSurface) ? "true" : "false")
		<< ",\"surfaceReady\":" << ((haveWindow && info.info.android.window && storedSurface) ? "true" : "false")
		<< ",\"eglBindingReady\":" << ((getContext && getSurface) ? (actualContext && drawSurface ? "true" : "false") : "null")
		<< ",\"contextMatches\":" << (getContext ? (cachedContext == actualContext ? "true" : "false") : "null")
		<< ",\"drawSurfaceMatches\":" << (getSurface && haveWindow ? (storedSurface == drawSurface ? "true" : "false") : "null")
		<< ",\"presentError\":" << DiagnosticQuote(presentError)
		<< ",\"rebuilds\":" << g_renderSurfaceRebuilds.load(std::memory_order_relaxed)
		<< ",\"uploads\":" << g_renderUploads.load(std::memory_order_relaxed)
		<< ",\"presents\":" << g_renderPresents.load(std::memory_order_relaxed) << "}";
	const std::string json = out.str();
	{
		std::lock_guard<std::mutex> lock(g_surfaceDiagnosticMutex);
		g_surfaceDiagnostic = json;
	}
	__android_log_print(ANDROID_LOG_INFO, "MesenSurface", "%s", json.c_str());
#endif
}

struct AndroidSurfaceBinding
{
	uintptr_t Window = 0;
	uintptr_t Surface = 0;
	bool Bootstrap = false;
	bool Complete() const { return Window != 0 && Surface != 0; }
	bool SamePair(const AndroidSurfaceBinding& other) const { return Window == other.Window && Surface == other.Surface; }
};

static AndroidSurfaceBinding ReadSurfaceBinding(SDL_Window* window)
{
	AndroidSurfaceBinding binding;
#ifdef __ANDROID__
	SDL_SysWMinfo info{};
	SDL_VERSION(&info.version);
	if(window && SDL_GetWindowWMInfo(window, &info) == SDL_TRUE && info.subsystem == SDL_SYSWM_ANDROID) {
		binding.Window = reinterpret_cast<uintptr_t>(info.info.android.window);
		binding.Surface = reinterpret_cast<uintptr_t>(info.info.android.surface);
		// Before its first renderer, a non-GL SDL window has no EGL surface.
		// The software renderer creates its hidden GL framebuffer at that point.
		binding.Bootstrap = !(SDL_GetWindowFlags(window) & SDL_WINDOW_OPENGL);
	}
#endif
	return binding;
}

static bool PresentWithDiagnostics(SDL_Renderer* renderer, SDL_Window* window)
{
	// SDL_RenderPresent returns void even when its backend fails. Capture its
	// fresh error before any diagnostic queries so failed swaps remain pending.
	SDL_ClearError();
	SDL_RenderPresent(renderer);
	g_renderPresents.fetch_add(1, std::memory_order_relaxed);
	const std::string error = SDL_GetError();
	if(!error.empty()) {
		g_renderErrors.fetch_add(1, std::memory_order_relaxed);
	}
	const uint64_t now = SDL_GetTicks64();
	if(g_traceNextPresent || (!error.empty() && now - g_lastPresentErrorTrace >= 1000)) {
		TraceSurface(g_traceNextPresent ? "first-present" : "present-error", window, renderer, error.c_str());
		g_traceNextPresent = false;
		g_lastPresentErrorTrace = now;
	}
	return error.empty();
}

static void OnEmuNotification(int type, void* param)
{
	if(type != static_cast<int>(ConsoleNotificationType::RefreshSoftwareRenderer) || !param) {
		return;
	}

	auto* frame = static_cast<SoftwareRendererFrame*>(param);
	g_renderCallbacks.fetch_add(1, std::memory_order_relaxed);
	// SoftwareRenderer also notifies while paused, with IsDirty always true.
	// Compare the pixels while the callback buffer is still valid.
	if(g_frames.Publish(frame->Frame.Buffer, frame->Frame.Width, frame->Frame.Height)) {
		g_renderNewFrames.fetch_add(1, std::memory_order_relaxed);
		if(g_frameEvent != static_cast<uint32_t>(-1)) {
			SDL_Event event{};
			event.type = g_frameEvent;
			SDL_PushEvent(&event); // Thread-safe wakeup; no SDL rendering here.
		}
	}
}

static bool FileReadable(const char* path)
{
	return path && path[0] && access(path, R_OK) == 0;
}

static std::string FindRom(int argc, char** argv, const char* home)
{
	if(argc > 1 && FileReadable(argv[1])) {
		return argv[1];
	}

	const char* env = std::getenv("MESEN2_ROM");
	if(FileReadable(env)) {
		return env;
	}

	if(home && home[0]) {
		std::string homeRom = std::string(home) + "/oos168x.sfc";
		if(FileReadable(homeRom.c_str())) {
			return homeRom;
		}
	}

	const char* candidates[] = {
		"/storage/emulated/0/Android/data/ca.mesen.oos/files/oos168x.sfc",
		"/storage/emulated/0/OracleOfSecrets/oos168x.sfc",
		"/storage/0000-0000/snes/oos168x.sfc",
		nullptr
	};
	for(int i = 0; candidates[i]; i++) {
		if(FileReadable(candidates[i])) {
			return candidates[i];
		}
	}
	return {};
}

static bool PresentMoss(SDL_Renderer* renderer, SDL_Window* window)
{
	if(SDL_SetRenderDrawColor(renderer, 11, 20, 16, 255) != 0 || SDL_RenderClear(renderer) != 0) {
		g_renderErrors.fetch_add(1, std::memory_order_relaxed);
		return false;
	}
	return PresentWithDiagnostics(renderer, window);
}

static bool PresentFrame(SDL_Renderer* renderer, SDL_Window* window, SDL_Texture** texture,
	uint32_t* texW, uint32_t* texH, HandheldVideo::Frame& frame,
	HandheldVideo::PresentationState& presentation, int presentMode)
{
	g_frames.CopyLatest(frame);
	if(!presentation.NeedsPresent(frame)) {
		return true;
	}
	if(frame.Pixels.empty()) {
		if(PresentMoss(renderer, window)) {
			presentation.Presented(frame);
			return true;
		}
		return false;
	}
	const uint32_t width = frame.Width;
	const uint32_t height = frame.Height;

	if(!*texture || *texW != width || *texH != height) {
		if(*texture) {
			SDL_DestroyTexture(*texture);
		}
		presentation.InvalidateTexture();
		*texture = SDL_CreateTexture(
			renderer,
			SDL_PIXELFORMAT_ARGB8888,
			SDL_TEXTUREACCESS_STREAMING,
			static_cast<int>(width),
			static_cast<int>(height)
		);
		*texW = width;
		*texH = height;
	}
	if(!*texture) {
		g_renderErrors.fetch_add(1, std::memory_order_relaxed);
		return false;
	}

	if(presentation.NeedsUpload(frame)) {
		if(SDL_UpdateTexture(*texture, nullptr, frame.Pixels.data(), static_cast<int>(width * sizeof(uint32_t))) != 0) {
			g_renderErrors.fetch_add(1, std::memory_order_relaxed);
			return false;
		}
		g_renderUploads.fetch_add(1, std::memory_order_relaxed);
		presentation.Uploaded(frame);
	}
	int winW = 0;
	int winH = 0;
	SDL_GetRendererOutputSize(renderer, &winW, &winH);
	if((winW <= 0 || winH <= 0) && window) {
		SDL_GetWindowSize(window, &winW, &winH);
	}
	if(winW <= 0 || winH <= 0) {
		return false; // Surface is not ready; retain the pending redraw.
	}
	SDL_Rect dest;
	dest.x = 0;
	dest.y = 0;
	dest.w = winW;
	dest.h = winH;
	if(width > 0 && height > 0 && winW > 0 && winH > 0) {
		if(presentMode == 0) {
			int scaleX = winW / static_cast<int>(width);
			int scaleY = winH / static_cast<int>(height);
			int scale = scaleX < scaleY ? scaleX : scaleY;
			if(scale < 1) {
				scale = 1;
			}
			dest.w = static_cast<int>(width) * scale;
			dest.h = static_cast<int>(height) * scale;
		} else {
			float scaleX = static_cast<float>(winW) / static_cast<float>(width);
			float scaleY = static_cast<float>(winH) / static_cast<float>(height);
			float scale = scaleX < scaleY ? scaleX : scaleY;
			dest.w = static_cast<int>(static_cast<float>(width) * scale);
			dest.h = static_cast<int>(static_cast<float>(height) * scale);
		}
		dest.x = (winW - dest.w) / 2;
		dest.y = (winH - dest.h) / 2;
	}
	if(SDL_SetRenderDrawColor(renderer, 11, 20, 16, 255) != 0
		|| SDL_RenderClear(renderer) != 0
		|| SDL_RenderCopy(renderer, *texture, nullptr, &dest) != 0) {
		g_renderErrors.fetch_add(1, std::memory_order_relaxed);
		return false;
	}
	if(!PresentWithDiagnostics(renderer, window)) {
		return false;
	}
	presentation.Presented(frame);
	return true;
}

extern "C" int SDL_main(int argc, char* argv[])
{
	SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");
	SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
	SDL_SetHint(SDL_HINT_AUDIO_DEVICE_APP_NAME, "Oracle of Secrets");
	if(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMECONTROLLER | SDL_INIT_JOYSTICK) != 0) {
		return 1;
	}
	SDL_GameControllerEventState(SDL_ENABLE);
	SDL_JoystickEventState(SDL_ENABLE);
	g_frameEvent = SDL_RegisterEvents(1);

	setenv("MESEN2_TCP_PORT", "27015", 0);
	// The socket exposes privileged emulator controls. Keep it device-local
	// and require hosts to connect through an explicit adb forward.
	setenv("MESEN2_TCP_BIND", "127.0.0.1", 1);

	const char* home = SDL_AndroidGetInternalStoragePath();
	if(!home) {
		home = "/data/local/tmp/mesen2-oos";
	}
	std::string statusPath = std::string(home) + "/mesen2-tcp-27015.status";
	setenv("MESEN2_STATUS_PATH", statusPath.c_str(), 1);
	FolderUtilities::SetHomeFolder(home);

	// Bind the debug socket before waiting on the Android window. SystemUI
	// can steal focus on the RG353P during adb sessions; agents still need
	// TCP access in that state.
	InitDll();

	// Keep Mesen on SoftwareRenderer. SdlRenderer::Init() calls
	// SDL_CreateRenderer from Mesen's video thread, which SIGBUS-crashes
	// Mali GLES on the RG353P. Presenting happens on this SDL thread.
	static char pendingWindowSentinel;
	void* pendingWindow = &pendingWindowSentinel;
	InitializeEmu(home, pendingWindow, pendingWindow, true, false, false, false);
	ApplyHandheldSnesDefaults();
	RegisterNotificationCallback(OnEmuNotification);

	std::string rom = FindRom(argc, argv, home);
	if(!rom.empty()) {
		g_romPath = rom;
		LoadRom(const_cast<char*>(rom.c_str()), nullptr);
	}

	SDL_Window* window = SDL_CreateWindow(
		"Mesen2-OOS",
		SDL_WINDOWPOS_UNDEFINED,
		SDL_WINDOWPOS_UNDEFINED,
		640,
		480,
		SDL_WINDOW_SHOWN | SDL_WINDOW_FULLSCREEN
	);

	SDL_Renderer* renderer = nullptr;
	SDL_Texture* texture = nullptr;
	uint32_t texW = 0;
	uint32_t texH = 0;
	HandheldVideo::Frame frame;
	HandheldVideo::PresentationState presentation;
	HandheldVideo::SurfaceState surface;
	int presentMode = g_presentMode.load(std::memory_order_relaxed);
	bool running = true;
	auto handleEvent = [&](const SDL_Event& event) {
		const char* trace = nullptr;
		if(event.type == SDL_QUIT || event.type == SDL_APP_TERMINATING) {
			running = false;
		} else if(event.type == SDL_APP_WILLENTERBACKGROUND || event.type == SDL_APP_DIDENTERBACKGROUND) {
			surface.SetForeground(false);
			trace = "background";
		} else if(event.type == SDL_APP_DIDENTERFOREGROUND) {
			surface.SetForeground(true);
			trace = "foreground";
		} else if(event.type == SDL_JOYDEVICEADDED || event.type == SDL_CONTROLLERDEVICEADDED) {
			KeyManager::UpdateDevices();
			ApplyHandheldSnesDefaults();
		} else if(event.type == SDL_RENDER_TARGETS_RESET || event.type == SDL_RENDER_DEVICE_RESET) {
			surface.Invalidate();
			trace = "device-reset";
		} else if(event.type == SDL_WINDOWEVENT) {
			switch(event.window.event) {
				case SDL_WINDOWEVENT_HIDDEN:
				case SDL_WINDOWEVENT_MINIMIZED:
					surface.SetWindowVisible(false);
					trace = "window-hidden";
					break;
				case SDL_WINDOWEVENT_SHOWN:
				case SDL_WINDOWEVENT_RESTORED:
					surface.SetWindowVisible(true);
					trace = "window-restored";
					break;
				case SDL_WINDOWEVENT_EXPOSED:
				case SDL_WINDOWEVENT_RESIZED:
				case SDL_WINDOWEVENT_SIZE_CHANGED:
					presentation.RequestRedraw();
					break;
			}
		}
		if(trace) {
			g_traceNextPresent = true;
			g_traceNextRebuild = true;
			TraceSurface(trace, window, renderer);
		}
	};
	auto observeBinding = [&]() {
		const AndroidSurfaceBinding binding = ReadSurfaceBinding(window);
		if(surface.ObserveBinding(binding.Window, binding.Surface, binding.Bootstrap)) {
			presentation.RequestRedraw();
			g_traceNextPresent = true;
			g_traceNextRebuild = true;
			TraceSurface("binding-changed", window, renderer);
		}
		return binding;
	};
	while(running) {
		SDL_Event event;
		while(SDL_PollEvent(&event)) {
			handleEvent(event);
		}
		if(!running) {
			break;
		}

		KeyManager::RefreshKeyState();
		uint16_t startKey = KeyManager::GetKeyCode("Pad1 Start");
		uint16_t selectKey = KeyManager::GetKeyCode("Pad1 Select");
		if(!selectKey) {
			selectKey = KeyManager::GetKeyCode("Pad1 Back");
		}
		bool combo = startKey && selectKey
			&& KeyManager::IsKeyPressed(startKey)
			&& KeyManager::IsKeyPressed(selectKey);
#ifdef __ANDROID__
		if(combo && !g_pauseComboLatched) {
			g_pauseComboLatched = true;
			JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
			if(env) {
				jclass cls = env->FindClass("ca/mesen/oos/MesenActivity");
				if(cls) {
					jmethodID mid = env->GetStaticMethodID(cls, "showPauseFromNative", "()V");
					if(mid) {
						env->CallStaticVoidMethod(cls, mid);
					}
					env->DeleteLocalRef(cls);
				}
			}
		} else if(!combo) {
			g_pauseComboLatched = false;
		}
#endif
		int requestedMode = g_presentMode.load(std::memory_order_relaxed);
		if(requestedMode != presentMode) {
			presentMode = requestedMode;
			presentation.RequestRedraw();
		}
		const AndroidSurfaceBinding beforeRebuild = observeBinding();
		if(window && surface.CanRebuild(SDL_GetTicks64())) {
			const uint64_t now = SDL_GetTicks64();
			const bool traceRebuild = g_traceNextRebuild || now - g_lastRebuildTrace >= 1000;
			g_traceNextRebuild = false;
			if(traceRebuild) {
				TraceSurface("before-rebuild", window, renderer);
				g_lastRebuildTrace = now;
			}
			// SDL's software renderer borrows the window surface. On Android
			// that surface has its own GL renderer/texture, so rebuilding just
			// our frame texture cannot recover a destroyed EGL surface/context.
			if(renderer) {
				SDL_DestroyRenderer(renderer); // Also destroys its textures/surface.
				renderer = nullptr;
			}
			texture = nullptr;
			texW = texH = 0;
			// Also clears framebuffer resources left by a failed creation.
			SDL_DestroyWindowSurface(window);
			presentation.InvalidateTexture();
			// Preserve the Mali workaround: all SDL work stays on this thread.
			SDL_SetHint(SDL_HINT_RENDER_DRIVER, "software");
			renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
			const std::string creationError = renderer ? "" : SDL_GetError();
			const AndroidSurfaceBinding afterRebuild = observeBinding();
			const bool bootstrap = beforeRebuild.Bootstrap && beforeRebuild.Surface == 0;
			const bool usable = renderer && afterRebuild.Complete()
				&& (beforeRebuild.SamePair(afterRebuild)
					|| (bootstrap && beforeRebuild.Window == afterRebuild.Window));
			if(!usable) {
				g_renderErrors.fetch_add(1, std::memory_order_relaxed);
			} else {
				g_renderSurfaceRebuilds.fetch_add(1, std::memory_order_relaxed);
			}
			surface.Rebuilt(usable, SDL_GetTicks64());
			const std::string rebuildError = renderer && !usable ? "Android surface unavailable or changed during renderer creation" : creationError;
			if(traceRebuild) {
				TraceSurface("after-rebuild", window, renderer, rebuildError.c_str());
				g_traceNextPresent = true;
			}
		}
		const AndroidSurfaceBinding beforePresent = observeBinding();
		if(renderer && surface.CanDraw()) {
			const bool presented = PresentFrame(renderer, window, &texture, &texW, &texH, frame, presentation, presentMode);
			const AndroidSurfaceBinding afterPresent = observeBinding();
			if(!presented || !afterPresent.Complete() || !beforePresent.SamePair(afterPresent)) {
				// A Java surface callback can race a successful-looking creation or
				// draw. Retain pixels and redraw after a bounded resource retry.
				presentation.RequestRedraw();
				surface.Rebuilt(false, SDL_GetTicks64());
			}
		}
		// New pixels wake this wait immediately. The timeout also bounds input,
		// presentation-mode changes and retries when a texture/surface is absent.
		if(SDL_WaitEventTimeout(&event, 8)) {
			handleEvent(event);
		}
	}

	if(texture) {
		SDL_DestroyTexture(texture);
	}
	if(renderer) {
		SDL_DestroyRenderer(renderer);
	}
	Release();
	if(window) {
		SDL_DestroyWindow(window);
	}
	SDL_Quit();
	return 0;
}

#ifdef __ANDROID__
static std::string RomFileName()
{
	std::string name = g_romPath;
	auto slash = name.find_last_of('/');
	if(slash != std::string::npos) {
		name = name.substr(slash + 1);
	}
	return name;
}

extern "C" JNIEXPORT void JNICALL Java_ca_mesen_oos_MesenNative_pauseEmu(JNIEnv*, jclass)
{
	Pause();
}

extern "C" JNIEXPORT void JNICALL Java_ca_mesen_oos_MesenNative_resumeEmu(JNIEnv*, jclass)
{
	Resume();
}

extern "C" JNIEXPORT void JNICALL Java_ca_mesen_oos_MesenNative_resetEmu(JNIEnv*, jclass)
{
	HandheldReset();
}

extern "C" JNIEXPORT jboolean JNICALL Java_ca_mesen_oos_MesenNative_loadRom(JNIEnv* env, jclass, jstring path)
{
	if(!env || !path) {
		return JNI_FALSE;
	}
	const char* utf = env->GetStringUTFChars(path, nullptr);
	if(!utf) {
		return JNI_FALSE;
	}
	g_romPath = utf;
	bool ok = LoadRom(const_cast<char*>(utf), nullptr);
	env->ReleaseStringUTFChars(path, utf);
	if(ok) {
		ApplyHandheldSnesDefaults();
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL Java_ca_mesen_oos_MesenNative_statusJson(JNIEnv* env, jclass)
{
	bool pad = false;
	bool mapped = false;
	std::string padName;
	int n = SDL_NumJoysticks();
	for(int i = 0; i < n; i++) {
		const char* name = SDL_JoystickNameForIndex(i);
		if(name && SDL_strcasestr(name, "touch_joypad")) {
			continue;
		}
		if(name) {
			pad = true;
			if(padName.empty()) {
				padName = name;
			}
			if(SDL_IsGameController(i)) {
				mapped = true;
			}
		}
	}
	KeyManager::RefreshKeyState();
	std::string buttons;
	for(uint16_t key : KeyManager::GetPressedKeys()) {
		std::string name = KeyManager::GetKeyName(key);
		if(name.empty()) {
			continue;
		}
		if(!buttons.empty()) {
			buttons += ",";
		}
		buttons += name;
	}
	auto jsonEscape = [](const std::string& value) {
		std::string out;
		for(char c : value) {
			if(c == '"' || c == '\\') {
				out.push_back('\\');
			}
			out.push_back(c);
		}
		return out;
	};
	HandheldRuntimeSnapshot runtime;
	HandheldReadRuntimeSnapshot(runtime);
	std::string surfaceDiagnostic;
	{
		std::lock_guard<std::mutex> lock(g_surfaceDiagnosticMutex);
		surfaceDiagnostic = g_surfaceDiagnostic;
	}
	std::string json = std::string("{\"rom\":\"") + jsonEscape(RomFileName())
		+ "\",\"romPath\":\"" + jsonEscape(g_romPath)
		+ "\",\"socket\":\"tcp:27015\",\"pad\":" + (pad ? "true" : "false")
		+ ",\"mapped\":" + (mapped ? "true" : "false")
		+ ",\"padName\":\"" + jsonEscape(padName)
		+ "\",\"buttons\":\"" + jsonEscape(buttons)
		+ "\",\"paused\":" + (runtime.Paused ? "true" : "false")
		+ ",\"sha1\":\"" + jsonEscape(runtime.RomSha1)
		+ "\",\"frames\":" + std::to_string(runtime.FrameCount)
		+ ",\"mode\":" + std::to_string(runtime.Mode)
		+ ",\"sub\":" + std::to_string(runtime.Submode)
		+ ",\"indoor\":" + std::to_string(runtime.Indoor)
		+ ",\"area\":" + std::to_string(runtime.Area)
		+ ",\"room\":" + std::to_string(runtime.Room)
		+ ",\"presentMode\":" + std::to_string(g_presentMode.load())
		+ ",\"renderCallbacks\":" + std::to_string(g_renderCallbacks.load(std::memory_order_relaxed))
		+ ",\"renderNewFrames\":" + std::to_string(g_renderNewFrames.load(std::memory_order_relaxed))
		+ ",\"renderUploads\":" + std::to_string(g_renderUploads.load(std::memory_order_relaxed))
		+ ",\"renderPresents\":" + std::to_string(g_renderPresents.load(std::memory_order_relaxed))
		+ ",\"renderErrors\":" + std::to_string(g_renderErrors.load(std::memory_order_relaxed))
		+ ",\"renderSurfaceRebuilds\":" + std::to_string(g_renderSurfaceRebuilds.load(std::memory_order_relaxed))
		+ ",\"renderSurfaceDiagnostic\":" + surfaceDiagnostic
		+ "}";
	return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_ca_mesen_oos_MesenNative_setPresentMode(JNIEnv*, jclass, jint mode)
{
	g_presentMode.store(mode == 0 ? 0 : 1);
}
#endif
