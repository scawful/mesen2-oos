#include "SdlKeyManager.h"
#include "Shared/Emulator.h"
#include "Shared/MessageManager.h"
#include <cstring>

SdlKeyManager::SdlKeyManager(Emulator* emu)
{
	_emu = emu;
	ResetKeyState();

	_keyDefinitions = KeyDefinition::GetSharedKeyDefinitions();

	vector<string> buttonNames = {
		"A", "B", "C", "X", "Y", "Z", "L1", "R1", "L2", "R2", "Select", "Start", "L3", "R3",
		"X+", "X-", "Y+", "Y-", "Z+", "Z-",
		"X2+", "X2-", "Y2+", "Y2-", "Z2+", "Z2-",
		"Right", "Left", "Down", "Up",
		"Right 2", "Left 2", "Down 2", "Up 2",
		"Right 3", "Left 3", "Down 3", "Up 3",
		"Right 4", "Left 4", "Down 4", "Up 4",
		"Trigger", "Thumb", "Thumb2", "Top", "Top2",
		"Pinkie", "Base", "Base2", "Base3", "Base4",
		"Base5", "Base6", "Dead"
	};

	for(int i = 0; i < 4; i++) {
		for(int j = 0; j < (int)buttonNames.size(); j++) {
			uint32_t keyCode = (uint32_t)(SdlKeyManager::BaseGamepadIndex + i * 0x100 + j);
			string name = "Pad" + std::to_string(i + 1) + " " + buttonNames[j];
			_keyDefinitions.push_back({ name, keyCode });
			if(buttonNames[j] == "Select") {
				_keyDefinitions.push_back({ "Pad" + std::to_string(i + 1) + " Back", keyCode });
			}
		}
	}

	for(KeyDefinition &keyDef : _keyDefinitions) {
		_keyNames[keyDef.keyCode] = keyDef.name;
		_keyCodes[keyDef.name] = keyDef.keyCode;
	}

	int rc = SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER | SDL_INIT_JOYSTICK);
	if(rc != 0) {
		MessageManager::Log("[SdlKeyManager] SDL_InitSubSystem failed: " + string(SDL_GetError()));
	}
	UpdateDevices();
}

SdlKeyManager::~SdlKeyManager()
{
	for(auto controller : _controllers) {
		if(controller) {
			SDL_GameControllerClose(controller);
		}
	}
	for(auto joystick : _joysticks) {
		if(joystick) {
			SDL_JoystickClose(joystick);
		}
	}
}

static bool NameLooksLikeAnbernicPad(const char* name)
{
	if(!name) {
		return false;
	}
	return SDL_strcasestr(name, "retrogame") || SDL_strcasestr(name, "touch_joypad")
		|| SDL_strcasestr(name, "odroidgo") || SDL_strcasestr(name, "go-super")
		|| SDL_strcasestr(name, "go-advance") || SDL_strcasestr(name, "rg353");
}

static bool NameIsDuplicateVirtualPad(const char* name)
{
	return name && SDL_strcasestr(name, "touch_joypad");
}

static void InstallAndroidGamepadMapping(int deviceIndex)
{
	const char* name = SDL_JoystickNameForIndex(deviceIndex);
	if(!NameLooksLikeAnbernicPad(name)) {
		return;
	}

	char guid[33];
	SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(deviceIndex), guid, sizeof(guid));
	char mapping[768];
	SDL_snprintf(mapping, sizeof(mapping),
		"%s,%s,a:b0,b:b1,x:b2,y:b3,back:b4,guide:b5,start:b6,leftstick:b7,rightstick:b8,"
		"leftshoulder:b9,rightshoulder:b10,dpup:b11,dpdown:b12,dpleft:b13,dpright:b14,"
		"leftx:a0,lefty:a1,rightx:a2,righty:a3,lefttrigger:b15,righttrigger:b16,platform:Android,",
		guid, name);
	if(SDL_GameControllerAddMapping(mapping) >= 0) {
		MessageManager::Log(string("[SdlKeyManager] mapped ") + name + " guid=" + guid);
	}
}

