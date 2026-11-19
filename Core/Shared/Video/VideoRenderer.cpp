#include "pch.h"
#include "Shared/Video/VideoRenderer.h"
#include "Shared/Video/VideoDecoder.h"
#include "Shared/Interfaces/IRenderingDevice.h"
#include "Shared/Emulator.h"
#include "Shared/EmuSettings.h"
#include "Shared/Video/DebugHud.h"
#include "Shared/Video/SystemHud.h"
#include "Shared/Video/WatchHud.h"
#include "Shared/InputHud.h"
#include "Shared/MessageManager.h"
#include "Utilities/Video/IVideoRecorder.h"
#include "Utilities/Video/AviRecorder.h"
#include "Utilities/Video/GifRecorder.h"
#include "Utilities/FolderUtilities.h"
#include "Android/app/src/main/cpp/HandheldDebugOverlay.h"
#ifdef __ANDROID__
#include "Android/app/src/main/cpp/Mp4VideoRecorder.h"
#endif

VideoRenderer::VideoRenderer(Emulator* emu)
{
	_emu = emu;
	_stopFlag = false;

	_rendererHud.reset(new DebugHud());
	_systemHud.reset(new SystemHud(_emu));
	_inputHud.reset(new InputHud(emu, _rendererHud.get()));
	_watchHud.reset(new WatchHud(_emu));
}

VideoRenderer::~VideoRenderer()
{
	_stopFlag = true;
	StopThread();
}

FrameInfo VideoRenderer::GetRendererSize()
{
	FrameInfo frame = {};
	frame.Width = _rendererWidth;
	frame.Height = _rendererHeight;
	return frame;
}

void VideoRenderer::SetRendererSize(uint32_t width, uint32_t height)
{
	_rendererWidth = width;
	_rendererHeight = height;
}

void VideoRenderer::StartThread()
{
	if(!_renderThread) {
		auto lock = _stopStartLock.AcquireSafe();
		if(!_renderThread) {
			_stopFlag = false;
			_waitForRender.Reset();

			_renderThread.reset(new std::thread(&VideoRenderer::RenderThread, this));
		}
	}
}

void VideoRenderer::StopThread()
{
	_stopFlag = true;
	if(_renderThread) {
		auto lock = _stopStartLock.AcquireSafe();
		if(_renderThread) {
			_renderThread->join();
			_renderThread.reset();
		}
	}
}

void VideoRenderer::RenderThread()
{
	if(_renderer) {
		_renderer->OnRendererThreadStarted();
	}

	while(!_stopFlag.load()) {
		//Wait until a frame is ready, or until 32ms have passed (to allow HUD to update at ~30fps when paused)
		bool timedOut = !_waitForRender.Wait(32);
		// The timeout path is for paused HUD only. During turbo/FF, PPU already
		// cadence-limits presents (~96 ms); force-redrawing here spiked outdoor FF
		// presents to ~30 Hz instead of the intended 10–12 Hz.
		bool forceRender = timedOut && _emu->IsPaused();
		if(_renderer) {
			FrameInfo size = _emu->GetVideoDecoder()->GetBaseFrameInfo(true);
			_scriptHudSurface.UpdateSize(size.Width * _scriptHudScale, size.Height * _scriptHudScale);

			size = GetEmuHudSize(size);
			if(_emuHudSurface.UpdateSize(size.Width, size.Height)) {
				_rendererHud->ClearScreen();
			}

			RenderedFrame frame;
			{
				auto lock = _frameLock.AcquireSafe();
				frame = _lastFrame;
			}

			_inputHud->DrawControllers(size, frame.InputData);
			{
				auto lock = _hudLock.AcquireSafe();
				_systemHud->Draw(_rendererHud.get(), size.Width, size.Height);
			}
			_watchHud->Draw(_rendererHud.get(), size.Width, size.Height);
			
			_emuHudSurface.IsDirty = _rendererHud->Draw(_emuHudSurface.Buffer, size, {}, 0, {}, true);
			_scriptHudSurface.IsDirty = DrawScriptHud(frame);

			if(forceRender || _needRedraw || _emuHudSurface.IsDirty || _scriptHudSurface.IsDirty) {
				_needRedraw = false;
				_renderer->Render(_emuHudSurface, _scriptHudSurface);
			}
		}
	}
}

