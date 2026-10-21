/**
 * CaptureCamera unit tests
 *
 * Compile and run from the repository root:
 *   clang++ -std=c++17 -DTEST_MODE -ICore Core/Shared/Video/CaptureCameraTest.cpp Core/Shared/Video/CaptureCamera.cpp -o /tmp/capture_camera_test && /tmp/capture_camera_test
 */

#ifdef TEST_MODE

#include "Shared/Video/CaptureCamera.h"
#include <cmath>
#include <cstdio>
#include <vector>

static int _failures = 0;

#define CHECK(cond) do { if(!(cond)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); _failures++; } } while(0)
#define CHECK_NEAR(a, b) CHECK(std::abs((a) - (b)) < 0.01)

// Frame where each pixel encodes its own game coordinate: 0xFF00XXYY
static std::vector<uint32_t> MakeFrame(uint32_t width, uint32_t height, uint32_t scale)
{
	std::vector<uint32_t> frame(width * scale * height * scale);
	for(uint32_t y = 0; y < height * scale; y++) {
		for(uint32_t x = 0; x < width * scale; x++) {
			frame[y * width * scale + x] = 0xFF000000 | ((x / scale) << 8) | (y / scale);
		}
	}
	return frame;
}

static void TestFullModeLetterboxes()
{
	CaptureCamera camera;
	CaptureCameraState state;
	state.OutputWidth = 512;
	state.OutputHeight = 224;
	camera.SetState(state);

	std::vector<uint32_t> src = MakeFrame(256, 224, 1);
	std::vector<uint32_t> out;
	uint32_t w, h;
	camera.Render(src.data(), 256, 224, 1.0, out, w, h);
	CHECK(w == 512 && h == 224);
	CHECK(out[0] == 0xFF000000);               // left pillarbox
	CHECK(out[128] == 0xFF000000);              // first game column
	CHECK(out[383] == (0xFF000000 | (255 << 8))); // last game column
	CHECK(out[384] == 0xFF000000);              // right pillarbox
}

static void TestManualCropAndClamp()
{
	CaptureCamera camera;
	CaptureCameraState state;
	state.Mode = CaptureCameraMode::Manual;
	state.OutputWidth = 160;
	state.OutputHeight = 90;
	state.Zoom = 2.0;
	state.CenterX = 0;
	state.CenterY = 0;
	camera.SetState(state);

	//16:9 at zoom 1 is 256x144 inside 256x224; zoom 2 halves it, and clamping keeps it on screen
	CaptureCameraRect view = camera.GetViewRect(256, 224);
	CHECK_NEAR(view.Width, 128);
	CHECK_NEAR(view.Height, 72);
	CHECK_NEAR(view.X, 0);
	CHECK_NEAR(view.Y, 0);

	state.Clamp = false;
	camera.SetState(state);
	view = camera.GetViewRect(256, 224);
	CHECK_NEAR(view.X, -64);
}

static void TestHiResSourceUsesGamePixels()
{
	CaptureCamera camera;
	CaptureCameraState state;
	state.Mode = CaptureCameraMode::Manual;
	state.CenterX = 100.5;
	state.CenterY = 50.5;
	state.Zoom = 224.0;
	state.OutputWidth = 8;
	state.OutputHeight = 7;
	camera.SetState(state);

	//512x448 source for a 256x224 game: the camera must still address game pixel (100, 50)
	std::vector<uint32_t> src = MakeFrame(256, 224, 2);
	std::vector<uint32_t> out;
	uint32_t w, h;
	camera.Render(src.data(), 512, 448, 2.0, out, w, h);
	CHECK(out[3 * 8 + 4] == (0xFF000000 | (100 << 8) | 50));
}

static void TestPanReachesTarget()
{
	CaptureCamera camera;
	CaptureCameraState state;
	state.Mode = CaptureCameraMode::Manual;
	state.OutputWidth = 128;
	state.OutputHeight = 112;
	state.Zoom = 2.0;
	camera.SetState(state);
	camera.Tick(256, 224, false, 0, 0);

	camera.StartPan(64, 56, 2.0, 10, true);
	for(int i = 0; i < 5; i++) {
		camera.Tick(256, 224, false, 0, 0);
	}
	CaptureCameraState mid = camera.GetState();
	CHECK(mid.PanActive);
	CHECK(mid.CenterX < 128 && mid.CenterX > 64);

	for(int i = 0; i < 5; i++) {
		camera.Tick(256, 224, false, 0, 0);
	}
	CaptureCameraState end = camera.GetState();
	CHECK(!end.PanActive);
	CHECK_NEAR(end.CenterX, 64);
	CHECK_NEAR(end.CenterY, 56);
}

static void TestFollowSmoothingAndDeadzone()
{
	CaptureCamera camera;
	CaptureCameraState state;
	state.Mode = CaptureCameraMode::Follow;
	state.OutputWidth = 128;
	state.OutputHeight = 112;
	state.Zoom = 2.0;
	state.Smoothing = 0.5;
	state.DeadzoneX = 8;
	state.CenterX = 128;
	state.CenterY = 112;
	camera.SetState(state);

	camera.Tick(256, 224, true, 132, 112);
	CHECK_NEAR(camera.GetState().CenterX, 128); // inside the deadzone

	camera.Tick(256, 224, true, 148, 112);
	CHECK_NEAR(camera.GetState().CenterX, 134); // halfway to 140 (target - deadzone)

	for(int i = 0; i < 40; i++) {
		camera.Tick(256, 224, true, 250, 112);
	}
	CHECK_NEAR(camera.GetState().CenterX, 192); // clamped: 256 - 128/2
}

int main()
{
	TestFullModeLetterboxes();
	TestManualCropAndClamp();
	TestHiResSourceUsesGamePixels();
	TestPanReachesTarget();
	TestFollowSmoothingAndDeadzone();
	if(_failures == 0) {
		std::printf("CaptureCamera tests passed\n");
	}
	return _failures == 0 ? 0 : 1;
}

#endif