static bool AxisPast(int16_t value, bool positive)
{
	const int threshold = 16000;
	return positive ? value > threshold : value < -threshold;
}

void SdlKeyManager::RefreshState()
{
	// The Android/SDL host owns SDL_PollEvent. Pump controller + keyboard here.
	SDL_GameControllerUpdate();
	int joystickCount = SDL_NumJoysticks();
	if(joystickCount != _lastJoystickCount) {
		UpdateDevices();
	}

	const Uint8* keys = SDL_GetKeyboardState(nullptr);
	if(keys) {
		auto setKey = [&](const char* name, SDL_Scancode scancode) {
			uint16_t code = GetKeyCode(name);
			if(code && code < 0x205) {
				_keyState[code] = keys[scancode] ? 1 : 0;
			}
		};
		setKey("Up Arrow", SDL_SCANCODE_UP);
		setKey("Down Arrow", SDL_SCANCODE_DOWN);
		setKey("Left Arrow", SDL_SCANCODE_LEFT);
		setKey("Right Arrow", SDL_SCANCODE_RIGHT);
		setKey("Enter", SDL_SCANCODE_RETURN);
		setKey("Esc", SDL_SCANCODE_ESCAPE);
		setKey("Tab", SDL_SCANCODE_TAB);
		setKey("A", SDL_SCANCODE_A);
		setKey("B", SDL_SCANCODE_B);
		setKey("S", SDL_SCANCODE_S);
		setKey("X", SDL_SCANCODE_X);
		setKey("Y", SDL_SCANCODE_Y);
		setKey("Z", SDL_SCANCODE_Z);
		setKey("Q", SDL_SCANCODE_Q);
		setKey("W", SDL_SCANCODE_W);
		setKey("Left Shift", SDL_SCANCODE_LSHIFT);
		setKey("Right Shift", SDL_SCANCODE_RSHIFT);
	}
}

bool SdlKeyManager::IsKeyPressed(uint16_t key)
{
	if(_disableAllKeys || key == 0) {
		return false;
	}

	if(key >= SdlKeyManager::BaseGamepadIndex) {
		int port = (key - SdlKeyManager::BaseGamepadIndex) / 0x100;
		int button = (key - SdlKeyManager::BaseGamepadIndex) % 0x100;

		if(port < (int)_controllers.size() && _controllers[port]) {
			SDL_GameController* pad = _controllers[port];
			SDL_Joystick* js = SDL_GameControllerGetJoystick(pad);
			Uint8 hat = (js && SDL_JoystickNumHats(js) > 0) ? SDL_JoystickGetHat(js, 0) : 0;
			auto joyDpad = [&](SDL_GameControllerButton button) -> bool {
				return SDL_GameControllerGetButton(pad, button)
					|| (js && SDL_JoystickGetButton(js, button));
			};
			switch(button) {
				case 0: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_A);
				case 1: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_B);
				case 3: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_X);
				case 4: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_Y);
				case 6: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
				case 7: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
				case 10: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_BACK)
					|| SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_GUIDE);
				case 11: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_START);
				case 12: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_LEFTSTICK);
				case 13: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_RIGHTSTICK);
				case 14: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTX), true);
				case 15: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTX), false);
				case 16: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTY), true);
				case 17: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTY), false);
				case 20: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_RIGHTX), true);
				case 21: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_RIGHTX), false);
				case 22: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_RIGHTY), true);
				case 23: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_RIGHTY), false);
				case 26: return joyDpad(SDL_CONTROLLER_BUTTON_DPAD_RIGHT)
					|| (hat & SDL_HAT_RIGHT)
					|| AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTX), true);
				case 27: return joyDpad(SDL_CONTROLLER_BUTTON_DPAD_LEFT)
					|| (hat & SDL_HAT_LEFT)
					|| AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTX), false);
				case 28: return joyDpad(SDL_CONTROLLER_BUTTON_DPAD_DOWN)
					|| (hat & SDL_HAT_DOWN)
					|| AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTY), true);
				case 29: return joyDpad(SDL_CONTROLLER_BUTTON_DPAD_UP)
					|| (hat & SDL_HAT_UP)
					|| AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_LEFTY), false);
				case 42: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_TRIGGERLEFT), true);
				case 43: return AxisPast(SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_TRIGGERRIGHT), true);
			}
		}

		int joyPort = port - (int)_controllers.size();
		if(joyPort >= 0 && joyPort < (int)_joysticks.size()) {
			return IsJoystickButtonPressed(joyPort, button);
		}
		return false;
	}

	if(key < 0x205) {
		return _keyState[key] != 0;
	}
	return false;
}

