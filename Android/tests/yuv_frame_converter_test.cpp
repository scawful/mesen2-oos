#include "../app/src/main/cpp/YuvFrameConverter.h"

#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>

using HandheldCapture::ComputePlacement;
using HandheldCapture::ConvertArgbToYuv420;
using HandheldCapture::FramePlacement;
using HandheldCapture::RgbToU;
using HandheldCapture::RgbToV;
using HandheldCapture::RgbToY;
using HandheldCapture::kDefaultRecordHeight;
using HandheldCapture::kDefaultRecordWidth;

static void Check(bool condition, const char* message)
{
	if(!condition) {
		throw std::runtime_error(message);
	}
}

static void TestPlacementVariants()
{
	FramePlacement p256 = ComputePlacement(256, 224, kDefaultRecordWidth, kDefaultRecordHeight);
	Check(p256.DstX == 0 && p256.DstY == 16 && p256.DrawW == 512 && p256.DrawH == 448,
		"256x224 should scale 2x cleanly to 512x448 centered inside 512x480");

	FramePlacement pHires = ComputePlacement(512, 224, kDefaultRecordWidth, kDefaultRecordHeight);
	Check(pHires.DstX == 0 && pHires.DstY == 16 && pHires.DrawW == 512 && pHires.DrawH == 448,
		"512x224 hi-res should map 1x horizontally and 2x vertically to 512x448");

	FramePlacement pInterlaced = ComputePlacement(512, 448, kDefaultRecordWidth, kDefaultRecordHeight);
	Check(pInterlaced.DstX == 0 && pInterlaced.DstY == 16 && pInterlaced.DrawW == 512 && pInterlaced.DrawH == 448,
		"512x448 should map 1:1 centered inside 512x480");

	FramePlacement pOverscan = ComputePlacement(256, 239, kDefaultRecordWidth, kDefaultRecordHeight);
	Check(pOverscan.DrawW == 512 && pOverscan.DrawH == 478 && pOverscan.DstX == 0 && pOverscan.DstY == 0,
		"256x239 overscan should scale 2x cleanly to 512x478 inside 512x480");
	Check((pOverscan.DstX & 1u) == 0 && (pOverscan.DstY & 1u) == 0,
		"placement must keep even offsets for 4:2:0 alignment");

	FramePlacement pHiresOverscan = ComputePlacement(512, 239, kDefaultRecordWidth, kDefaultRecordHeight);
	Check(pHiresOverscan.DrawW == 512 && pHiresOverscan.DrawH == 478 && pHiresOverscan.DstX == 0 && pHiresOverscan.DstY == 0,
		"512x239 hi-res overscan should scale 1x2 cleanly to 512x478");
}

static void TestZeroChromaBleed2xUpscaleNv12()
{
	const uint32_t srcW = 256;
	const uint32_t srcH = 224;
	std::vector<uint32_t> src(srcW * srcH, 0xFF0000FFu); // blue background
	// Sharp 1px red/green checkerboard in top-left 4x4 block.
	for(uint32_t y = 0; y < 4; ++y) {
		for(uint32_t x = 0; x < 4; ++x) {
			src[y * srcW + x] = ((x + y) & 1u) ? 0xFFFF0000u : 0xFF00FF00u;
		}
	}

	const uint32_t dstW = 512;
	const uint32_t dstH = 448;
	std::vector<uint8_t> yuv(dstW * dstH * 3 / 2, 0);
	Check(ConvertArgbToYuv420(src.data(), srcW, srcH, dstW, dstH, dstW, dstH, true, yuv.data(), yuv.size()),
		"NV12 conversion should succeed");

	const uint8_t redY = RgbToY(255, 0, 0);
	const uint8_t redU = RgbToU(255, 0, 0);
	const uint8_t redV = RgbToV(255, 0, 0);
	const uint8_t greenY = RgbToY(0, 255, 0);
	const uint8_t greenU = RgbToU(0, 255, 0);
	const uint8_t greenV = RgbToV(0, 255, 0);

	// Source (0,0) = green -> dest 2x2 block [0..1, 0..1]
	Check(yuv[0] == greenY && yuv[1] == greenY && yuv[dstW] == greenY && yuv[dstW + 1] == greenY,
		"2x2 Y block (0,0) must match pure green without averaging neighbors");
	const size_t uvOffset = static_cast<size_t>(dstW) * dstH;
	Check(yuv[uvOffset] == greenU && yuv[uvOffset + 1] == greenV,
		"2x2 UV block (0,0) must match pure green with zero chroma bleed");

	// Source (1,0) = red -> dest 2x2 block [2..3, 0..1]
	Check(yuv[2] == redY && yuv[3] == redY && yuv[dstW + 2] == redY && yuv[dstW + 3] == redY,
		"2x2 Y block (1,0) must match pure red");
	Check(yuv[uvOffset + 2] == redU && yuv[uvOffset + 3] == redV,
		"2x2 UV block (1,0) must match pure red with zero chroma bleed");
}

