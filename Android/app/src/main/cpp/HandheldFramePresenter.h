#pragma once

#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

namespace HandheldVideo {

struct Frame
{
	std::vector<uint32_t> Pixels;
	uint32_t Width = 0;
	uint32_t Height = 0;
	uint64_t Generation = 0;
};

// Own callback pixels before the renderer reuses its buffer. The SDL thread
// keeps a separate reusable snapshot, so it never holds this lock while drawing.
class FrameMailbox
{
public:
	bool Publish(const uint32_t* pixels, uint32_t width, uint32_t height)
	{
		if(!pixels || width == 0 || height == 0
			|| width > static_cast<uint32_t>(std::numeric_limits<int>::max() / sizeof(uint32_t))
			|| height > static_cast<uint32_t>(std::numeric_limits<int>::max())
			|| static_cast<size_t>(width) > std::numeric_limits<size_t>::max() / height / sizeof(uint32_t)) {
			return false;
		}
		const size_t count = static_cast<size_t>(width) * height;
		std::lock_guard<std::mutex> lock(_mutex);
		if(width == _latest.Width && height == _latest.Height
			&& std::memcmp(_latest.Pixels.data(), pixels, count * sizeof(uint32_t)) == 0) {
			return false;
		}
		_latest.Pixels.assign(pixels, pixels + count);
		_latest.Width = width;
		_latest.Height = height;
		++_latest.Generation;
		return true;
	}

	bool CopyLatest(Frame& destination)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if(destination.Generation == _latest.Generation) {
			return false;
		}
		destination.Pixels.assign(_latest.Pixels.begin(), _latest.Pixels.end());
		destination.Width = _latest.Width;
		destination.Height = _latest.Height;
		destination.Generation = _latest.Generation;
		return true;
	}

private:
	std::mutex _mutex;
	Frame _latest;
};

// SDL-thread state. Failed creation/upload/drawing never consumes pending work.
class PresentationState
{
public:
	void RequestRedraw() { _redraw = true; }
	void InvalidateTexture() { _uploaded = 0; _redraw = true; }
	bool NeedsPresent(const Frame& frame) const { return _redraw || _presented != frame.Generation; }
	bool NeedsUpload(const Frame& frame) const { return !frame.Pixels.empty() && _uploaded != frame.Generation; }
	void Uploaded(const Frame& frame) { _uploaded = frame.Generation; }
	void Presented(const Frame& frame) { _presented = frame.Generation; _redraw = false; }

private:
	uint64_t _uploaded = 0;
	uint64_t _presented = 0;
	bool _redraw = true;
};

// The emulator keeps running when Android loses its drawing surface. Only the
// SDL thread uses this state; background events must not recreate or bind GL.
class SurfaceState
{
public:
	void SetForeground(bool foreground) { _foreground = foreground; Invalidate(); }
	void SetWindowVisible(bool visible) { _windowVisible = visible; Invalidate(); }
	void Invalidate() { _rebuild = true; _retryAfter = 0; }
	bool ObserveBinding(uintptr_t window, uintptr_t surface, bool bootstrap = false)
	{
		const bool changed = window != _nativeWindow || surface != _nativeSurface || bootstrap != _bootstrap;
		_nativeWindow = window;
		_nativeSurface = surface;
		_bootstrap = bootstrap;
		if(changed) { Invalidate(); }
		return changed;
	}
	bool HasBinding() const { return _nativeWindow != 0 && _nativeSurface != 0; }
	bool CanDraw() const { return _foreground && _windowVisible && HasBinding() && !_rebuild; }
	bool CanRebuild(uint64_t now) const
	{
		return _foreground && _windowVisible && _rebuild && now >= _retryAfter
			&& _nativeWindow != 0 && (_nativeSurface != 0 || (_bootstrap && !_everReady));
	}
	void Rebuilt(bool success, uint64_t now)
	{
		success = success && HasBinding();
		_rebuild = !success;
		_retryAfter = success ? 0 : now + 100;
		_everReady = _everReady || success;
	}

private:
	bool _foreground = true;
	bool _windowVisible = true;
	bool _rebuild = true;
	bool _bootstrap = false;
	bool _everReady = false;
	uintptr_t _nativeWindow = 0;
	uintptr_t _nativeSurface = 0;
	uint64_t _retryAfter = 0;
};

}