bool SdlKeyManager::IsMouseButtonPressed(MouseButton button)
{
	return _keyState[SdlKeyManager::BaseMouseButtonIndex + (int)button];
}

vector<uint16_t> SdlKeyManager::GetPressedKeys()
{
	vector<uint16_t> pressedKeys;
	for(int i = 0; i < (int)_controllers.size(); i++) {
		if(!_controllers[i]) {
			continue;
		}
		for(int j = 0; j <= 54; j++) {
			uint16_t key = SdlKeyManager::BaseGamepadIndex + i * 0x100 + j;
			if(IsKeyPressed(key)) {
				pressedKeys.push_back(key);
			}
		}
	}
	for(int i = 0; i < (int)_joysticks.size(); i++) {
		if(!_joysticks[i]) {
			continue;
		}
		int port = (int)_controllers.size() + i;
		for(int j = 0; j <= 54; j++) {
			uint16_t key = SdlKeyManager::BaseGamepadIndex + port * 0x100 + j;
			if(IsKeyPressed(key)) {
				pressedKeys.push_back(key);
			}
		}
	}
	for(int i = 0; i < 0x205; i++) {
		if(_keyState[i]) {
			pressedKeys.push_back(i);
		}
	}
	return pressedKeys;
}

string SdlKeyManager::GetKeyName(uint16_t key)
{
	auto keyDef = _keyNames.find(key);
	if(keyDef != _keyNames.end()) {
		return keyDef->second;
	}
	return "";
}

uint16_t SdlKeyManager::GetKeyCode(string keyName)
{
	auto keyDef = _keyCodes.find(keyName);
	if(keyDef != _keyCodes.end()) {
		return keyDef->second;
	}
	return 0;
}

void SdlKeyManager::UpdateDevices()
{
	for(auto c : _controllers) {
		if(c) {
			SDL_GameControllerClose(c);
		}
	}
	_controllers.clear();
	for(auto j : _joysticks) {
		if(j) {
			SDL_JoystickClose(j);
		}
	}
	_joysticks.clear();

	int numJoysticks = SDL_NumJoysticks();
	_lastJoystickCount = numJoysticks;
	MessageManager::Log("[SdlKeyManager] joysticks=" + std::to_string(numJoysticks));

	for(int i = 0; i < numJoysticks; i++) {
		const char* name = SDL_JoystickNameForIndex(i);
		if(NameIsDuplicateVirtualPad(name)) {
			MessageManager::Log("[SdlKeyManager] skip duplicate " + string(name ? name : ""));
			continue;
		}
		InstallAndroidGamepadMapping(i);
		bool isController = SDL_IsGameController(i);
		MessageManager::Log("[SdlKeyManager] device " + std::to_string(i) + ": " + (name ? name : "Unknown") +
			(isController ? " (controller)" : " (joystick)"));
		if(isController) {
			SDL_GameController* controller = SDL_GameControllerOpen(i);
			if(controller) {
				_controllers.push_back(controller);
			}
		} else {
			SDL_Joystick* joystick = SDL_JoystickOpen(i);
			if(joystick) {
				_joysticks.push_back(joystick);
			}
		}
	}
}

