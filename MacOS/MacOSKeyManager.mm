#import <Foundation/Foundation.h>
#import <Cocoa/Cocoa.h>

#include <algorithm>
#include "MacOSKeyManager.h"
//The MacOS SDK defines a global function 'Debugger', colliding with Mesen's Debugger class
//Redefine it temporarily so the headers don't cause compilation errors due to this
#define Debugger MesenDebugger
#include "Shared/Emulator.h"
#include "Shared/EmuSettings.h"
#include "Shared/KeyDefinitions.h"
#include "Shared/SettingTypes.h"
#include "Shared/MessageManager.h"
#undef Debugger

static void LoadDefaultControllerMappings()
{
	// 8BitDo SN30 Pro / SN30 Pro+ mappings for macOS (USB and Bluetooth)
	const char* mappings[] = {
		"03000000c82d00000160000001000000,8BitDo SN30 Pro,a:b1,b:b0,back:b10,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,leftshoulder:b6,leftstick:b13,lefttrigger:a4,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,righttrigger:a5,rightx:a2,righty:a3,start:b11,x:b4,y:b3,platform:Mac OS X,",
		"03000000c82d00000161000000010000,8BitDo SN30 Pro,a:b1,b:b0,back:b10,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b2,leftshoulder:b6,leftstick:b13,lefttrigger:b8,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,righttrigger:b9,rightx:a2,righty:a3,start:b11,x:b4,y:b3,platform:Mac OS X,",
		"03000000c82d00000260000001000000,8BitDo SN30 Pro Plus,a:b1,b:b0,back:b10,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b2,leftshoulder:b6,leftstick:b13,lefttrigger:b8,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,righttrigger:b9,rightx:a2,righty:a3,start:b11,x:b4,y:b3,platform:Mac OS X,",
		"03000000c82d00000261000000010000,8BitDo SN30 Pro Plus,a:b1,b:b0,back:b10,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b2,leftshoulder:b6,leftstick:b13,lefttrigger:b8,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,righttrigger:b9,rightx:a2,righty:a3,start:b11,x:b4,y:b3,platform:Mac OS X,",
	};
	for(size_t i = 0; i < sizeof(mappings) / sizeof(mappings[0]); i++) {
		SDL_GameControllerAddMapping(mappings[i]);
	}
}

MacOSKeyManager::MacOSKeyManager(Emulator* emu)
{
	_emu = emu;

	ResetKeyState();

	_keyDefinitions = KeyDefinition::GetSharedKeyDefinitions();
	
	// Register Gamepad buttons
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
	
	// Register 4 players worth of gamepads (SDL typically supports many, but Mesen usually maps 4)
	for(int i = 0; i < 4; i++) {
		for(int j = 0; j < (int)buttonNames.size(); j++) {
			_keyDefinitions.push_back({ "Pad" + std::to_string(i + 1) + " " + buttonNames[j], (uint32_t)(MacOSKeyManager::BaseGamepadIndex + i * 0x100 + j) });
		}
	}

	for(KeyDefinition &keyDef : _keyDefinitions) {
		_keyNames[keyDef.keyCode] = keyDef.name;
		_keyCodes[keyDef.name] = keyDef.keyCode;
	}

	_disableAllKeys = false;
	
	// Initialize SDL GameController subsystem
	int rc = SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER | SDL_INIT_JOYSTICK);
	MessageManager::Log("[MacOSKeyManager] SDL_InitSubSystem returned: " + std::to_string(rc));
	if (rc != 0) {
		MessageManager::Log("[MacOSKeyManager] SDL Error: " + string(SDL_GetError()));
	}
	LoadDefaultControllerMappings();
	UpdateDevices();

	NSEventMask eventMask = NSEventMaskKeyDown | NSEventMaskKeyUp | NSEventMaskFlagsChanged;

	_eventMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:eventMask handler:^ NSEvent* (NSEvent* event) {
		if(_emu->GetSettings()->CheckFlag(EmulationFlags::InBackground)) {
			//Allow UI to handle key-events when main window is not in focus
			return event;
		}

		if([event type] == NSEventTypeKeyDown && ([event modifierFlags] & NSEventModifierFlagCommand) != 0) {
			//Pass through command-based keydown events so cmd+Q etc still works
			return event;
		}

		if([event type] == NSEventTypeFlagsChanged) {
			HandleModifiers((uint32_t) [event modifierFlags]);
		} else {
			uint16_t mappedKeyCode = [event keyCode] >= 128 ? 0 : _keyCodeMap[[event keyCode]];
			_keyState[mappedKeyCode] = ([event type] == NSEventTypeKeyDown);
		}

		return nil;
	}];
}

