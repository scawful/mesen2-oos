#ifndef TEST_MODE
#include "pch.h"
#endif
#include "Shared/Video/CaptureCamera.h"
#include <algorithm>
#include <cmath>

CaptureCameraState CaptureCamera::GetState() const
{
	std::lock_guard<std::mutex> guard(_lock);
	return _state;
}

void CaptureCamera::SetState(const CaptureCameraState& state)
{
	std::lock_guard<std::mutex> guard(_lock);
	_state = state;
	_state.Zoom = std::clamp(_state.Zoom, 0.1, 64.0);
	_state.Smoothing = std::clamp(_state.Smoothing, 0.0, 1.0);
}

void CaptureCamera::Reset()
{
	std::lock_guard<std::mutex> guard(_lock);
	_state = {};
}

void CaptureCamera::ResolveCenter(double& cx, double& cy, double& zoom) const
{
	CaptureCameraRect rect = ComputeViewRect(_state, _gameWidth, _gameHeight, _state.OutputWidth ? _state.OutputWidth : _gameWidth, _state.OutputHeight ? _state.OutputHeight : _gameHeight);
	if(_state.Mode == CaptureCameraMode::Full) {
		cx = _gameWidth / 2.0;
		cy = _gameHeight / 2.0;
		zoom = 1.0;
	} else {
		cx = rect.X + rect.Width / 2;
		cy = rect.Y + rect.Height / 2;
		zoom = _state.Zoom;
	}
}

void CaptureCamera::StartPan(double toX, double toY, double toZoom, uint32_t frames, bool smooth)
{
	std::lock_guard<std::mutex> guard(_lock);
	ResolveCenter(_state.PanFromX, _state.PanFromY, _state.PanFromZoom);
	_state.PanToX = toX < 0 ? _state.PanFromX : toX;
	_state.PanToY = toY < 0 ? _state.PanFromY : toY;
	_state.PanToZoom = toZoom <= 0 ? _state.PanFromZoom : std::clamp(toZoom, 0.1, 64.0);
	_state.PanFrames = std::max<uint32_t>(1, frames);
	_state.PanElapsed = 0;
	_state.PanSmooth = smooth;
	_state.PanActive = true;
	_state.Mode = CaptureCameraMode::Manual;
	_state.CenterX = _state.PanFromX;
	_state.CenterY = _state.PanFromY;
	_state.Zoom = _state.PanFromZoom;
}

void CaptureCamera::Tick(uint32_t gameWidth, uint32_t gameHeight, bool hasTarget, double targetX, double targetY)
{
	std::lock_guard<std::mutex> guard(_lock);
	_gameWidth = gameWidth;
	_gameHeight = gameHeight;

	if(_state.PanActive) {
		_state.PanElapsed++;
		double t = std::min(1.0, (double)_state.PanElapsed / _state.PanFrames);
		if(_state.PanSmooth) {
			t = t * t * (3 - 2 * t);
		}
		_state.CenterX = _state.PanFromX + (_state.PanToX - _state.PanFromX) * t;
		_state.CenterY = _state.PanFromY + (_state.PanToY - _state.PanFromY) * t;
		_state.Zoom = _state.PanFromZoom + (_state.PanToZoom - _state.PanFromZoom) * t;
		if(_state.PanElapsed >= _state.PanFrames) {
			_state.PanActive = false;
		}
		return;
	}

	if(_state.Mode != CaptureCameraMode::Follow || !hasTarget) {
		return;
	}

	double cx = _state.CenterX < 0 ? gameWidth / 2.0 : _state.CenterX;
	double cy = _state.CenterY < 0 ? gameHeight / 2.0 : _state.CenterY;

	auto approach = [this](double center, double target, double deadzone) {
		double delta = target - center;
		if(std::abs(delta) <= deadzone) {
			return center;
		}
		double desired = target - (delta > 0 ? deadzone : -deadzone);
		return center + (desired - center) * _state.Smoothing;
	};
	_state.CenterX = approach(cx, targetX, _state.DeadzoneX);
	_state.CenterY = approach(cy, targetY, _state.DeadzoneY);

	if(_state.Clamp) {
		//Keep the stored center inside the reachable range so the camera does not lag when the target turns around near an edge
		CaptureCameraRect rect = ComputeViewRect(_state, gameWidth, gameHeight, _state.OutputWidth ? _state.OutputWidth : gameWidth, _state.OutputHeight ? _state.OutputHeight : gameHeight);
		_state.CenterX = rect.X + rect.Width / 2;
		_state.CenterY = rect.Y + rect.Height / 2;
	}
}

