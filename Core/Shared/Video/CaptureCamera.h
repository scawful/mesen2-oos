#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <mutex>

// Virtual camera for captures (recordings, screenshots, live streams).
// Works on the decoded output frame only: it never reads or writes game state
// itself. Coordinates are in game pixels (the console's native resolution),
// independent of video filters or SNES hi-res output.
enum class CaptureCameraMode
{
	Full,   // whole frame, letterboxed into the output size
	Manual, // fixed center + zoom
	Follow  // center tracks a screen-space target supplied every frame
};

enum class CaptureCameraFilter
{
	Nearest,
	Bilinear
};

struct CaptureCameraState
{
	CaptureCameraMode Mode = CaptureCameraMode::Full;
	CaptureCameraFilter Filter = CaptureCameraFilter::Nearest;

	// 0 = use the game frame size
	uint32_t OutputWidth = 0;
	uint32_t OutputHeight = 0;

	// Negative = frame center
	double CenterX = -1;
	double CenterY = -1;
	double Zoom = 1.0;
	bool Clamp = true;

	double Smoothing = 0.15;
	double DeadzoneX = 0;
	double DeadzoneY = 0;

	bool PanActive = false;
	double PanFromX = 0, PanFromY = 0, PanFromZoom = 1.0;
	double PanToX = 0, PanToY = 0, PanToZoom = 1.0;
	uint32_t PanFrames = 0;
	uint32_t PanElapsed = 0;
	bool PanSmooth = true;
};

struct CaptureCameraRect
{
	double X = 0;
	double Y = 0;
	double Width = 0;
	double Height = 0;
};

class CaptureCamera
{
private:
	mutable std::mutex _lock;
	CaptureCameraState _state;
	uint32_t _gameWidth = 0;
	uint32_t _gameHeight = 0;

	void ResolveCenter(double& cx, double& cy, double& zoom) const;

public:
	CaptureCameraState GetState() const;
	void SetState(const CaptureCameraState& state);
	void Reset();

	// Starts a pan from the current view to the given center/zoom. Ends in Manual mode.
	void StartPan(double toX, double toY, double toZoom, uint32_t frames, bool smooth);

	// Advance pan/follow by one frame. hasTarget=false leaves Follow mode where it is.
	void Tick(uint32_t gameWidth, uint32_t gameHeight, bool hasTarget, double targetX, double targetY);

	// Output size that Render() will produce for the current game frame size.
	void GetOutputSize(uint32_t gameWidth, uint32_t gameHeight, uint32_t& width, uint32_t& height) const;

	// Visible region in game pixels for the given game frame size and output aspect.
	CaptureCameraRect GetViewRect(uint32_t gameWidth, uint32_t gameHeight) const;

	// src: ARGB frame of srcWidth x srcHeight; pixelScale = source pixels per game pixel.
	// dst is resized to width*height ARGB.
	void Render(const uint32_t* src, uint32_t srcWidth, uint32_t srcHeight, double pixelScale, std::vector<uint32_t>& dst, uint32_t& width, uint32_t& height);

	static CaptureCameraRect ComputeViewRect(const CaptureCameraState& state, uint32_t gameWidth, uint32_t gameHeight, uint32_t outWidth, uint32_t outHeight);
	static bool TryParseMode(const std::string& value, CaptureCameraMode& mode);
	static bool TryParseFilter(const std::string& value, CaptureCameraFilter& filter);
	static const char* ModeName(CaptureCameraMode mode);
	static const char* FilterName(CaptureCameraFilter filter);
};
