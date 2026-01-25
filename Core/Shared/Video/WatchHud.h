#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"

class DebugHud;
class Emulator;

class WatchHud
{
private:
	Emulator* _emu;
	SimpleLock _lock;
	string _text;
	string _dataJson;

	void DrawOutlinedString(DebugHud* hud, int x, int y, const string& text, int maxWidth) const;
	void DrawCollisionOverlay(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight);

public:
	WatchHud(Emulator* emu);

	void SetText(string text);
	void Clear();
	string GetText();
	void SetData(string data);
	string GetData();
	void ClearData();
	void Draw(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight);
};