FrameInfo VideoRenderer::GetEmuHudSize(FrameInfo baseFrameSize)
{
	FrameInfo size = {};
	if(_emu->GetSettings()->GetPreferences().HudSize == HudDisplaySize::Scaled) {
		//Adjust the system HUD's width to match the aspect ratio to allow text to be unstretched
		//(The Lua HUD is not adjusted to allow scripts that need to match positions on the game screen to work correctly.)
		double aspectRatio = _emu->GetSettings()->GetAspectRatio(_emu->GetRegion(), baseFrameSize);
		size.Width = (uint32_t)std::round(baseFrameSize.Height * aspectRatio);
		size.Height = baseFrameSize.Height;
	} else {
		size.Width = _rendererWidth / 2;
		size.Height = _rendererHeight / 2;
	}
	return size;
}

bool VideoRenderer::DrawScriptHud(RenderedFrame& frame)
{
	bool needRedraw = false;
	if(_lastScriptHudFrameNumber != frame.FrameNumber) {
		//Clear+draw HUD for scripts
		//-Only when frame number changes (to prevent the HUD from disappearing when paused, etc.)
		//-Only when commands are queued, otherwise skip drawing/clearing to avoid wasting CPU time
		if(_needScriptHudClear) {
			_scriptHudSurface.Clear();
			_needScriptHudClear = false;
			needRedraw = true;
		}

		if(_emu->GetScriptHud()->HasCommands()) {
			auto [size, overscan] = GetScriptHudSize();
			_emu->GetScriptHud()->Draw(_scriptHudSurface.Buffer, size, overscan, frame.FrameNumber, {});
			_needScriptHudClear = true;
			_lastScriptHudFrameNumber = frame.FrameNumber;
			needRedraw = true;
		}
	}
	return needRedraw;
}

std::pair<FrameInfo, OverscanDimensions> VideoRenderer::GetScriptHudSize()
{
	FrameInfo scriptHudSize = { _scriptHudSurface.Width, _scriptHudSurface.Height };
	OverscanDimensions overscan = _emu->GetSettings()->GetOverscan();
	overscan.Top *= _scriptHudScale;
	overscan.Bottom *= _scriptHudScale;
	overscan.Left *= _scriptHudScale;
	overscan.Right *= _scriptHudScale;
	return { scriptHudSize, overscan };
}

void VideoRenderer::SetWatchHudText(string text)
{
	if(_watchHud) {
		_watchHud->SetText(std::move(text));
		_needRedraw = true;
		_waitForRender.Signal();
	}
}

string VideoRenderer::GetWatchHudText()
{
	if(_watchHud) {
		return _watchHud->GetText();
	}
	return "";
}

void VideoRenderer::SetWatchHudData(string dataJson)
{
	if(_watchHud) {
		_watchHud->SetData(std::move(dataJson));
	}
}

string VideoRenderer::GetWatchHudData()
{
	if(_watchHud) {
		return _watchHud->GetData();
	}
	return "";
}

void VideoRenderer::UpdateFrame(RenderedFrame& frame)
{
	{
		auto lock = _hudLock.AcquireSafe();
		_systemHud->UpdateHud();
	}

	RenderedFrame activeFrame = frame;
	const uint32_t overlayMask = _debugOverlayMask.load(std::memory_order_relaxed);
	if(overlayMask != 0 && frame.FrameBuffer && frame.Width >= 256 && frame.Height >= 224) {
		ConsoleMemoryInfo wram = _emu->GetMemory(MemoryType::SnesWorkRam);
		if(wram.Memory && wram.Size >= 0x1000) {
			auto lock = _frameLock.AcquireSafe();
			const size_t pixelCount = static_cast<size_t>(frame.Width) * frame.Height;
			_debugOverlayBuffer.resize(pixelCount);
			memcpy(_debugOverlayBuffer.data(), frame.FrameBuffer, pixelCount * sizeof(uint32_t));
			HandheldOverlay::Render(
				_debugOverlayBuffer.data(),
				frame.Width,
				frame.Height,
				static_cast<const uint8_t*>(wram.Memory),
				wram.Size,
				overlayMask);
			activeFrame.FrameBuffer = _debugOverlayBuffer.data();
			_lastFrame = activeFrame;
		} else {
			auto lock = _frameLock.AcquireSafe();
			_lastFrame = activeFrame;
		}
	} else {
		auto lock = _frameLock.AcquireSafe();
		_lastFrame = activeFrame;
	}

	ProcessAviRecording(activeFrame);

	if(_renderer) {
		_renderer->UpdateFrame(activeFrame);
		_needRedraw = true;
		_waitForRender.Signal();
	}
}

