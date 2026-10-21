#include "pch.h"
#include "Shared/Video/LiveStreamSink.h"
#include "Shared/MessageManager.h"

#ifndef _WIN32
#include <cerrno>
#include <csignal>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <unistd.h>
#endif

LiveStreamSink::Channel::Channel(std::string path, size_t maxQueuedBytes) : _path(std::move(path)), _maxQueuedBytes(maxQueuedBytes)
{
	_thread = std::thread(&Channel::Run, this);
}

LiveStreamSink::Channel::~Channel()
{
	_stop = true;
	_signal.notify_all();
	if(_thread.joinable()) {
		_thread.join();
	}
}

void LiveStreamSink::Channel::Push(const void* data, size_t size)
{
	if(!_connected) {
		return;
	}

	std::lock_guard<std::mutex> guard(_lock);
	if(_queuedBytes + size > _maxQueuedBytes) {
		_dropped++;
		return;
	}
	const uint8_t* bytes = static_cast<const uint8_t*>(data);
	_queue.emplace_back(bytes, bytes + size);
	_queuedBytes += size;
	_signal.notify_one();
}

LiveStreamSink::ChannelStats LiveStreamSink::Channel::GetStats() const
{
	ChannelStats stats;
	stats.Connected = _connected;
	stats.BuffersSent = _sent;
	stats.BuffersDropped = _dropped;
	stats.BytesSent = _bytesSent;
	return stats;
}

#ifndef _WIN32
int LiveStreamSink::Channel::OpenWriter()
{
	while(!_stop) {
		//O_NONBLOCK makes open() fail with ENXIO instead of blocking until a reader appears
		int fd = open(_path.c_str(), O_WRONLY | O_NONBLOCK);
		if(fd >= 0) {
			return fd;
		}
		if(errno != ENXIO && errno != EINTR) {
			MessageManager::Log("[LiveStream] Could not open " + _path + ": " + strerror(errno));
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(100));
	}
	return -1;
}

bool LiveStreamSink::Channel::WriteAll(int fd, const std::vector<uint8_t>& data)
{
	size_t written = 0;
	while(written < data.size()) {
		if(_stop) {
			return false;
		}
		pollfd pfd = { fd, POLLOUT, 0 };
		int ready = poll(&pfd, 1, 100);
		if(ready < 0 && errno != EINTR) {
			return false;
		}
		if(ready <= 0) {
			continue;
		}
		if(pfd.revents & (POLLERR | POLLHUP)) {
			return false;
		}
		ssize_t result = write(fd, data.data() + written, data.size() - written);
		if(result < 0) {
			if(errno == EAGAIN || errno == EINTR) {
				continue;
			}
			return false;
		}
		written += (size_t)result;
	}
	return true;
}

void LiveStreamSink::Channel::Run()
{
	//A reader that exits raises SIGPIPE on this thread; keep it from terminating the process
	sigset_t mask;
	sigemptyset(&mask);
	sigaddset(&mask, SIGPIPE);
	pthread_sigmask(SIG_BLOCK, &mask, nullptr);

	while(!_stop) {
		int fd = OpenWriter();
		if(fd < 0) {
			break;
		}
		_connected = true;

		while(!_stop) {
			std::vector<uint8_t> buffer;
			{
				std::unique_lock<std::mutex> guard(_lock);
				_signal.wait_for(guard, std::chrono::milliseconds(100), [this] { return _stop || !_queue.empty(); });
				if(_queue.empty()) {
					continue;
				}
				buffer = std::move(_queue.front());
				_queue.pop_front();
				_queuedBytes -= buffer.size();
			}

			if(!WriteAll(fd, buffer)) {
				break;
			}
			_sent++;
			_bytesSent += buffer.size();
		}

		_connected = false;
		close(fd);
		std::lock_guard<std::mutex> guard(_lock);
		_queue.clear();
		_queuedBytes = 0;
	}
}

bool LiveStreamSink::IsSupported()
{
	return true;
}
#else
int LiveStreamSink::Channel::OpenWriter() { return -1; }
bool LiveStreamSink::Channel::WriteAll(int, const std::vector<uint8_t>&) { return false; }
void LiveStreamSink::Channel::Run() {}
bool LiveStreamSink::IsSupported() { return false; }
#endif

LiveStreamSink::LiveStreamSink(const std::string& videoPath, const std::string& audioPath, uint32_t sourceWidth, uint32_t sourceHeight, uint32_t downscale, uint32_t sampleRate, uint32_t frameInterval)
	: _sourceWidth(sourceWidth), _sourceHeight(sourceHeight), _downscale(std::max<uint32_t>(1, downscale)),
	_sampleRate(sampleRate), _frameInterval(std::max<uint32_t>(1, frameInterval))
{
	_width = sourceWidth / _downscale;
	_height = sourceHeight / _downscale;
	size_t frameBytes = (size_t)_width * _height * 4;
	if(_downscale > 1) {
		_scaled.resize((size_t)_width * _height);
	}
	_video.reset(new Channel(videoPath, frameBytes * 6));
	if(!audioPath.empty()) {
		_audio.reset(new Channel(audioPath, (size_t)sampleRate * 4));
	}
}

LiveStreamSink::~LiveStreamSink()
{
	_video.reset();
	_audio.reset();
}

void LiveStreamSink::PushFrame(const uint32_t* buffer, uint32_t width, uint32_t height)
{
	if(width != _sourceWidth || height != _sourceHeight) {
		return;
	}
	if(_frameCounter++ % _frameInterval != 0) {
		return;
	}
	if(_downscale == 1) {
		_video->Push(buffer, (size_t)width * height * 4);
		return;
	}

	uint32_t n = _downscale;
	uint32_t area = n * n;
	for(uint32_t y = 0; y < _height; y++) {
		for(uint32_t x = 0; x < _width; x++) {
			uint32_t b = 0, g = 0, r = 0;
			for(uint32_t sy = 0; sy < n; sy++) {
				const uint32_t* row = buffer + (size_t)(y * n + sy) * width + x * n;
				for(uint32_t sx = 0; sx < n; sx++) {
					uint32_t px = row[sx];
					b += px & 0xFF;
					g += (px >> 8) & 0xFF;
					r += (px >> 16) & 0xFF;
				}
			}
			_scaled[(size_t)y * _width + x] = 0xFF000000 | ((r / area) << 16) | ((g / area) << 8) | (b / area);
		}
	}
	_video->Push(_scaled.data(), _scaled.size() * 4);
}

void LiveStreamSink::PushAudio(const int16_t* samples, uint32_t sampleCount, uint32_t sampleRate)
{
	if(_audio && sampleRate == _sampleRate) {
		_audio->Push(samples, (size_t)sampleCount * 4);
	}
}

LiveStreamSink::ChannelStats LiveStreamSink::GetVideoStats() const
{
	return _video->GetStats();
}

LiveStreamSink::ChannelStats LiveStreamSink::GetAudioStats() const
{
	return _audio ? _audio->GetStats() : ChannelStats();
}
