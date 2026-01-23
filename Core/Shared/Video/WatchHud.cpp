#include "pch.h"
#include "Shared/Video/WatchHud.h"
#include "Shared/Video/DebugHud.h"

void WatchHud::SetText(string text)
{
	auto lock = _lock.AcquireSafe();
	_text = std::move(text);
}

void WatchHud::Clear()
{
	auto lock = _lock.AcquireSafe();
	_text.clear();
}

string WatchHud::GetText()
{
	auto lock = _lock.AcquireSafe();
	return _text;
}

void WatchHud::SetData(string data)
{
	auto lock = _lock.AcquireSafe();
	_dataJson = std::move(data);
}

string WatchHud::GetData()
{
	auto lock = _lock.AcquireSafe();
	return _dataJson;
}

void WatchHud::ClearData()
{
	auto lock = _lock.AcquireSafe();
	_dataJson.clear();
}

void WatchHud::DrawOutlinedString(DebugHud* hud, int x, int y, const string& text, int maxWidth) const
{
	for(int offsetX = -1; offsetX <= 1; offsetX++) {
		for(int offsetY = -1; offsetY <= 1; offsetY++) {
			if(offsetX == 0 && offsetY == 0) {
				continue;
			}
			hud->DrawString(x + offsetX, y + offsetY, text, 0xFF000000, 0, 1, -1, maxWidth);
		}
	}
	hud->DrawString(x, y, text, 0xFFFFFFFF, 0, 1, -1, maxWidth);
}

void WatchHud::Draw(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight)
{
	(void)screenHeight;

	string text;
	{
		auto lock = _lock.AcquireSafe();
		text = _text;
	}

	if(text.empty()) {
		return;
	}

	int x = 4;
	int y = 10;
	int maxWidth = (int)screenWidth - x - 4;
	if(maxWidth <= 0) {
		return;
	}

	DrawOutlinedString(hud, x, y, text, maxWidth);
}
