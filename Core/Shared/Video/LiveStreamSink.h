#pragma once
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Streams raw BGRA video frames and s16le stereo audio to two named pipes (FIFOs)
// for an external encoder such as ffmpeg. Writes happen on worker threads; when a
// reader falls behind, whole buffers are dropped so emulation never blocks.
class LiveStreamSink
{
public:
	struct ChannelStats
	{
		bool Connected = false;
		uint64_t BuffersSent = 0;
		uint64_t BuffersDropped = 0;
		uint64_t BytesSent = 0;
	};

private:
	class Channel
	{
	private:
		std::string _path;
		size_t _maxQueuedBytes;
		std::thread _thread;
		std::mutex _lock;
		std::condition_variable _signal;
		std::deque<std::vector<uint8_t>> _queue;
		size_t _queuedBytes = 0;
		std::atomic<bool> _stop = false;
		std::atomic<bool> _connected = false;
		std::atomic<uint64_t> _sent = 0;
		std::atomic<uint64_t> _dropped = 0;
		std::atomic<uint64_t> _bytesSent = 0;

		void Run();
		int OpenWriter();
		bool WriteAll(int fd, const std::vector<uint8_t>& data);

	public:
		Channel(std::string path, size_t maxQueuedBytes);
		~Channel();
		void Push(const void* data, size_t size);
		ChannelStats GetStats() const;
		const std::string& GetPath() const { return _path; }
	};

	std::unique_ptr<Channel> _video;
	std::unique_ptr<Channel> _audio;
	uint32_t _sourceWidth;
	uint32_t _sourceHeight;
	uint32_t _downscale;
	uint32_t _width;
	uint32_t _height;
	uint32_t _sampleRate;
	uint32_t _frameInterval;
	uint32_t _frameCounter = 0;
	std::vector<uint32_t> _scaled;

public:
	//Frames arrive at sourceWidth x sourceHeight and are box-filtered down by an integer factor before streaming
	LiveStreamSink(const std::string& videoPath, const std::string& audioPath, uint32_t sourceWidth, uint32_t sourceHeight, uint32_t downscale, uint32_t sampleRate, uint32_t frameInterval);
	~LiveStreamSink();

	static bool IsSupported();

	void PushFrame(const uint32_t* buffer, uint32_t width, uint32_t height);
	void PushAudio(const int16_t* samples, uint32_t sampleCount, uint32_t sampleRate);

	uint32_t GetWidth() const { return _width; }
	uint32_t GetHeight() const { return _height; }
	uint32_t GetSampleRate() const { return _sampleRate; }
	uint32_t GetFrameInterval() const { return _frameInterval; }
	uint32_t GetDownscale() const { return _downscale; }
	bool HasAudio() const { return _audio != nullptr; }
	const std::string& GetVideoPath() const { return _video->GetPath(); }
	ChannelStats GetVideoStats() const;
	ChannelStats GetAudioStats() const;
};