void CaptureCamera::GetOutputSize(uint32_t gameWidth, uint32_t gameHeight, uint32_t& width, uint32_t& height) const
{
	std::lock_guard<std::mutex> guard(_lock);
	width = _state.OutputWidth ? _state.OutputWidth : gameWidth;
	height = _state.OutputHeight ? _state.OutputHeight : gameHeight;
}

CaptureCameraRect CaptureCamera::GetViewRect(uint32_t gameWidth, uint32_t gameHeight) const
{
	std::lock_guard<std::mutex> guard(_lock);
	return ComputeViewRect(_state, gameWidth, gameHeight, _state.OutputWidth ? _state.OutputWidth : gameWidth, _state.OutputHeight ? _state.OutputHeight : gameHeight);
}

CaptureCameraRect CaptureCamera::ComputeViewRect(const CaptureCameraState& state, uint32_t gameWidth, uint32_t gameHeight, uint32_t outWidth, uint32_t outHeight)
{
	CaptureCameraRect rect;
	if(gameWidth == 0 || gameHeight == 0 || outWidth == 0 || outHeight == 0) {
		return rect;
	}

	if(state.Mode == CaptureCameraMode::Full) {
		rect.Width = gameWidth;
		rect.Height = gameHeight;
		return rect;
	}

	double aspect = (double)outWidth / outHeight;
	double baseHeight = std::min<double>(gameHeight, gameWidth / aspect);
	double zoom = std::clamp(state.Zoom, 0.1, 64.0);
	rect.Height = baseHeight / zoom;
	rect.Width = rect.Height * aspect;

	double cx = state.CenterX < 0 ? gameWidth / 2.0 : state.CenterX;
	double cy = state.CenterY < 0 ? gameHeight / 2.0 : state.CenterY;
	if(state.Clamp) {
		cx = rect.Width >= gameWidth ? gameWidth / 2.0 : std::clamp(cx, rect.Width / 2, gameWidth - rect.Width / 2);
		cy = rect.Height >= gameHeight ? gameHeight / 2.0 : std::clamp(cy, rect.Height / 2, gameHeight - rect.Height / 2);
	}
	rect.X = cx - rect.Width / 2;
	rect.Y = cy - rect.Height / 2;
	return rect;
}

