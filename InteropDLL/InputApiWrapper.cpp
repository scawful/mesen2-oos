#include "Common.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/BaseControlManager.h"
#include "Core/Shared/KeyManager.h"
#include "Core/Shared/ShortcutKeyHandler.h"
#include "Utilities/StringUtilities.h"
#include "Core/Shared/Interfaces/IMouseManager.h"
#include "InteropLifecycle.h"

extern unique_ptr<IKeyManager>& _keyManager;
extern unique_ptr<IMouseManager>& _mouseManager;
extern unique_ptr<Emulator>& _emu;

extern "C" 
{
	DllExport void __stdcall SetMousePosition(double x, double y)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		KeyManager::SetMousePosition(_emu.get(), x, y);
	}

	DllExport void __stdcall SetMouseMovement(int16_t x, int16_t y)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		KeyManager::SetMouseMovement(x, y);
	}

	DllExport void __stdcall UpdateInputDevices()
	{ 
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_keyManager) {
			_keyManager->UpdateDevices();
		} 
	}

	DllExport void __stdcall RefreshKeyState()
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		KeyManager::RefreshKeyState();
	}

	DllExport void __stdcall GetPressedKeys(uint16_t* keyBuffer)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		vector<uint16_t> pressedKeys = KeyManager::GetPressedKeys();
		for(size_t i = 0; i < pressedKeys.size() && i < 3; i++) {
			keyBuffer[i] = pressedKeys[i];
		}
	}

	DllExport void __stdcall DisableAllKeys(bool disabled)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_keyManager) {
			_keyManager->SetDisabled(disabled);
		}
	}

	DllExport void __stdcall SetKeyState(uint16_t scanCode, bool state)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_keyManager) {
			if(_keyManager->SetKeyState(scanCode, state)) {
				_emu->GetShortcutKeyHandler()->ProcessKeys();
			}
		}
	}
	
	DllExport void __stdcall ResetKeyState()
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_keyManager) {
			_keyManager->ResetKeyState();
		}
	}

	DllExport void __stdcall GetKeyName(uint16_t keyCode, char* outKeyName, uint32_t maxLength)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		StringUtilities::CopyToBuffer(KeyManager::GetKeyName(keyCode), outKeyName, maxLength);
	}

	DllExport uint16_t __stdcall GetKeyCode(char* keyName)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(0);
		if(keyName) {
			return KeyManager::GetKeyCode(keyName);
		} else {
			return 0;
		}
	}

	DllExport bool __stdcall HasControlDevice(ControllerType type)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(false);
		return _emu->HasControlDevice(type);
	}

	DllExport void __stdcall ResetLagCounter()
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		_emu->ResetLagCounter();
	}

	DllExport SystemMouseState __stdcall GetSystemMouseState(void* rendererHandle)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(SystemMouseState {});
		if(_mouseManager) {
			return _mouseManager->GetSystemMouseState(rendererHandle);
		}
		SystemMouseState state = {};
		return state;
	}

	DllExport bool __stdcall CaptureMouse(int32_t x, int32_t y, int32_t width, int32_t height, void* rendererHandle)
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(false);
		if(_mouseManager) {
			return _mouseManager->CaptureMouse(x, y, width, height, rendererHandle);
		}
		return false;
	}

	DllExport void __stdcall ReleaseMouse()
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_mouseManager) {
			_mouseManager->ReleaseMouse();
		}
	}

	DllExport void __stdcall SetSystemMousePosition(int32_t x, int32_t y)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_mouseManager) {
			_mouseManager->SetSystemMousePosition(x, y);
		}
	}

	DllExport void __stdcall SetCursorImage(CursorImage image)
	{
		INTEROP_EMU_LEASE_OR_RETURN();
		if(_mouseManager) {
			_mouseManager->SetCursorImage(image);
		}
	}

	DllExport double __stdcall GetPixelScale()
	{
		INTEROP_EMU_LEASE_OR_RETURN_VALUE(1.0);
		if(_mouseManager) {
			return _mouseManager->GetPixelScale();
		}
		return 1.0;
	}
}