void VideoRenderer::ClearFrame()
{
	if(_renderer) {
		_renderer->ClearFrame();
	}
}

void VideoRenderer::RegisterRenderingDevice(IRenderingDevice *renderer)
{
	_renderer = renderer;
	StartThread();
}

void VideoRenderer::UnregisterRenderingDevice(IRenderingDevice *renderer)
{
	if(_renderer == renderer) {
		StopThread();
		_renderer = nullptr;
	}
}

void VideoRenderer::ProcessAviRecording(RenderedFrame& frame)
{
	shared_ptr<IVideoRecorder> recorder = _recorder.lock();
	if(recorder) {
		if(!recorder->IsRecording()) {
			if(!recorder->StartRecording(frame.Width, frame.Height, 4, _emu->GetSettings()->GetAudioConfig().SampleRate, _emu->GetFps())) {
				StopRecording();
				return;
			}
		}

		if(_recorderOptions.RecordInputHud || _recorderOptions.RecordSystemHud) {
			//Calculate the scale needed for the HUD elements
			FrameInfo originalSize = _emu->GetVideoDecoder()->GetBaseFrameInfo(true);
			double scale = (double)frame.Height / originalSize.Height;
			FrameInfo scaledFrameSize = { (uint32_t)(frame.Width / scale), (uint32_t)(frame.Height / scale) };

			//Update the surface to match the frame's size
			_aviRecorderSurface.UpdateSize(frame.Width, frame.Height);
			
			//Copy the game screen
			memcpy(_aviRecorderSurface.Buffer, frame.FrameBuffer, frame.Width * frame.Height * sizeof(uint32_t));

			//Draw the system/input HUDs
			DebugHud hud;
			InputHud inputHud(_emu, &hud);
			if(_recorderOptions.RecordSystemHud) {
				_systemHud->Draw(&hud, scaledFrameSize.Width, scaledFrameSize.Height);
			}
			if(_recorderOptions.RecordInputHud) {
				inputHud.DrawControllers(scaledFrameSize, frame.InputData);
			}

			FrameInfo frameSize = { frame.Width, frame.Height };
			hud.Draw((uint32_t*)_aviRecorderSurface.Buffer, frameSize, {}, frame.FrameNumber, { scale, scale });

			//Record the final result
			if(!recorder->AddFrame(_aviRecorderSurface.Buffer, frame.Width, frame.Height, _emu->GetFps())) {
				StopRecording();
			}
		} else {
			//Only record the game screen
			if(!recorder->AddFrame(frame.FrameBuffer, frame.Width, frame.Height, _emu->GetFps())) {
				StopRecording();
			}
		}
	}
}

bool VideoRenderer::StartRecording(string filename, RecordAviOptions options)
{
	StopRecording();
	_recorderOptions = options;

	shared_ptr<IVideoRecorder> recorder;
	string ext = FolderUtilities::GetExtension(filename);
	for(char& c : ext) {
		c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
	}
#ifdef __ANDROID__
	if(ext == ".mp4") {
		recorder.reset(new Mp4VideoRecorder());
	} else
#endif
	if(options.Codec == VideoCodec::GIF || ext == ".gif") {
		recorder.reset(new GifRecorder());
	} else {
		recorder.reset(new AviRecorder(options.Codec, options.CompressionLevel));
	}

	if(recorder->Init(filename)) {
		_replayRecorder.reset();
		_recorder.reset(recorder);
		
		if(!options.RecordSystemHud) {
			//Only display message if not recording the system HUD (otherwise the message is always visible on the recording, which isn't ideal)
			MessageManager::DisplayMessage("VideoRecorder", "VideoRecorderStarted", filename);
		}
		return true;
	} else {
		MessageManager::DisplayMessage("VideoRecorder", "CouldNotWriteToFile", filename);
		return false;
	}
}