void CaptureCamera::Render(const uint32_t* src, uint32_t srcWidth, uint32_t srcHeight, double pixelScale, std::vector<uint32_t>& dst, uint32_t& width, uint32_t& height)
{
	if(pixelScale <= 0) {
		pixelScale = 1.0;
	}
	uint32_t gameWidth = (uint32_t)std::lround(srcWidth / pixelScale);
	uint32_t gameHeight = (uint32_t)std::lround(srcHeight / pixelScale);

	CaptureCameraState state = GetState();
	width = state.OutputWidth ? state.OutputWidth : gameWidth;
	height = state.OutputHeight ? state.OutputHeight : gameHeight;
	dst.assign((size_t)width * height, 0xFF000000);
	if(!src || srcWidth == 0 || srcHeight == 0 || width == 0 || height == 0) {
		return;
	}

	//Map the visible game rect onto a destination rect (letterboxed in Full mode)
	CaptureCameraRect view = ComputeViewRect(state, gameWidth, gameHeight, width, height);
	double dstX = 0, dstY = 0, dstW = width, dstH = height;
	if(state.Mode == CaptureCameraMode::Full) {
		double fit = std::min((double)width / gameWidth, (double)height / gameHeight);
		dstW = gameWidth * fit;
		dstH = gameHeight * fit;
		dstX = (width - dstW) / 2;
		dstY = (height - dstH) / 2;
	}
	double stepX = view.Width / dstW;
	double stepY = view.Height / dstH;

	auto toSource = [pixelScale](double game) { return game * pixelScale; };

	if(state.Filter == CaptureCameraFilter::Nearest) {
		std::vector<int32_t> xIndex(width);
		for(uint32_t x = 0; x < width; x++) {
			double gx = view.X + (x + 0.5 - dstX) * stepX;
			int32_t sx = (int32_t)std::floor(toSource(gx));
			bool inside = x + 0.5 >= dstX && x + 0.5 < dstX + dstW && gx >= 0 && gx < gameWidth;
			xIndex[x] = inside ? std::clamp<int32_t>(sx, 0, srcWidth - 1) : -1;
		}
		for(uint32_t y = 0; y < height; y++) {
			double gy = view.Y + (y + 0.5 - dstY) * stepY;
			if(y + 0.5 < dstY || y + 0.5 >= dstY + dstH || gy < 0 || gy >= gameHeight) {
				continue;
			}
			int32_t sy = std::clamp<int32_t>((int32_t)std::floor(toSource(gy)), 0, srcHeight - 1);
			const uint32_t* srcRow = src + (size_t)sy * srcWidth;
			uint32_t* dstRow = dst.data() + (size_t)y * width;
			for(uint32_t x = 0; x < width; x++) {
				int32_t sx = xIndex[x];
				if(sx >= 0) {
					dstRow[x] = srcRow[sx];
				}
			}
		}
		return;
	}

	auto lerpChannel = [](uint32_t a, uint32_t b, double t, int shift) {
		double ca = (a >> shift) & 0xFF;
		double cb = (b >> shift) & 0xFF;
		return (uint32_t)std::lround(ca + (cb - ca) * t) << shift;
	};
	auto lerpPixel = [&](uint32_t a, uint32_t b, double t) {
		return 0xFF000000 | lerpChannel(a, b, t, 16) | lerpChannel(a, b, t, 8) | lerpChannel(a, b, t, 0);
	};

	for(uint32_t y = 0; y < height; y++) {
		double gy = view.Y + (y + 0.5 - dstY) * stepY;
		if(y + 0.5 < dstY || y + 0.5 >= dstY + dstH || gy < 0 || gy >= gameHeight) {
			continue;
		}
		double sy = toSource(gy) - 0.5;
		int32_t y0 = std::clamp<int32_t>((int32_t)std::floor(sy), 0, srcHeight - 1);
		int32_t y1 = std::min<int32_t>(y0 + 1, srcHeight - 1);
		double fy = std::clamp(sy - std::floor(sy), 0.0, 1.0);
		uint32_t* dstRow = dst.data() + (size_t)y * width;
		for(uint32_t x = 0; x < width; x++) {
			double gx = view.X + (x + 0.5 - dstX) * stepX;
			if(x + 0.5 < dstX || x + 0.5 >= dstX + dstW || gx < 0 || gx >= gameWidth) {
				continue;
			}
			double sx = toSource(gx) - 0.5;
			int32_t x0 = std::clamp<int32_t>((int32_t)std::floor(sx), 0, srcWidth - 1);
			int32_t x1 = std::min<int32_t>(x0 + 1, srcWidth - 1);
			double fx = std::clamp(sx - std::floor(sx), 0.0, 1.0);
			uint32_t top = lerpPixel(src[(size_t)y0 * srcWidth + x0], src[(size_t)y0 * srcWidth + x1], fx);
			uint32_t bottom = lerpPixel(src[(size_t)y1 * srcWidth + x0], src[(size_t)y1 * srcWidth + x1], fx);
			dstRow[x] = lerpPixel(top, bottom, fy);
		}
	}
}

bool CaptureCamera::TryParseMode(const std::string& value, CaptureCameraMode& mode)
{
	if(value == "full" || value == "off") {
		mode = CaptureCameraMode::Full;
	} else if(value == "manual") {
		mode = CaptureCameraMode::Manual;
	} else if(value == "follow") {
		mode = CaptureCameraMode::Follow;
	} else {
		return false;
	}
	return true;
}

bool CaptureCamera::TryParseFilter(const std::string& value, CaptureCameraFilter& filter)
{
	if(value == "nearest") {
		filter = CaptureCameraFilter::Nearest;
	} else if(value == "bilinear" || value == "smooth") {
		filter = CaptureCameraFilter::Bilinear;
	} else {
		return false;
	}
	return true;
}

const char* CaptureCamera::ModeName(CaptureCameraMode mode)
{
	switch(mode) {
		case CaptureCameraMode::Manual: return "manual";
		case CaptureCameraMode::Follow: return "follow";
		default: return "full";
	}
}

const char* CaptureCamera::FilterName(CaptureCameraFilter filter)
{
	return filter == CaptureCameraFilter::Bilinear ? "bilinear" : "nearest";
}
