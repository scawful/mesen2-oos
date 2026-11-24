#include "SdlMouseManager.h"

SystemMouseState SdlMouseManager::GetSystemMouseState(void*)
{
	return SystemMouseState {};
}

bool SdlMouseManager::CaptureMouse(int32_t, int32_t, int32_t, int32_t, void*)
{
	return false;
}

void SdlMouseManager::ReleaseMouse()
{
}

void SdlMouseManager::SetSystemMousePosition(int32_t, int32_t)
{
}

void SdlMouseManager::SetCursorImage(CursorImage)
{
}

double SdlMouseManager::GetPixelScale()
{
	return 1.0;
}
