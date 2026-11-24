#pragma once
#include <unordered_map>
#include <vector>
#include <SDL.h>
#include "Shared/Interfaces/IKeyManager.h"
#include "Shared/KeyDefinitions.h"

class Emulator;

class SdlKeyManager : public IKeyManager
{
private:
	static constexpr int BaseMouseButtonIndex = 0x200;
	static constexpr int BaseGamepadIndex = 0x1000;

	Emulator* _emu = nullptr;
	vector<KeyDefinition> _keyDefinitions;
	bool _keyState[0x205] = {};
	std::unordered_map<uint16_t, string> _keyNames;
	std::unordered_map<string, uint16_t> _keyCodes;
	bool _disableAllKeys = false;
	int _lastJoystickCount = -1;

	vector<SDL_GameController*> _controllers;
	vector<SDL_Joystick*> _joysticks;

	bool IsJoystickButtonPressed(int port, int button);

public:
	SdlKeyManager(Emulator* emu);
	virtual ~SdlKeyManager();

	void RefreshState() override;
	void UpdateDevices() override;
	bool IsMouseButtonPressed(MouseButton button) override;
	bool IsKeyPressed(uint16_t keyCode) override;
	vector<uint16_t> GetPressedKeys() override;
	string GetKeyName(uint16_t keyCode) override;
	uint16_t GetKeyCode(string keyName) override;
	bool SetKeyState(uint16_t scanCode, bool state) override;
	void ResetKeyState() override;
	void SetDisabled(bool disabled) override;
};
