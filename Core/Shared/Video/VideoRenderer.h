#pragma once
#include "pch.h"
#include <thread>
#include "Shared/SettingTypes.h"
#include "Shared/RenderedFrame.h"
#include "Shared/Interfaces/IRenderingDevice.h"
#include "Utilities/AutoResetEvent.h"
#include "Utilities/SimpleLock.h"
#include "Utilities/safe_ptr.h"

class IRenderingDevice;
class Emulator;
class SystemHud;
class DebugHud;
class InputHud;
class WatchHud;

class IVideoRecorder;
class Mp4VideoRecorder;
enum class VideoCodec;

struct RecordAviOptions
{
	VideoCodec Codec;
	uint32_t CompressionLevel;
	bool RecordSystemHud;
	bool RecordInputHud;
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
	safe_ptr<Mp4VideoRecorder> _replayRecorder;

	atomic<uint32_t> _debugOverlayMask{ 0 };
	vector<uint32_t> _debugOverlayBuffer;

	void RenderThread();
	bool DrawScriptHud(RenderedFrame& frame);
	
	FrameInfo GetEmuHudSize(FrameInfo baseFrameSize);

	void ProcessAviRecording(RenderedFrame& frame);

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

	bool StartRecording(string filename, RecordAviOptions options);
	void AddRecordingSound(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate);
	void StopRecording();
	bool IsRecording();
	bool IsFileRecording();
	string GetRecordingFile();

	bool StartReplayBuffer(uint32_t windowSeconds, RecordAviOptions options);
	void StopReplayBuffer();
	bool IsReplayBufferActive();
	uint32_t GetReplayBufferedSeconds();
	bool DumpReplayBuffer(string filename);

	void SetDebugOverlayMask(uint32_t mask);
	uint32_t GetDebugOverlayMask();
	bool CopyLastFrame(vector<uint32_t>& outPixels, uint32_t& outWidth, uint32_t& outHeight);
};