static void TestHires512x224Mapping()
{
	const uint32_t srcW = 512;
	const uint32_t srcH = 224;
	std::vector<uint32_t> src(srcW * srcH, 0xFF000000u);
	src[0] = 0xFFFFFFFFu;
	src[1] = 0xFF000000u;

	const uint32_t dstW = 512;
	const uint32_t dstH = 448;
	std::vector<uint8_t> yuv(dstW * dstH * 3 / 2, 0);
	Check(ConvertArgbToYuv420(src.data(), srcW, srcH, dstW, dstH, dstW, dstH, true, yuv.data(), yuv.size()),
		"512x224 NV12 conversion should succeed");
	Check(yuv[0] == 235 && yuv[1] == 16 && yuv[dstW] == 235 && yuv[dstW + 1] == 16,
		"hi-res horizontal pixels must stay distinct while doubling vertically");
}

static void TestLetterboxAndPaddedStrides()
{
	const uint32_t srcW = 256;
	const uint32_t srcH = 239;
	std::vector<uint32_t> src(srcW * srcH, 0xFFFFFFFFu);

	const uint32_t dstW = 512;
	const uint32_t dstH = 448;
	const uint32_t stride = 576;
	const uint32_t sliceHeight = 464;

	// Test both NV12 (semiPlanar=true) and I420 (semiPlanar=false)
	for(bool semiPlanar : { true, false }) {
		std::vector<uint8_t> yuv(static_cast<size_t>(stride) * sliceHeight * 3 / 2, 0xCC);
		Check(ConvertArgbToYuv420(src.data(), srcW, srcH, dstW, dstH, stride, sliceHeight, semiPlanar, yuv.data(), yuv.size()),
			"padded stride conversion should succeed");

		// Top-left border pixel (0, 0) must be studio black: Y=16, U=128, V=128
		Check(yuv[0] == 16, "border Y must be 16");
		// Centered white region starts at (128, 104)
		Check(yuv[104 * stride + 128] == 235, "centered content Y must be 235");

		const size_t yBytes = static_cast<size_t>(stride) * sliceHeight;
		if(semiPlanar) {
			Check(yuv[yBytes] == 128 && yuv[yBytes + 1] == 128, "NV12 border chroma must be 128/128");
		} else {
			const size_t uIdx = yBytes + (104 / 2) * (stride / 2) + (128 / 2);
			const size_t vIdx = yBytes + (stride / 2) * (sliceHeight / 2) + (104 / 2) * (stride / 2) + (128 / 2);
			Check(yuv[yBytes] == 128, "I420 border U must be 128");
			Check(yuv[uIdx] == 128 && yuv[vIdx] == 128, "I420 white center chroma must be 128/128");
		}
	}

	// Reject undersized destination buffer
	std::vector<uint8_t> tiny(100, 0);
	Check(!ConvertArgbToYuv420(src.data(), srcW, srcH, dstW, dstH, stride, sliceHeight, true, tiny.data(), tiny.size()),
		"undersized destination buffer must fail cleanly");
}

int main()
{
	TestPlacementVariants();
	TestZeroChromaBleed2xUpscaleNv12();
	TestHires512x224Mapping();
	TestLetterboxAndPaddedStrides();
	std::cout << "yuv_frame_converter_test OK (4 suites)\n";
	return 0;
}
