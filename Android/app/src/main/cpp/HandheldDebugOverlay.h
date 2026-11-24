#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace HandheldOverlay {

constexpr uint32_t kMaskHitboxes = 0x01u;
constexpr uint32_t kMaskCollision = 0x02u;
constexpr uint32_t kMaskGrid = 0x04u;
constexpr uint32_t kMaskAll = kMaskHitboxes | kMaskCollision | kMaskGrid;

namespace detail {

inline uint16_t ReadU16(const uint8_t* wram, size_t wramSize, size_t offset)
{
	if(offset + 1 >= wramSize) {
		return 0;
	}
	return static_cast<uint16_t>(wram[offset]) | (static_cast<uint16_t>(wram[offset + 1]) << 8);
}

inline uint32_t BlendPixel(uint32_t dst, uint32_t rgb, uint32_t alpha)
{
	const uint32_t inv = 256u - alpha;
	const uint32_t dr = (dst >> 16) & 0xFFu;
	const uint32_t dg = (dst >> 8) & 0xFFu;
	const uint32_t db = dst & 0xFFu;
	const uint32_t sr = (rgb >> 16) & 0xFFu;
	const uint32_t sg = (rgb >> 8) & 0xFFu;
	const uint32_t sb = rgb & 0xFFu;
	const uint32_t r = (dr * inv + sr * alpha) >> 8;
	const uint32_t g = (dg * inv + sg * alpha) >> 8;
	const uint32_t b = (db * inv + sb * alpha) >> 8;
	return 0xFF000000u | (r << 16) | (g << 8) | b;
}

inline bool IsBlackRow(const uint32_t* buf, uint32_t w, uint32_t rowY)
{
	const uint32_t* row = buf + static_cast<size_t>(rowY) * w;
	for(uint32_t x = 0; x < w; ++x) {
		if((row[x] & 0x00FFFFFFu) != 0u) {
			return false;
		}
	}
	return true;
}

inline void PlotScaled(uint32_t* buf, uint32_t w, uint32_t h, uint32_t sx, uint32_t sy, int minY, int maxY, int x, int y, uint32_t rgb, uint32_t alpha)
{
	if(x < 0 || y < minY || y >= maxY) {
		return;
	}
	const uint32_t px0 = static_cast<uint32_t>(x) * sx;
	const uint32_t py0 = static_cast<uint32_t>(y) * sy;
	if(px0 >= w || py0 >= h) {
		return;
	}
	const uint32_t px1 = std::min(w, px0 + sx);
	const uint32_t py1 = std::min(h, py0 + sy);
	for(uint32_t py = py0; py < py1; ++py) {
		uint32_t* row = buf + static_cast<size_t>(py) * w;
		for(uint32_t px = px0; px < px1; ++px) {
			row[px] = (alpha >= 255u) ? (0xFF000000u | (rgb & 0xFFFFFFu)) : BlendPixel(row[px], rgb, alpha);
		}
	}
}

inline void FillRect(uint32_t* buf, uint32_t w, uint32_t h, uint32_t sx, uint32_t sy, int minY, int maxY, int x0, int y0, int rw, int rh, uint32_t rgb, uint32_t alpha)
{
	const int baseW = static_cast<int>(w / sx);
	const int cx0 = std::max(0, x0);
	const int cy0 = std::max(minY, y0);
	const int cx1 = std::min(baseW, x0 + rw);
	const int cy1 = std::min(maxY, y0 + rh);
	for(int y = cy0; y < cy1; ++y) {
		for(int x = cx0; x < cx1; ++x) {
			PlotScaled(buf, w, h, sx, sy, minY, maxY, x, y, rgb, alpha);
		}
	}
}

inline void DrawBox(uint32_t* buf, uint32_t w, uint32_t h, uint32_t sx, uint32_t sy, int minY, int maxY, int x0, int y0, int rw, int rh, uint32_t rgb, uint32_t alpha = 230u)
{
	if(rw <= 0 || rh <= 0) {
		return;
	}
	for(int x = x0; x < x0 + rw; ++x) {
		PlotScaled(buf, w, h, sx, sy, minY, maxY, x, y0, rgb, alpha);
		PlotScaled(buf, w, h, sx, sy, minY, maxY, x, y0 + rh - 1, rgb, alpha);
	}
	for(int y = y0 + 1; y < y0 + rh - 1; ++y) {
		PlotScaled(buf, w, h, sx, sy, minY, maxY, x0, y, rgb, alpha);
		PlotScaled(buf, w, h, sx, sy, minY, maxY, x0 + rw - 1, y, rgb, alpha);
	}
}

// Minimal 3x5 bitmap font for 0-9 and A-F hex sprite labels (each nibble is 5 rows of 3 bits).
inline uint16_t HexGlyph(uint8_t nibble)
{
	static constexpr uint16_t kGlyphs[16] = {
		0b111'101'101'101'111, // 0
		0b010'110'010'010'111, // 1
		0b111'001'111'100'111, // 2
		0b111'001'111'001'111, // 3
		0b101'101'111'001'001, // 4
		0b111'100'111'001'111, // 5
		0b111'100'111'101'111, // 6
		0b111'001'001'010'010, // 7
		0b111'101'111'101'111, // 8
		0b111'101'111'001'111, // 9
		0b010'101'111'101'101, // A
		0b110'101'110'101'110, // B
		0b111'100'100'100'111, // C
		0b110'101'101'101'110, // D
		0b111'100'110'100'111, // E
		0b111'100'110'100'100, // F
	};
	return kGlyphs[nibble & 0x0Fu];
}

inline void DrawHexByte(uint32_t* buf, uint32_t w, uint32_t h, uint32_t sx, uint32_t sy, int minY, int maxY, int x, int y, uint8_t value, uint32_t rgb)
{
	FillRect(buf, w, h, sx, sy, minY, maxY, x - 1, y - 1, 9, 7, 0x000000u, 170u);
	const uint8_t digits[2] = { static_cast<uint8_t>((value >> 4) & 0x0Fu), static_cast<uint8_t>(value & 0x0Fu) };
	for(int d = 0; d < 2; ++d) {
		const uint16_t bits = HexGlyph(digits[d]);
		for(int row = 0; row < 5; ++row) {
			const int shift = (4 - row) * 3;
			for(int col = 0; col < 3; ++col) {
				if((bits >> (shift + (2 - col))) & 1u) {
					PlotScaled(buf, w, h, sx, sy, minY, maxY, x + d * 4 + col, y + row, rgb, 255u);
				}
			}
		}
	}
}

inline bool CollisionColor(uint8_t tile, uint32_t& outRgb, uint32_t& outAlpha)
{
	if(tile == 0x00) {
		return false;
	}
	if(tile <= 0x07) {
		outRgb = 0xFF4040u; // Solid wall / block / ledge
		outAlpha = 76u;
		return true;
	}
	if(tile == 0x08 || tile == 0x09) {
		outRgb = 0x3399FFu; // Deep / shallow water
		outAlpha = 88u;
		return true;
	}
	if(tile == 0x20 || tile == 0x21) {
		outRgb = 0xB833FFu; // Pit
		outAlpha = 104u;
		return true;
	}
	if((tile >= 0x10 && tile <= 0x1F) || (tile >= 0x22 && tile <= 0x27)) {
		outRgb = 0xFFD840u; // Stairs / ladders / slopes / layer swaps
		outAlpha = 84u;
		return true;
	}
	if(tile >= 0x60 && tile <= 0x7F) {
		outRgb = 0x33FF88u; // Doors / warps / switches / chests
		outAlpha = 96u;
		return true;
	}
	outRgb = 0x66D0D0u; // Other special tile attribute
	outAlpha = 64u;
	return true;
}

} // namespace detail

