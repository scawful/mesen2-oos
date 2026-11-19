#include <cassert>
#include <iostream>
#include <vector>
#include "HandheldDebugOverlay.h"

using namespace HandheldOverlay;

static void TestSkipsOutsidePlayModules()
{
	std::vector<uint32_t> fb(256 * 239, 0xFF000000u);
	std::vector<uint8_t> wram(0x20000, 0);
	wram[0x0010] = 0x00; // Title screen
	Render(fb.data(), 256, 239, wram.data(), wram.size(), kMaskAll);
	for(uint32_t px : fb) {
		assert(px == 0xFF000000u);
	}
}

static void TestDrawsLinkAndSpriteHitboxes()
{
	std::vector<uint32_t> fb(256 * 239, 0xFF000000u);
	std::vector<uint8_t> wram(0x20000, 0);
	wram[0x0010] = 0x09; // Overworld
	// Camera at (100, 200), Link at (140, 250) -> screen (40, 50), layer 0 (cyan)
	wram[0x00E2] = 100; wram[0x00E8] = 200;
	wram[0x0022] = 140; wram[0x0020] = 250;
	wram[0x00EE] = 0;

	// Sprite slot 2 active ($09), ID $41 at world (180, 260 = $0104) -> screen (80, 60)
	wram[0x0DD0 + 2] = 0x09;
	wram[0x0E20 + 2] = 0x41;
	wram[0x0D10 + 2] = 180;
	wram[0x0D00 + 2] = 0x04;
	wram[0x0D20 + 2] = 0x01;

	Render(fb.data(), 256, 239, wram.data(), wram.size(), kMaskHitboxes);
	assert(fb[50 * 256 + 40] != 0xFF000000u); // Link top-left corner drawn
	assert(fb[60 * 256 + 80] != 0xFF000000u); // Sprite top-left corner drawn
	assert(fb[5 * 256 + 5] == 0xFF000000u);   // Untouched background pixel
}

static void TestUnderworldCollisionAndGrid()
{
	std::vector<uint32_t> fb(256 * 239, 0xFF000000u);
	std::vector<uint8_t> wram(0x20000, 0);
	wram[0x0010] = 0x07; // Dungeon
	wram[0x00E2] = 0; wram[0x00E8] = 0;
	wram[0x00EE] = 0; // Layer 0 -> COLMAPA ($7F2000 -> offset 0x12000)
	// Tile (2, 3) -> pixel (16..23, 24..31) set to pit $20
	wram[0x12000 + 3 * 64 + 2] = 0x20;

	Render(fb.data(), 256, 239, wram.data(), wram.size(), kMaskCollision | kMaskGrid);
	assert(fb[26 * 256 + 18] != 0xFF000000u); // Pit tile shaded
	assert(fb[0 * 256 + 0] != 0xFF000000u);   // Grid seam at (0,0) drawn
}

int main()
{
	TestSkipsOutsidePlayModules();
	TestDrawsLinkAndSpriteHitboxes();
	TestUnderworldCollisionAndGrid();
	std::cout << "handheld_debug_overlay_test OK (3 suites)\n";
	return 0;
}