MacOSKeyManager::~MacOSKeyManager()
{
	[NSEvent removeMonitor:(id) _eventMonitor];
	
	for(auto controller : _controllers) {
		SDL_GameControllerClose(controller);
	}
	for(auto joystick : _joysticks) {
		if(joystick) SDL_JoystickClose(joystick);
	}

	// The UI creates this manager on a worker thread and destroys it on the UI
	// thread. SDL2-compat can block in IOHIDManagerUnscheduleFromRunLoop when
	// joystick shutdown runs on a different run loop than initialization.
	// This manager is process-lifetime, so leave subsystem cleanup to the OS.
}

void MacOSKeyManager::HandleModifiers(uint32_t flags)
{
	bool leftShift = (flags & NX_DEVICELSHIFTKEYMASK) != 0;
	bool rightShift = (flags & NX_DEVICERSHIFTKEYMASK) != 0;
	bool leftCtrl = (flags & NX_DEVICELCTLKEYMASK) != 0;
	bool rightCtrl = (flags & NX_DEVICERCTLKEYMASK) != 0;
	bool leftAlt = (flags & NX_DEVICELALTKEYMASK) != 0;
	bool rightAlt = (flags & NX_DEVICERALTKEYMASK) != 0;
	bool leftCmd = (flags & NX_DEVICELCMDKEYMASK) != 0;
	bool rightCmd = (flags & NX_DEVICERCMDKEYMASK) != 0;

	//Fallback when device-specific flags are not exposed by the OS.
	if(!(leftShift || rightShift) && (flags & NSEventModifierFlagShift) != 0) {
		leftShift = true;
	}
	if(!(leftCtrl || rightCtrl) && (flags & NSEventModifierFlagControl) != 0) {
		leftCtrl = true;
	}
	if(!(leftAlt || rightAlt) && (flags & NSEventModifierFlagOption) != 0) {
		leftAlt = true;
	}
	if(!(leftCmd || rightCmd) && (flags & NSEventModifierFlagCommand) != 0) {
		leftCmd = true;
	}

	_keyState[116] = leftShift; //Left shift
	_keyState[117] = rightShift; //Right shift
	_keyState[118] = leftCtrl; //Left ctrl
	_keyState[119] = rightCtrl; //Right ctrl
	_keyState[120] = leftAlt; //Left alt/option
	_keyState[121] = rightAlt; //Right alt/option
	_keyState[70] = leftCmd; //Left cmd
	_keyState[71] = rightCmd; //Right cmd
}

void MacOSKeyManager::RefreshState()
{
	SDL_Event event;
	while (SDL_PollEvent(&event)) {
		if (event.type == SDL_CONTROLLERDEVICEADDED || event.type == SDL_CONTROLLERDEVICEREMOVED ||
		    event.type == SDL_JOYDEVICEADDED || event.type == SDL_JOYDEVICEREMOVED) {
			MessageManager::Log("[MacOSKeyManager] Controller/joystick device change detected.");
			UpdateDevices();
		}
	}
	SDL_GameControllerUpdate();
	if (!_joysticks.empty()) {
		SDL_JoystickUpdate();  // Required for raw joystick state
	}
	
}

