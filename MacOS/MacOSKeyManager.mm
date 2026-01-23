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
#undef Debugger

MacOSKeyManager::MacOSKeyManager(Emulator* emu)
{
	_emu = emu;

	ResetKeyState();

	_keyDefinitions = KeyDefinition::GetSharedKeyDefinitions();

	for(KeyDefinition &keyDef : _keyDefinitions) {
		_keyNames[keyDef.keyCode] = keyDef.name;
		_keyCodes[keyDef.name] = keyDef.keyCode;
	}

	_disableAllKeys = false;

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
	//TODO: NOT IMPLEMENTED YET
	//Only needed to detect poll controller input
}

bool MacOSKeyManager::IsKeyPressed(uint16_t key)
{
	if(_disableAllKeys || key == 0) {
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
	//TODO: NOT IMPLEMENTED YET
	//Only needed to detect newly plugged in devices
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
