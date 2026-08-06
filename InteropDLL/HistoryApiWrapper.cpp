#include "Common.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/RewindManager.h"
#include "Core/Shared/HistoryViewer.h"
#include "Core/Shared/Interfaces/IRenderingDevice.h"
#include "Core/Shared/Interfaces/IAudioDevice.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Core/Shared/Movies/MovieManager.h"
#include "Core/Shared/MessageManager.h"
#include "Shared/Video/SoftwareRenderer.h"
#include "InteropNotificationListeners.h"
#include "Utilities/SimpleLock.h"
#include "InteropLifecycle.h"
#include <mutex>

#ifdef _WIN32
	#include "Windows/Renderer.h"
	#include "Windows/SoundManager.h"
#elif __APPLE__
	#include "Sdl/SdlSoundManager.h"
#else
	#include "Sdl/SdlRenderer.h"
	#include "Sdl/SdlSoundManager.h"
#endif

extern unique_ptr<Emulator>& _emu;
extern bool _softwareRenderer;

SimpleLock& _historyLifecycleLock = *new SimpleLock();
static std::recursive_mutex& _historyOperationMutex = *new std::recursive_mutex();
unique_ptr<Emulator>& _historyPlayer = *new unique_ptr<Emulator>();
unique_ptr<IRenderingDevice>& _historyRenderer = *new unique_ptr<IRenderingDevice>();
unique_ptr<IAudioDevice>& _historySoundManager = *new unique_ptr<IAudioDevice>();

HistoryViewer* _historyViewer = nullptr;

static InteropNotificationListeners& _listeners = *new InteropNotificationListeners();

static void ReleaseHistoryPlayer(bool processExit)
{
	auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
	if(!_historyPlayer) {
		_historyViewer = nullptr;
		return;
	}

	if(processExit) {
		_historyPlayer->ReleaseForProcessExit();
	} else {
		_historyPlayer->Release();
	}
	_historyRenderer.reset();
	_historySoundManager.reset();
	_historyPlayer.reset();
	_historyViewer = nullptr;
}

void HistoryViewerDisableCallbacksForProcessExit()
{
	_listeners.DisableCallbacks();
}

void HistoryViewerReleaseForInteropShutdown(bool processExit)
{
	ReleaseHistoryPlayer(processExit);
}

extern "C"
{
	DllExport bool __stdcall HistoryViewerEnabled()
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(false);
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		return _emu->GetRewindManager()->HasHistory();
	}

	DllExport void __stdcall HistoryViewerRelease()
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(InteropNotificationListener::IsCallbackOnCurrentThread()) {
			MessageManager::Log("[Shutdown] Ignoring reentrant history release from a notification callback");
			return;
		}
		std::lock_guard<std::recursive_mutex> operationLock(_historyOperationMutex);
		// Closing the history viewer must not wait for unrelated callbacks from
		// the main emulator.  Some main callbacks synchronously wait for the UI
		// thread that closes this window.
		_listeners.DisableCallbacksAndWait();
		ReleaseHistoryPlayer(false);
	}

	DllExport void __stdcall HistoryViewerInitialize(void* windowHandle, void* viewerHandle)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(InteropNotificationListener::IsCallbackOnCurrentThread()) {
			MessageManager::Log("[Shutdown] Ignoring reentrant history initialization from a notification callback");
			return;
		}
		std::lock_guard<std::recursive_mutex> operationLock(_historyOperationMutex);
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		_listeners.EnableCallbacks();
		_historyPlayer.reset(new Emulator());
		_historyPlayer->Initialize();
		_historyPlayer->GetSettings()->CopySettings(*_emu->GetSettings());

		_historyViewer = _historyPlayer->GetHistoryViewer();
		if(!_historyViewer->Initialize(_emu.get())) {
			HistoryViewerRelease();
			return;
		}

		_historyPlayer->GetSettings()->GetEmulationConfig().EmulationSpeed = 100;

		if(_softwareRenderer) {
			_historyRenderer.reset(new SoftwareRenderer(_historyPlayer.get()));
		} else {
			#ifdef _WIN32
				_historyRenderer.reset(new Renderer(_historyPlayer.get(), (HWND)viewerHandle));
			#elif __APPLE__
				_historyRenderer.reset(new SoftwareRenderer(_historyPlayer.get()));
			#else
				_historyRenderer.reset(new SdlRenderer(_historyPlayer.get(), viewerHandle));
			#endif
		}

		#ifdef _WIN32
			_historySoundManager.reset(new SoundManager(_historyPlayer.get(), (HWND)windowHandle));
		#elif __APPLE__
			_historySoundManager.reset(new SdlSoundManager(_historyPlayer.get()));
		#else
			_historySoundManager.reset(new SdlSoundManager(_historyPlayer.get()));
		#endif
	}

	DllExport HistoryViewerState __stdcall HistoryViewerGetState()
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(HistoryViewerState {});
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		return _historyViewer ? _historyViewer->GetState() : HistoryViewerState {};
	}

	DllExport void __stdcall HistoryViewerSetOptions(HistoryViewerOptions options)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		if(_historyViewer) {
			_historyViewer->SetOptions(options);
		}
	}

	DllExport bool __stdcall HistoryViewerCreateSaveState(const char* outputFile, uint32_t position)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(false);
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		return _historyViewer ? _historyViewer->CreateSaveState(outputFile, position) : false;
	}

	DllExport bool __stdcall HistoryViewerSaveMovie(const char* movieFile, uint32_t startPosition, uint32_t endPosition)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(false);
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		return _historyViewer ? _historyViewer->SaveMovie(movieFile, startPosition, endPosition) : false;
	}

	DllExport void __stdcall HistoryViewerResumeGameplay(uint32_t resumePosition)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		if(_historyViewer) {
			_historyViewer->ResumeGameplay(resumePosition);
		}
	}

	DllExport void __stdcall HistoryViewerSetPosition(uint32_t seekPosition)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		if(_historyViewer) {
			_historyViewer->SeekTo(seekPosition);
		}
	}

	DllExport INotificationListener* __stdcall HistoryViewerRegisterNotificationCallback(NotificationListenerCallback callback)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(nullptr);
		auto lifecycleLock = _historyLifecycleLock.AcquireSafe();
		return _listeners.RegisterNotificationCallback(callback, _historyPlayer.get());
	}

	DllExport void __stdcall HistoryViewerUnregisterNotificationCallback(INotificationListener* listener)
	{
		_listeners.UnregisterNotificationCallback(listener);
	}
}