bool MacOSKeyManager::IsKeyPressed(uint16_t key)
{
	if(_disableAllKeys || key == 0) {
		return false;
	}
	
	if(key >= MacOSKeyManager::BaseGamepadIndex) {
		int port = (key - MacOSKeyManager::BaseGamepadIndex) / 0x100;
		int button = (key - MacOSKeyManager::BaseGamepadIndex) % 0x100;
		
		// Check Game Controllers first
		if(port < (int)_controllers.size() && _controllers[port]) {
			// Map internal button index to SDL values
			// This mapping needs to match what we put in buttonNames
			// "A", "B", "C", "X", "Y", "Z", "L1", "R1", "L2", "R2", "Select", "Start", "L3", "R3"
			switch(button) {
				case 0: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_A);
				case 1: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_B);
				// C ??
				case 3: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_X);
				case 4: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_Y);
				// Z ??
				case 6: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
				case 7: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
				// L2/R2 might be axes or buttons depending on controller, SDL usually has axis
				case 10: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_BACK);
				case 11: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_START);
				case 12: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_LEFTSTICK);
				case 13: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_RIGHTSTICK);
				
				// Axes as buttons
				// "X+", "X-", "Y+", "Y-", "Z+", "Z-" ...
				// 14 = X+ (Left Stick Right)
				case 14: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_LEFTX) > 16000;
				case 15: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_LEFTX) < -16000;
				case 16: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_LEFTY) > 16000; // Down in SDL?
				case 17: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_LEFTY) < -16000; // Up in SDL?
				
				// Right Stick
				case 20: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_RIGHTX) > 16000;
				case 21: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_RIGHTX) < -16000;
				case 22: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_RIGHTY) > 16000;
				case 23: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_RIGHTY) < -16000;
				
				// D-Pad
				case 26: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_DPAD_RIGHT);
				case 27: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_DPAD_LEFT);
				case 28: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_DPAD_DOWN);
				case 29: return SDL_GameControllerGetButton(_controllers[port], SDL_CONTROLLER_BUTTON_DPAD_UP);
				
				// Triggers
				case 42: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 16000;
				case 43: return SDL_GameControllerGetAxis(_controllers[port], SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 16000;
			}
		}
		// Fallback: check joysticks (port offset by number of controllers)
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

bool MacOSKeyManager::IsMouseButtonPressed(MouseButton button)
{
	return _keyState[MacOSKeyManager::BaseMouseButtonIndex + (int)button];
}

vector<uint16_t> MacOSKeyManager::GetPressedKeys()
{
	vector<uint16_t> pressedKeys;
	
	// Check Game Controllers
	for(int i = 0; i < (int)_controllers.size(); i++) {
		if(!_controllers[i]) continue;
		for(int j = 0; j <= 54; j++) {
			uint16_t key = MacOSKeyManager::BaseGamepadIndex + i * 0x100 + j;
			if(IsKeyPressed(key)) pressedKeys.push_back(key);
		}
	}
	// Check fallback joysticks
	for(int i = 0; i < (int)_joysticks.size(); i++) {
		if(!_joysticks[i]) continue;
		int port = (int)_controllers.size() + i;
		for(int j = 0; j <= 54; j++) {
			uint16_t key = MacOSKeyManager::BaseGamepadIndex + port * 0x100 + j;
			if(IsKeyPressed(key)) pressedKeys.push_back(key);
		}
	}

	for(int i = 0; i < 0x205; i++) {
		if(_keyState[i]) {
			pressedKeys.push_back(i);
		}
	}
	return pressedKeys;
}

string MacOSKeyManager::GetKeyName(uint16_t key)
{
	auto keyDef = _keyNames.find(key);
	if(keyDef != _keyNames.end()) {
		return keyDef->second;
	}
	return "";
}

uint16_t MacOSKeyManager::GetKeyCode(string keyName)
{
	auto keyDef = _keyCodes.find(keyName);
	if(keyDef != _keyCodes.end()) {
		return keyDef->second;
	}
	return 0;
}

