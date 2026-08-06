#include "Common.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Netplay/ClientConnectionData.h"
#include "Core/Netplay/GameServer.h"
#include "Core/Netplay/GameClient.h"
#include "InteropLifecycle.h"

extern unique_ptr<Emulator>& _emu;

extern "C" {
	DllExport void __stdcall StartServer(uint16_t port, char* password) { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetGameServer()->StartServer(port, password); }
	DllExport void __stdcall StopServer() { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetGameServer()->StopServer(); }
	DllExport bool __stdcall IsServerRunning() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetGameServer()->Started(); }

	DllExport void __stdcall Connect(char* host, uint16_t port, char* password, bool spectator)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		ClientConnectionData connectionData(host, port, password, spectator);
		_emu->GetGameClient()->Connect(connectionData);
	}

	DllExport void __stdcall Disconnect() { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetGameClient()->Disconnect(); }
	DllExport bool __stdcall IsConnected() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetGameClient()->Connected(); }

	DllExport void __stdcall NetPlayGetControllerList(NetplayControllerUsageInfo* list, int32_t& length)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		vector<NetplayControllerUsageInfo> controllers;
		if(_emu->GetGameServer()->Started()) {
			controllers = _emu->GetGameServer()->GetControllerList();
		} else {
			controllers = _emu->GetGameClient()->GetControllerList();
		}

		for(size_t i = 0; i < controllers.size() && i < length; i++) {
			list[i] = controllers[i];
		}
		length = (int32_t)controllers.size();
	}

	DllExport void __stdcall NetPlaySelectController(NetplayControllerInfo controller)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_emu->GetGameServer()->Started()) {
			return _emu->GetGameServer()->SetHostControllerPort(controller);
		} else {
			return _emu->GetGameClient()->SelectController(controller);
		}
	}

	DllExport NetplayControllerInfo __stdcall NetPlayGetControllerPort()
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(NetplayControllerInfo {});
		if(_emu->GetGameServer()->Started()) {
			return _emu->GetGameServer()->GetHostControllerPort();
		} else {
			return _emu->GetGameClient()->GetControllerPort();
		}
	}
}