/**
 * Zero-debugger WRAM debug overlay rendered directly onto the SNES frame buffer.
 * Reads standard ALTTP/OoS WRAM mirrors ($7E0010, $7E0020-$7E0023, $7E00E2/$7E00E8,
 * $7E00EE, $7E0D00-$7E0F60, and underworld COLMAPA/COLMAPB at $7F2000/$7F3000).
 */
inline void Render(uint32_t* argb, uint32_t width, uint32_t height, const uint8_t* wram, size_t wramSize, uint32_t mask)
{
	if(!argb || !wram || width < 256 || height < 224 || wramSize < 0x1000 || (mask & kMaskAll) == 0) {
		return;
	}

	const uint8_t mode = wram[0x0010];
	if(mode != 0x07 && mode != 0x09 && mode != 0x0B) {
		return;
	}

	const uint32_t sx = (width >= 512) ? (width / 256u) : 1u;
	const uint32_t sy = (height >= 448) ? (height / 224u) : 1u;
	const int baseW = static_cast<int>(width / sx);
	const int baseH = static_cast<int>(height / sy);
	// SnesPpu centers standard 224-line frames inside the 239-line output buffer
	// at rows [7, 231) (scanline + 6 for 1-based scanlines). BG scrolling displays
	// BGnVOFS + 1 on scanline 1, putting BG tiles at screenY + 6 and sprites at +7.
	const bool centered224 = (baseH == 239) &&
		detail::IsBlackRow(argb, width, 0) &&
		detail::IsBlackRow(argb, width, 6u * sy);
	const int viewTop = centered224 ? 7 : 0;
	const int viewBottom = centered224 ? 231 : baseH;
	const int bgYOff = centered224 ? 6 : 0;
	const int sprYOff = centered224 ? 7 : 0;

	const int camX = static_cast<int>(detail::ReadU16(wram, wramSize, 0x00E2));
	const int camY = static_cast<int>(detail::ReadU16(wram, wramSize, 0x00E8));
	const uint8_t linkLayer = wram[0x00EE];

	// 1. Underworld collision map (COLMAPA $7F2000 for layer 0, COLMAPB $7F3000 for layer 1).
	if((mask & kMaskCollision) != 0 && mode == 0x07) {
		const size_t mapBase = (linkLayer != 0) ? 0x13000u : 0x12000u;
		if(mapBase + 0x1000u <= wramSize) {
			const uint8_t* colMap = wram + mapBase;
			const int startTileX = camX >> 3;
			const int startTileY = camY >> 3;
			const int tilesX = (baseW / 8) + 2;
			const int tilesY = ((viewBottom - viewTop) / 8) + 3;
			for(int ty = 0; ty < tilesY; ++ty) {
				const int worldTileY = (startTileY + ty) & 63;
				const int screenY = ((startTileY + ty) * 8) - camY + bgYOff;
				for(int tx = 0; tx < tilesX; ++tx) {
					const int worldTileX = (startTileX + tx) & 63;
					const uint8_t attr = colMap[worldTileY * 64 + worldTileX];
					uint32_t rgb = 0;
					uint32_t alpha = 0;
					if(detail::CollisionColor(attr, rgb, alpha)) {
						const int screenX = ((startTileX + tx) * 8) - camX;
						detail::FillRect(argb, width, height, sx, sy, viewTop, viewBottom, screenX, screenY, 8, 8, rgb, alpha);
					}
				}
			}
		}
	}

	// 2. Scroll-aligned 16x16 tile grid + 256px screen seam markers.
	if((mask & kMaskGrid) != 0) {
		const int offX = (16 - (camX & 15)) & 15;
		const int offY = (16 - (camY & 15)) & 15;
		for(int x = offX; x < baseW; x += 16) {
			const int worldX = camX + x;
			const bool seam = (worldX & 0xFF) == 0;
			const uint32_t color = seam ? 0xFF55FFu : 0xFFFFFFu;
			const uint32_t alpha = seam ? 160u : 42u;
			for(int y = viewTop; y < viewBottom; ++y) {
				detail::PlotScaled(argb, width, height, sx, sy, viewTop, viewBottom, x, y, color, alpha);
			}
		}
		for(int relY = offY; relY + bgYOff < viewBottom; relY += 16) {
			const int y = relY + bgYOff;
			if(y < viewTop) {
				continue;
			}
			const int worldY = camY + relY;
			const bool seam = (worldY & 0xFF) == 0;
			const uint32_t color = seam ? 0xFF55FFu : 0xFFFFFFu;
			const uint32_t alpha = seam ? 160u : 42u;
			for(int x = 0; x < baseW; ++x) {
				detail::PlotScaled(argb, width, height, sx, sy, viewTop, viewBottom, x, y, color, alpha);
			}
		}
	}

	// 3. Link & active sprite hitboxes + hex IDs.
	if((mask & kMaskHitboxes) != 0) {
		const int linkX = static_cast<int>(detail::ReadU16(wram, wramSize, 0x0022));
		const int linkY = static_cast<int>(detail::ReadU16(wram, wramSize, 0x0020));
		const int lScrX = linkX - camX;
		// ALTTP Link's 16x16 tile-collision footprint sits at (POSX, POSY + 8);
		// inner 8x8 damage box sits at (POSX + 4, POSY + 12).
		const int lFootY = (linkY - camY) + 8 + bgYOff;
		const uint32_t linkColor = (linkLayer == 0) ? 0x38F2FFu : 0xFFD94Au;
		detail::DrawBox(argb, width, height, sx, sy, viewTop, viewBottom, lScrX, lFootY, 16, 16, linkColor, 240u);
		detail::DrawBox(argb, width, height, sx, sy, viewTop, viewBottom, lScrX + 4, lFootY + 4, 8, 8, 0xFFFFFFu, 220u);

		for(int i = 0; i < 16; ++i) {
			const uint8_t state = wram[0x0DD0 + i];
			if(state == 0) {
				continue;
			}
			const uint8_t sprId = wram[0x0E20 + i];
			const int sprX = static_cast<int>(wram[0x0D10 + i]) | (static_cast<int>(wram[0x0D30 + i]) << 8);
			const int sprY = static_cast<int>(wram[0x0D00 + i]) | (static_cast<int>(wram[0x0D20 + i]) << 8);
			const uint8_t props = wram[0x0E60 + i];
			const uint8_t colProp = wram[0x0F60 + i];
			const int sScrX = sprX - camX;
			const int sScrY = (sprY - camY) + sprYOff;
			if(sScrX < -24 || sScrX > baseW + 8 || sScrY < viewTop - 24 || sScrY > viewBottom + 8) {
				continue;
			}
			uint32_t sprColor = 0xFF4848u; // Active enemy
			if(state == 0x0B) {
				sprColor = 0x55AAFFu; // Frozen
			} else if((props & 0x80u) != 0) {
				sprColor = 0x44EE77u; // Harmless / NPC
			} else if((colProp & 0x40u) != 0) {
				sprColor = 0xFF66EEu; // Impervious
			}
			detail::DrawBox(argb, width, height, sx, sy, viewTop, viewBottom, sScrX, sScrY, 16, 16, sprColor, 235u);
			detail::DrawHexByte(argb, width, height, sx, sy, viewTop, viewBottom, sScrX + 1, std::max(viewTop + 1, sScrY - 7), sprId, sprColor);
		}
	}
}

} // namespace HandheldOverlay
