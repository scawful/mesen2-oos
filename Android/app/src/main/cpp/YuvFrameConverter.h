#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace HandheldCapture {

constexpr uint32_t kDefaultRecordWidth = 512;
constexpr uint32_t kDefaultRecordHeight = 480;

struct FramePlacement
{
	uint32_t DstX = 0;
	uint32_t DstY = 0;
	uint32_t DrawW = 0;
	uint32_t DrawH = 0;
};

inline FramePlacement ComputePlacement(uint32_t srcW, uint32_t srcH, uint32_t dstW, uint32_t dstH)
{
	if(srcW == 0 || srcH == 0 || dstW == 0 || dstH == 0) {
		return {};
	}

	uint32_t drawW = 0;
	uint32_t drawH = 0;

	// SNES hi-res modes (e.g. 512x224 Mode 5 / pseudo-hires) double width without doubling height.
	// Use 1x horizontal and 2x vertical (capped to dstH) so 4:3 geometry and crisp hi-res pixels stay intact.
	if(srcW == 512 && srcH <= 240 && dstW >= 512) {
		drawW = 512;
		drawH = std::min(dstH, srcH * 2);
	} else {
		const uint32_t scaleX = dstW / srcW;
		const uint32_t scaleY = dstH / srcH;
		const uint32_t scale = std::min(scaleX, scaleY);
		if(scale >= 1) {
			drawW = srcW * scale;
			drawH = srcH * scale;
		} else {
			const double ratioX = static_cast<double>(dstW) / static_cast<double>(srcW);
			const double ratioY = static_cast<double>(dstH) / static_cast<double>(srcH);
			const double ratio = std::min(ratioX, ratioY);
			drawW = std::max<uint32_t>(1, std::min<uint32_t>(dstW, static_cast<uint32_t>(srcW * ratio)));
			drawH = std::max<uint32_t>(1, std::min<uint32_t>(dstH, static_cast<uint32_t>(srcH * ratio)));
		}
	}

	const uint32_t dstX = ((dstW - drawW) / 2) & ~1u;
	const uint32_t dstY = ((dstH - drawH) / 2) & ~1u;
	return { dstX, dstY, drawW, drawH };
}

inline uint8_t ClampY(int value)
{
	return static_cast<uint8_t>(std::clamp(value, 16, 235));
}

inline uint8_t ClampUV(int value)
{
	return static_cast<uint8_t>(std::clamp(value, 16, 240));
}

inline uint8_t RgbToY(int r, int g, int b)
{
	return ClampY(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
}

inline uint8_t RgbToU(int r, int g, int b)
{
	return ClampUV(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
}

inline uint8_t RgbToV(int r, int g, int b)
{
	return ClampUV(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
}

inline bool ConvertArgbToYuv420(
	const uint32_t* srcArgb,
	uint32_t srcW,
	uint32_t srcH,
	uint32_t dstW,
	uint32_t dstH,
	uint32_t stride,
	uint32_t sliceHeight,
	bool semiPlanar,
	uint8_t* dstYuv,
	size_t dstSize)
{
	if(!srcArgb || !dstYuv || srcW == 0 || srcH == 0 || dstW == 0 || dstH == 0) {
		return false;
	}
	if((dstW & 1u) != 0 || (dstH & 1u) != 0 || (stride & 1u) != 0 || (sliceHeight & 1u) != 0) {
		return false;
	}
	if(stride < dstW || sliceHeight < dstH) {
		return false;
	}

	const size_t yPlaneBytes = static_cast<size_t>(stride) * sliceHeight;
	const size_t chromaHeight = dstH / 2;
	const size_t chromaPlaneStride = stride / 2;
	const size_t requiredChromaBytes = semiPlanar
		? static_cast<size_t>(stride) * chromaHeight
		: static_cast<size_t>(chromaPlaneStride) * (sliceHeight / 2) + static_cast<size_t>(chromaPlaneStride) * chromaHeight;
	if(dstSize < yPlaneBytes + requiredChromaBytes) {
		return false;
	}

	const FramePlacement placement = ComputePlacement(srcW, srcH, dstW, dstH);
	if(placement.DrawW == 0 || placement.DrawH == 0) {
		return false;
	}

	std::vector<int32_t> xMap(dstW, -1);
	for(uint32_t x = placement.DstX; x < placement.DstX + placement.DrawW; ++x) {
		xMap[x] = static_cast<int32_t>((static_cast<uint64_t>(x - placement.DstX) * srcW) / placement.DrawW);
	}

	uint8_t* yPlane = dstYuv;
	uint8_t* uvBase = dstYuv + yPlaneBytes;
	uint8_t* uPlane = uvBase;
	uint8_t* vPlane = uvBase + (static_cast<size_t>(chromaPlaneStride) * (sliceHeight / 2));

	auto samplePixel = [&](int32_t sx, int32_t sy, int& r, int& g, int& b) {
		if(sx < 0 || sy < 0) {
			r = 0;
			g = 0;
			b = 0;
			return;
		}
		const uint32_t argb = srcArgb[static_cast<size_t>(sy) * srcW + static_cast<size_t>(sx)];
		r = static_cast<int>((argb >> 16) & 0xFFu);
		g = static_cast<int>((argb >> 8) & 0xFFu);
		b = static_cast<int>(argb & 0xFFu);
	};

	for(uint32_t y = 0; y < dstH; y += 2) {
		const int32_t sy0 = (y >= placement.DstY && y < placement.DstY + placement.DrawH)
			? static_cast<int32_t>((static_cast<uint64_t>(y - placement.DstY) * srcH) / placement.DrawH)
			: -1;
		const int32_t sy1 = (y + 1 >= placement.DstY && y + 1 < placement.DstY + placement.DrawH)
			? static_cast<int32_t>((static_cast<uint64_t>(y + 1 - placement.DstY) * srcH) / placement.DrawH)
			: -1;

		uint8_t* yRow0 = yPlane + static_cast<size_t>(y) * stride;
		uint8_t* yRow1 = yRow0 + stride;
		const size_t chromaRow = y / 2;

		for(uint32_t x = 0; x < dstW; x += 2) {
			const int32_t sx0 = xMap[x];
			const int32_t sx1 = xMap[x + 1];

			int r00, g00, b00;
			int r10, g10, b10;
			int r01, g01, b01;
			int r11, g11, b11;
			samplePixel(sx0, sy0, r00, g00, b00);
			samplePixel(sx1, sy0, r10, g10, b10);
			samplePixel(sx0, sy1, r01, g01, b01);
			samplePixel(sx1, sy1, r11, g11, b11);

			yRow0[x] = RgbToY(r00, g00, b00);
			yRow0[x + 1] = RgbToY(r10, g10, b10);
			yRow1[x] = RgbToY(r01, g01, b01);
			yRow1[x + 1] = RgbToY(r11, g11, b11);

			const int avgR = (r00 + r10 + r01 + r11 + 2) >> 2;
			const int avgG = (g00 + g10 + g01 + g11 + 2) >> 2;
			const int avgB = (b00 + b10 + b01 + b11 + 2) >> 2;
			const uint8_t uVal = RgbToU(avgR, avgG, avgB);
			const uint8_t vVal = RgbToV(avgR, avgG, avgB);

			if(semiPlanar) {
				uint8_t* uvRow = uvBase + chromaRow * stride;
				uvRow[x] = uVal;
				uvRow[x + 1] = vVal;
			} else {
				const size_t cIdx = chromaRow * chromaPlaneStride + (x / 2);
				uPlane[cIdx] = uVal;
				vPlane[cIdx] = vVal;
			}
		}
	}

	return true;
}

} // namespace HandheldCapture