bool SdlKeyManager::IsJoystickButtonPressed(int port, int button)
{
	if(port < 0 || port >= (int)_joysticks.size() || !_joysticks[port]) {
		return false;
	}
	SDL_Joystick* j = _joysticks[port];
	auto joyButton = [&](int sdlButton) -> bool {
		if(sdlButton < 0 || sdlButton >= SDL_JoystickNumButtons(j)) {
			return false;
		}
		return SDL_JoystickGetButton(j, sdlButton) != 0;
	};
	auto joyAxis = [&](int axis, bool positive) -> bool {
		if(axis < 0 || axis >= SDL_JoystickNumAxes(j)) {
			return false;
		}
		return AxisPast(SDL_JoystickGetAxis(j, axis), positive);
	};
	Uint8 hat = SDL_JoystickNumHats(j) > 0 ? SDL_JoystickGetHat(j, 0) : 0;

	switch(button) {
#ifdef __ANDROID__
		case 0: return joyButton(SDL_CONTROLLER_BUTTON_A);
		case 1: return joyButton(SDL_CONTROLLER_BUTTON_B);
		case 3: return joyButton(SDL_CONTROLLER_BUTTON_X);
		case 4: return joyButton(SDL_CONTROLLER_BUTTON_Y);
		case 6: return joyButton(SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
		case 7: return joyButton(SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
		case 10: return joyButton(SDL_CONTROLLER_BUTTON_BACK) || joyButton(SDL_CONTROLLER_BUTTON_GUIDE);
		case 11: return joyButton(SDL_CONTROLLER_BUTTON_START);
		case 12: return joyButton(SDL_CONTROLLER_BUTTON_LEFTSTICK);
		case 13: return joyButton(SDL_CONTROLLER_BUTTON_RIGHTSTICK);
		case 26: return joyButton(SDL_CONTROLLER_BUTTON_DPAD_RIGHT) || (hat & SDL_HAT_RIGHT) || joyAxis(0, true);
		case 27: return joyButton(SDL_CONTROLLER_BUTTON_DPAD_LEFT) || (hat & SDL_HAT_LEFT) || joyAxis(0, false);
		case 28: return joyButton(SDL_CONTROLLER_BUTTON_DPAD_DOWN) || (hat & SDL_HAT_DOWN) || joyAxis(1, true);
		case 29: return joyButton(SDL_CONTROLLER_BUTTON_DPAD_UP) || (hat & SDL_HAT_UP) || joyAxis(1, false);
		case 42: return joyButton(15) || joyAxis(4, true);
		case 43: return joyButton(16) || joyAxis(5, true);
#else
		case 0: return joyButton(0);
		case 1: return joyButton(1);
		case 3: return joyButton(3);
		case 4: return joyButton(4);
		case 6: return joyButton(6);
		case 7: return joyButton(7);
		case 10: return joyButton(10);
		case 11: return joyButton(11);
		case 12: return joyButton(13);
		case 13: return joyButton(14);
		case 26: return (hat & SDL_HAT_RIGHT) || joyAxis(0, true);
		case 27: return (hat & SDL_HAT_LEFT) || joyAxis(0, false);
		case 28: return (hat & SDL_HAT_DOWN) || joyAxis(1, true);
		case 29: return (hat & SDL_HAT_UP) || joyAxis(1, false);
		case 42: return joyAxis(4, true);
		case 43: return joyAxis(5, true);
#endif
		case 14: return joyAxis(0, true);
		case 15: return joyAxis(0, false);
		case 16: return joyAxis(1, true);
		case 17: return joyAxis(1, false);
		case 20: return joyAxis(2, true);
		case 21: return joyAxis(2, false);
		case 22: return joyAxis(3, true);
		case 23: return joyAxis(3, false);
		default: return false;
	}
}

bool SdlKeyManager::SetKeyState(uint16_t scanCode, bool state)
{
	if(scanCode < 0x205 && _keyState[scanCode] != state) {
		_keyState[scanCode] = state;
		return true;
	}
	return false;
}

void SdlKeyManager::ResetKeyState()
{
	memset(_keyState, 0, sizeof(_keyState));
}

void SdlKeyManager::SetDisabled(bool disabled)
{
	_disableAllKeys = disabled;
}