void MacOSKeyManager::UpdateDevices()
{
	// Close existing controllers and joysticks
	for(auto c : _controllers) {
		if(c) SDL_GameControllerClose(c);
	}
	_controllers.clear();
	for(auto j : _joysticks) {
		if(j) SDL_JoystickClose(j);
	}
	_joysticks.clear();
	
	int numJoysticks = SDL_NumJoysticks();
	MessageManager::Log("[MacOSKeyManager] Total joysticks found: " + std::to_string(numJoysticks));
	
	for(int i = 0; i < numJoysticks; i++) {
		const char* name = SDL_JoystickNameForIndex(i);
		bool isController = SDL_IsGameController(i);
		MessageManager::Log("[MacOSKeyManager] Device " + std::to_string(i) + ": " + (name ? name : "Unknown") + " (IsController: " + (isController ? "Yes" : "No") + ")");
		
		if(isController) {
			SDL_GameController* controller = SDL_GameControllerOpen(i);
			if(controller) {
				_controllers.push_back(controller);
				MessageManager::Log("[MacOSKeyManager] Successfully opened controller: " + string(SDL_GameControllerName(controller)));
			} else {
				MessageManager::Log("[MacOSKeyManager] Failed to open controller " + std::to_string(i) + ": " + string(SDL_GetError()));
			}
		} else {
			// Fallback: open as raw joystick for devices SDL doesn't recognize as Game Controllers
			// (e.g. 8BitDo in certain modes, generic HID gamepads)
			SDL_Joystick* joystick = SDL_JoystickOpen(i);
			if(joystick) {
				_joysticks.push_back(joystick);
				MessageManager::Log("[MacOSKeyManager] Opened as joystick (fallback): " + string(SDL_JoystickName(joystick)));
			}
		}
	}
}

bool MacOSKeyManager::IsJoystickButtonPressed(int port, int button)
{
	if(port < 0 || port >= (int)_joysticks.size() || !_joysticks[port]) return false;
	SDL_Joystick* j = _joysticks[port];
	
	// Map our virtual button index to SDL_Joystick buttons/axes
	// Layout matches common D-input / 8BitDo: A=0,B=1,X=3,Y=4, L1=6,R1=7, Select=10,Start=11, L3=12,R3=13
	// Axes: 14-17 left stick, 20-23 right stick, 26-29 d-pad (hat), 42-43 triggers
	switch(button) {
		case 0: return SDL_JoystickGetButton(j, 0);  // A
		case 1: return SDL_JoystickGetButton(j, 1);  // B
		case 3: return SDL_JoystickGetButton(j, 3);  // X
		case 4: return SDL_JoystickGetButton(j, 4);  // Y
		case 6: return SDL_JoystickGetButton(j, 6);  // L1
		case 7: return SDL_JoystickGetButton(j, 7);  // R1
		case 10: return SDL_JoystickGetButton(j, 10); // Select
		case 11: return SDL_JoystickGetButton(j, 11); // Start
		case 12: return SDL_JoystickGetButton(j, 13); // L3
		case 13: return SDL_JoystickGetButton(j, 14); // R3
		case 14: return SDL_JoystickGetAxis(j, 0) > 16000;  // Left stick right
		case 15: return SDL_JoystickGetAxis(j, 0) < -16000; // Left stick left
		case 16: return SDL_JoystickGetAxis(j, 1) > 16000;  // Left stick down
		case 17: return SDL_JoystickGetAxis(j, 1) < -16000; // Left stick up
		case 20: return SDL_JoystickGetAxis(j, 2) > 16000;  // Right stick right
		case 21: return SDL_JoystickGetAxis(j, 2) < -16000;
		case 22: return SDL_JoystickGetAxis(j, 3) > 16000;  // Right stick down
		case 23: return SDL_JoystickGetAxis(j, 3) < -16000;
		case 26: case 27: case 28: case 29: {  // D-pad from hat
			Uint8 hat = SDL_JoystickGetHat(j, 0);
			if(button == 26) return (hat & SDL_HAT_RIGHT) != 0;
			if(button == 27) return (hat & SDL_HAT_LEFT) != 0;
			if(button == 28) return (hat & SDL_HAT_DOWN) != 0;
			if(button == 29) return (hat & SDL_HAT_UP) != 0;
			return false;
		}
		case 42: return SDL_JoystickNumAxes(j) > 4 && SDL_JoystickGetAxis(j, 4) > 16000; // L2
		case 43: return SDL_JoystickNumAxes(j) > 5 && SDL_JoystickGetAxis(j, 5) > 16000; // R2
		default: return false;
	}
}

bool MacOSKeyManager::SetKeyState(uint16_t scanCode, bool state)
{
	if(scanCode < 0x205 && _keyState[scanCode] != state) {
		_keyState[scanCode] = state;
		return true;
	}
	return false;
}

void MacOSKeyManager::ResetKeyState()
{
	memset(_keyState, 0, sizeof(_keyState));
}

void MacOSKeyManager::SetDisabled(bool disabled)
{
	_disableAllKeys = disabled;
}
