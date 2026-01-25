#include "pch.h"
#include "Shared/Video/WatchHud.h"
#include "Shared/Video/DebugHud.h"
#include "Shared/Emulator.h"
#include "Shared/SocketServer.h"
#include "Shared/DebuggerRequest.h"
#include "Debugger/Debugger.h"
#include "Debugger/MemoryDumper.h"

WatchHud::WatchHud(Emulator* emu)
{
	_emu = emu;
}

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

void WatchHud::DrawCollisionOverlay(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight)
{
	if(!SocketServer::IsCollisionOverlayEnabled()) {
		return;
	}

	if(!_emu || !_emu->IsRunning()) {
		return;
	}

	auto dbg = _emu->GetDebugger(false);
	if(!dbg.GetDebugger()) {
		return;
	}

	Debugger* debugger = dbg.GetDebugger();
	MemoryDumper* dumper = debugger->GetMemoryDumper();

	string mode = SocketServer::GetCollisionOverlayMode();
	const vector<uint8_t>& highlightTiles = SocketServer::GetCollisionHighlightTiles();

	// ALTTP collision map addresses
	// COLMAPA: $7F2000 (primary)
	// COLMAPB: $7F6000 (secondary)
	uint32_t colmapABase = 0x7F2000;
	uint32_t colmapBBase = 0x7F6000;

	// Read camera/scroll position from WRAM to determine visible area
	// ALTTP stores camera at $E2/$E4 (16-bit X/Y)
	uint8_t camXLo = dumper->GetMemoryValue(MemoryType::SnesMemory, 0x7E00E2);
	uint8_t camXHi = dumper->GetMemoryValue(MemoryType::SnesMemory, 0x7E00E3);
	uint8_t camYLo = dumper->GetMemoryValue(MemoryType::SnesMemory, 0x7E00E4);
	uint8_t camYHi = dumper->GetMemoryValue(MemoryType::SnesMemory, 0x7E00E5);
	int camX = camXLo | (camXHi << 8);
	int camY = camYLo | (camYHi << 8);

	// SNES screen is 256x224
	const int snesWidth = 256;
	const int snesHeight = 224;
	const int tileSize = 8;  // 8x8 pixel tiles

	// Calculate scale factors
	float scaleX = (float)screenWidth / snesWidth;
	float scaleY = (float)screenHeight / snesHeight;

	// Determine which tiles are visible on screen
	int startTileX = camX / tileSize;
	int startTileY = camY / tileSize;
	int tilesAcross = (snesWidth / tileSize) + 1;  // +1 for partial tiles
	int tilesDown = (snesHeight / tileSize) + 1;

	// Offset within the starting tile (for smooth scrolling alignment)
	int offsetX = camX % tileSize;
	int offsetY = camY % tileSize;

	// Draw collision map overlay
	auto drawColmap = [&](uint32_t baseAddr, uint32_t overlayColor) {
		for(int ty = 0; ty < tilesDown; ty++) {
			for(int tx = 0; tx < tilesAcross; tx++) {
				int mapX = startTileX + tx;
				int mapY = startTileY + ty;

				// Wrap around the 64x64 collision map
				mapX = mapX & 63;
				mapY = mapY & 63;

				uint32_t addr = baseAddr + (mapY * 64) + mapX;
				uint8_t tileType = dumper->GetMemoryValue(MemoryType::SnesMemory, addr);

				// Skip empty tiles (type 0)
				if(tileType == 0) {
					continue;
				}

				// Calculate screen position
				int screenX = (tx * tileSize - offsetX) * scaleX;
				int screenY = (ty * tileSize - offsetY) * scaleY;
				int tileW = (int)(tileSize * scaleX);
				int tileH = (int)(tileSize * scaleY);

				// Skip if off screen
				if(screenX + tileW < 0 || screenY + tileH < 0) {
					continue;
				}
				if(screenX >= (int)screenWidth || screenY >= (int)screenHeight) {
					continue;
				}

				// Determine color based on tile type
				uint32_t color = overlayColor;

				// Check if this tile type should be highlighted
				bool isHighlighted = false;
				for(uint8_t ht : highlightTiles) {
					if(tileType == ht) {
						isHighlighted = true;
						break;
					}
				}

				if(isHighlighted) {
					// Highlighted tiles get bright color (water = blue, etc.)
					if(tileType == 0x09 || tileType == 0x0A || tileType == 0x1A) {
						color = 0x800080FF;  // Semi-transparent blue for water
					} else {
						color = 0x80FF00FF;  // Semi-transparent magenta for other highlighted
					}
				} else {
					// Color based on collision type
					if(tileType >= 0x08 && tileType <= 0x0F) {
						// Deep water / swim tiles
						color = 0x400000FF;  // Dark blue
					} else if(tileType >= 0x40 && tileType <= 0x4F) {
						// Solid / walls
						color = 0x40FF0000;  // Dark red
					} else if(tileType >= 0x20 && tileType <= 0x2F) {
						// Ledges / cliffs
						color = 0x40FFFF00;  // Dark yellow
					} else if(tileType >= 0x60 && tileType <= 0x6F) {
						// Stairs / entrances
						color = 0x4000FF00;  // Dark green
					} else {
						// Other types - gray
						color = 0x30808080;  // Semi-transparent gray
					}
				}

				// Draw filled rectangle for this tile
				hud->DrawRectangle(screenX, screenY, tileW, tileH, color, true, 1, -1);
			}
		}
	};

	// Draw based on mode
	if(mode == "A" || mode == "both") {
		drawColmap(colmapABase, 0x40FF8000);  // Orange tint for A
	}
	if(mode == "B" || mode == "both") {
		drawColmap(colmapBBase, 0x4000FFFF);  // Cyan tint for B
	}

	// Draw legend
	DrawOutlinedString(hud, 4, screenHeight - 20,
		"Collision: " + mode + " (Blue=Water, Red=Solid)", (int)screenWidth - 8);
}

void WatchHud::Draw(DebugHud* hud, uint32_t screenWidth, uint32_t screenHeight)
{
	// Draw collision overlay first (underneath text)
	DrawCollisionOverlay(hud, screenWidth, screenHeight);

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
