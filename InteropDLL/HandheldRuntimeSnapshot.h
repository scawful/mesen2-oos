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
	bool Paused = false;
	char RomSha1[41] = {};
};
