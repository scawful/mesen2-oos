#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"

class DebugHud;

class WatchHud
{
private:
	SimpleLock _lock;
	string _text;

	void DrawOutlinedString(DebugHud* hud, int x, int y, const string& text, int maxWidth) const;

public:
	void SetText(string text);
	void Clear();
	string GetText();
	void Draw(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight);
};
