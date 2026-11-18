#pragma once

#include <cstdint>

struct HandheldRuntimeSnapshot
{
	uint32_t FrameCount = 0;
	int32_t Mode = -1;
	int32_t Submode = -1;
	int32_t Indoor = -1;
	int32_t Area = -1;
	int32_t Room = -1;
	int32_t LinkX = -1;
	int32_t LinkY = -1;
	int32_t LinkLayer = -1;
	int32_t LinkDir = -1;
	int32_t LinkState = -1;
	int32_t LinkForm = -1;
	int32_t Health = -1;
	int32_t MaxHealth = -1;
	int32_t Magic = -1;
	int32_t Rupees = -1;
	bool Paused = false;
	char RomSha1[41] = {};
};
