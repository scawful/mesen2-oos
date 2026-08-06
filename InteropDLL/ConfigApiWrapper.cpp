#include "Common.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/Interfaces/IAudioDevice.h"
#include "Core/Shared/BaseControlManager.h"
#include "Core/Shared/BaseControlDevice.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/SettingTypes.h"
#include "Utilities/StringUtilities.h"
#include "InteropLifecycle.h"

extern unique_ptr<Emulator>& _emu;
extern unique_ptr<IAudioDevice>& _soundManager;

extern "C" {
	DllExport void __stdcall SetVideoConfig(VideoConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetVideoConfig(config);
	}

	DllExport void __stdcall SetAudioConfig(AudioConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetAudioConfig(config);
	}

	DllExport void __stdcall SetInputConfig(InputConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetInputConfig(config);
	}

	DllExport void __stdcall SetEmulationConfig(EmulationConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetEmulationConfig(config);
	}
	
	DllExport void __stdcall SetGameboyConfig(GameboyConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetGameboyConfig(config);
	}

	DllExport void __stdcall SetGbaConfig(GbaConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetGbaConfig(config);
	}

	DllExport void __stdcall SetPcEngineConfig(PcEngineConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetPcEngineConfig(config);
	}

	DllExport void __stdcall SetNesConfig(NesConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetNesConfig(config);
	}

	DllExport void __stdcall SetSnesConfig(SnesConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetSnesConfig(config);
	}

	DllExport void __stdcall SetSmsConfig(SmsConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetSmsConfig(config);
	}

	DllExport void __stdcall SetGameConfig(GameConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetGameConfig(config);
	}

	DllExport void __stdcall SetPreferences(PreferencesConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetPreferences(config);
	}

	DllExport void __stdcall SetAudioPlayerConfig(AudioPlayerConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetAudioPlayerConfig(config);
	}

	DllExport void __stdcall SetDebugConfig(DebugConfig config)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetDebugConfig(config);
	}

	DllExport void __stdcall SetShortcutKeys(ShortcutKeyInfo shortcuts[], uint32_t count)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		vector<ShortcutKeyInfo> shortcutList(shortcuts, shortcuts + count);
		_emu->GetSettings()->SetShortcutKeys(shortcutList);
	}

	DllExport NesConfig __stdcall GetNesConfig()
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(NesConfig {});
		return _emu->GetSettings()->GetNesConfig();
	}

	DllExport void __stdcall GetAudioDevices(char* outDeviceList, uint32_t maxLength)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		StringUtilities::CopyToBuffer(_soundManager ? _soundManager->GetAvailableDevices() : "", outDeviceList, maxLength);
	}

	DllExport void __stdcall SetEmulationFlag(EmulationFlags flag, bool enabled)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetFlagState(flag, enabled);
	}

	DllExport void __stdcall SetDebuggerFlag(DebuggerFlags flag, bool enabled)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->GetSettings()->SetDebuggerFlag(flag, enabled);
	}
}
