#pragma once
#include "pch.h"
#include <thread>
#include "Shared/SettingTypes.h"
#include "Shared/RenderedFrame.h"
#include "Shared/Interfaces/IRenderingDevice.h"
#include "Utilities/AutoResetEvent.h"
#include "Utilities/SimpleLock.h"
#include "Utilities/safe_ptr.h"
#include "Shared/MemoryType.h"
#include "Shared/Video/CaptureCamera.h"

class IRenderingDevice;
class LiveStreamSink;
class Emulator;
class SystemHud;
class DebugHud;
class InputHud;
class WatchHud;

class IVideoRecorder;
enum class VideoCodec;

struct RecordAviOptions
{
	VideoCodec Codec;
	uint32_t CompressionLevel;
	bool RecordSystemHud;
	bool RecordInputHud;
};

// Screen-space follow target for the capture camera: target - scroll + offset, read
// from emulator memory each frame. Addresses are offsets into MemType; -1 = unused.
struct CaptureFollowSource
{
	MemoryType MemType = MemoryType::SnesWorkRam;
	int32_t TargetXAddr = -1;
	int32_t TargetYAddr = -1;
	int32_t ScrollXAddr = -1;
	int32_t ScrollYAddr = -1;
	int32_t OffsetX = 0;
	int32_t OffsetY = 0;
	uint8_t ValueSize = 2;
};

class VideoRenderer
{
private:
	Emulator* _emu;

	AutoResetEvent _waitForRender;
	unique_ptr<std::thread> _renderThread;
	IRenderingDevice* _renderer = nullptr;
	atomic<bool> _stopFlag;
	SimpleLock _stopStartLock;

	uint32_t _rendererWidth = 512;
	uint32_t _rendererHeight = 480;

	unique_ptr<DebugHud> _rendererHud;
	unique_ptr<SystemHud> _systemHud;
	unique_ptr<InputHud> _inputHud;
	unique_ptr<WatchHud> _watchHud;
	SimpleLock _hudLock;

	RenderSurfaceInfo _aviRecorderSurface = {};
	RecordAviOptions _recorderOptions = {};

	RenderSurfaceInfo _emuHudSurface = {};
	RenderSurfaceInfo _scriptHudSurface = {};
	bool _needScriptHudClear = false;
	uint32_t _scriptHudScale = 2;
	uint32_t _lastScriptHudFrameNumber = 0;
	bool _needRedraw = true;

	RenderedFrame _lastFrame;
	SimpleLock _frameLock;

	safe_ptr<IVideoRecorder> _recorder;
	atomic<bool> _recordThroughCamera = false;

	CaptureCamera _camera;
	CaptureFollowSource _follow;
	SimpleLock _followLock;
	safe_ptr<LiveStreamSink> _liveSink;
	vector<uint32_t> _cameraFrame;
	uint32_t _cameraFrameWidth = 0;
	uint32_t _cameraFrameHeight = 0;

	void RenderThread();
	bool DrawScriptHud(RenderedFrame& frame);
	
	FrameInfo GetEmuHudSize(FrameInfo baseFrameSize);

	void ProcessAviRecording(RenderedFrame& frame);
	void ProcessCapture(RenderedFrame& frame);
	bool ReadFollowTarget(double& x, double& y);
	double GetGamePixelScale(const RenderedFrame& frame);

public:
	VideoRenderer(Emulator* emu);
	~VideoRenderer();

	FrameInfo GetRendererSize();
	void SetRendererSize(uint32_t width, uint32_t height);
	
	void SetScriptHudScale(uint32_t scale) { _scriptHudScale = scale; }
	std::pair<FrameInfo, OverscanDimensions> GetScriptHudSize();
	void SetWatchHudText(string text);
	string GetWatchHudText();
	void SetWatchHudData(string dataJson);
	string GetWatchHudData();

	void StartThread();
	void StopThread();

	void UpdateFrame(RenderedFrame& frame);
	void ClearFrame();
	void RegisterRenderingDevice(IRenderingDevice *renderer);
	void UnregisterRenderingDevice(IRenderingDevice *renderer);

	void StartRecording(string filename, RecordAviOptions options);
	bool StartRecording(string filename, RecordAviOptions options, bool throughCamera);
	void AddRecordingSound(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate);
	void StopRecording();
	bool IsRecording();
	bool IsRecordingThroughCamera() { return _recordThroughCamera; }
	string GetRecordingFile();
	bool NeedsRecordingSound();

	CaptureCamera& GetCaptureCamera() { return _camera; }
	void SetCameraFollow(const CaptureFollowSource& source);
	CaptureFollowSource GetCameraFollow();
	// Game frame size (native pixels) of the last rendered frame.
	FrameInfo GetGameFrameSize();
	bool RenderCameraSnapshot(vector<uint32_t>& buffer, uint32_t& width, uint32_t& height);

	bool StartLiveStream(string videoPath, string audioPath, uint32_t frameInterval, uint32_t downscale, string& error);
	void StopLiveStream();
	shared_ptr<LiveStreamSink> GetLiveStream();
};