void VideoRenderer::AddRecordingSound(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate)
{
	shared_ptr<IVideoRecorder> recorder = _recorder.lock();
	if(recorder) {
		if(!recorder->AddSound(soundBuffer, sampleCount, sampleRate)) {
			StopRecording();
		}
	}
}

void VideoRenderer::StopRecording()
{
	shared_ptr<IVideoRecorder> recorder = _recorder.lock();
	bool wasReplay = _replayRecorder != nullptr;
	_replayRecorder.reset();
	_recorder.reset();
	_aviRecorderSurface.UpdateSize(0, 0);
	if(recorder) {
		recorder->StopRecording();
		if(!wasReplay) {
			MessageManager::DisplayMessage("VideoRecorder", "VideoRecorderStopped", recorder->GetOutputFile());
		}
	}
}

bool VideoRenderer::IsRecording()
{
	return _recorder != nullptr;
}

bool VideoRenderer::IsFileRecording()
{
	return _recorder != nullptr && _replayRecorder == nullptr;
}

string VideoRenderer::GetRecordingFile()
{
	if(_replayRecorder != nullptr) {
		return "";
	}
	shared_ptr<IVideoRecorder> recorder = _recorder.lock();
	return recorder ? recorder->GetOutputFile() : "";
}

bool VideoRenderer::StartReplayBuffer(uint32_t windowSeconds, RecordAviOptions options)
{
#ifdef __ANDROID__
	StopRecording();
	_recorderOptions = options;
	shared_ptr<Mp4VideoRecorder> recorder(new Mp4VideoRecorder());
	if(!recorder->InitReplay(windowSeconds)) {
		return false;
	}
	_replayRecorder.reset(recorder);
	_recorder.reset(recorder);
	return true;
#else
	(void)windowSeconds;
	(void)options;
	return false;
#endif
}

void VideoRenderer::StopReplayBuffer()
{
	if(_replayRecorder != nullptr) {
		StopRecording();
	}
}

bool VideoRenderer::IsReplayBufferActive()
{
	return _replayRecorder != nullptr;
}

uint32_t VideoRenderer::GetReplayBufferedSeconds()
{
#ifdef __ANDROID__
	shared_ptr<Mp4VideoRecorder> replay = _replayRecorder.lock();
	return replay ? replay->GetReplayBufferedSeconds() : 0;
#else
	return 0;
#endif
}

bool VideoRenderer::DumpReplayBuffer(string filename)
{
#ifdef __ANDROID__
	shared_ptr<Mp4VideoRecorder> replay = _replayRecorder.lock();
	return replay ? replay->DumpReplay(std::move(filename)) : false;
#else
	(void)filename;
	return false;
#endif
}

void VideoRenderer::SetDebugOverlayMask(uint32_t mask)
{
	_debugOverlayMask.store(mask & HandheldOverlay::kMaskAll, std::memory_order_relaxed);
	_needRedraw = true;
	_waitForRender.Signal();
}

uint32_t VideoRenderer::GetDebugOverlayMask()
{
	return _debugOverlayMask.load(std::memory_order_relaxed);
}

bool VideoRenderer::CopyLastFrame(vector<uint32_t>& outPixels, uint32_t& outWidth, uint32_t& outHeight)
{
	auto lock = _frameLock.AcquireSafe();
	if(!_lastFrame.FrameBuffer || _lastFrame.Width == 0 || _lastFrame.Height == 0) {
		return false;
	}
	const size_t count = static_cast<size_t>(_lastFrame.Width) * _lastFrame.Height;
	outWidth = _lastFrame.Width;
	outHeight = _lastFrame.Height;
	outPixels.resize(count);
	memcpy(outPixels.data(), _lastFrame.FrameBuffer, count * sizeof(uint32_t));
	return true;
}
