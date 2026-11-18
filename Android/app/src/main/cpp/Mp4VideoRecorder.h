#pragma once

#include "pch.h"
#include "Utilities/Video/IVideoRecorder.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

class Mp4VideoRecorder final : public IVideoRecorder
{
public:
	Mp4VideoRecorder();
	~Mp4VideoRecorder() override;

	bool Init(string filename) override;
	bool StartRecording(uint32_t width, uint32_t height, uint32_t bpp, uint32_t audioSampleRate, double fps) override;
	void StopRecording() override;

	bool AddFrame(void* frameBuffer, uint32_t width, uint32_t height, double fps) override;
	bool AddSound(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate) override;

	bool IsRecording() override;
	string GetOutputFile() override;

private:
	struct PendingFrame
	{
		std::vector<uint32_t> Pixels;
		uint32_t Width = 0;
		uint32_t Height = 0;
		int64_t PtsUs = 0;
	};

	void EncoderLoop();
	void CleanupLocked();

	string _outputFile;
	int _fd = -1;

	uint32_t _videoWidth = 512;
	uint32_t _videoHeight = 448;
	uint32_t _videoStride = 512;
	uint32_t _videoSliceHeight = 448;
	bool _videoSemiPlanar = true;
	uint32_t _audioSampleRate = 48000;
	double _fps = 60.0988;

	std::atomic<bool> _recording{ false };
	std::atomic<bool> _failed{ false };
	bool _stopRequested = false;
	bool _hasAudio = true;

	std::mutex _lifecycleMutex;
	std::mutex _queueMutex;
	std::condition_variable _queueCv;
	std::deque<PendingFrame> _videoQueue;
	std::deque<int16_t> _audioQueue;
	uint64_t _videoFrameCount = 0;
	uint64_t _audioFramesQueued = 0;

	std::thread _worker;
};
