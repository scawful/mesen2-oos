#include "pch.h"
#include "Mp4VideoRecorder.h"
#include "YuvFrameConverter.h"

#ifdef __ANDROID__
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace {

constexpr const char* kLogTag = "MesenMp4Recorder";
constexpr int32_t kColorFormatYuv420Planar = 19;
constexpr int32_t kColorFormatYuv420SemiPlanar = 21;
constexpr size_t kMaxPendingFrames = 8;
constexpr size_t kAudioSamplesPerPacket = 1024; // stereo frames per AAC-LC frame
constexpr uint32_t kVideoBitrateBps = 4000000;
constexpr uint32_t kAudioBitrateBps = 128000;

using GetInputFormatFn = AMediaFormat* (*)(AMediaCodec*);

GetInputFormatFn ResolveGetInputFormat()
{
	static GetInputFormatFn fn = reinterpret_cast<GetInputFormatFn>(dlsym(RTLD_DEFAULT, "AMediaCodec_getInputFormat"));
	return fn;
}

AMediaFormat* CreateAvcFormat(uint32_t width, uint32_t height, int32_t colorFormat, int32_t fps)
{
	AMediaFormat* fmt = AMediaFormat_new();
	if(!fmt) {
		return nullptr;
	}
	AMediaFormat_setString(fmt, "mime", "video/avc");
	AMediaFormat_setInt32(fmt, "width", static_cast<int32_t>(width));
	AMediaFormat_setInt32(fmt, "height", static_cast<int32_t>(height));
	AMediaFormat_setInt32(fmt, "color-format", colorFormat);
	AMediaFormat_setInt32(fmt, "bitrate", static_cast<int32_t>(kVideoBitrateBps));
	AMediaFormat_setInt32(fmt, "frame-rate", fps);
	AMediaFormat_setInt32(fmt, "i-frame-interval", 1);
	return fmt;
}

AMediaFormat* CreateAacFormat(uint32_t sampleRate)
{
	AMediaFormat* fmt = AMediaFormat_new();
	if(!fmt) {
		return nullptr;
	}
	AMediaFormat_setString(fmt, "mime", "audio/mp4a-latm");
	AMediaFormat_setInt32(fmt, "sample-rate", static_cast<int32_t>(sampleRate));
	AMediaFormat_setInt32(fmt, "channel-count", 2);
	AMediaFormat_setInt32(fmt, "bitrate", static_cast<int32_t>(kAudioBitrateBps));
	AMediaFormat_setInt32(fmt, "aac-profile", 2); // AAC-LC
	return fmt;
}

struct PendingMuxPacket
{
	bool IsVideo = true;
	std::vector<uint8_t> Data;
	int64_t PtsUs = 0;
	uint32_t Flags = 0;
};

} // namespace

Mp4VideoRecorder::Mp4VideoRecorder() = default;

Mp4VideoRecorder::~Mp4VideoRecorder()
{
	StopRecording();
}

bool Mp4VideoRecorder::Init(string filename)
{
	std::lock_guard<std::mutex> lock(_lifecycleMutex);
	if(filename.empty()) {
		return false;
	}
	int testFd = ::open(filename.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
	if(testFd < 0) {
		__android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to open output file %s: %s", filename.c_str(), strerror(errno));
		return false;
	}
	::close(testFd);
	::unlink(filename.c_str());
	_outputFile = std::move(filename);
	return true;
}

bool Mp4VideoRecorder::StartRecording(uint32_t /*width*/, uint32_t /*height*/, uint32_t /*bpp*/, uint32_t audioSampleRate, double fps)
{
	std::lock_guard<std::mutex> lock(_lifecycleMutex);
	if(_recording.load(std::memory_order_acquire)) {
		return true;
	}
	if(_outputFile.empty()) {
		return false;
	}

	_videoWidth = HandheldCapture::kDefaultRecordWidth;
	_videoHeight = HandheldCapture::kDefaultRecordHeight;
	_videoStride = _videoWidth;
	_videoSliceHeight = _videoHeight;
	_videoSemiPlanar = true;
	_audioSampleRate = audioSampleRate > 0 ? audioSampleRate : 48000;
	_fps = fps > 1.0 ? fps : 60.0988;
	_failed.store(false, std::memory_order_release);

	{
		std::lock_guard<std::mutex> qlock(_queueMutex);
		_stopRequested = false;
		_videoQueue.clear();
		_audioQueue.clear();
		_videoFrameCount = 0;
		_audioFramesQueued = 0;
	}

	_fd = ::open(_outputFile.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
	if(_fd < 0) {
		__android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create MP4 file %s: %s", _outputFile.c_str(), strerror(errno));
		return false;
	}

	_hasAudio = true;
	_recording.store(true, std::memory_order_release);
	_worker = std::thread(&Mp4VideoRecorder::EncoderLoop, this);
	return true;
}

void Mp4VideoRecorder::StopRecording()
{
	std::lock_guard<std::mutex> lock(_lifecycleMutex);
	{
		std::lock_guard<std::mutex> qlock(_queueMutex);
		_stopRequested = true;
	}
	_recording.store(false, std::memory_order_release);
	_queueCv.notify_all();

	if(_worker.joinable()) {
		_worker.join();
	}
	CleanupLocked();
}

void Mp4VideoRecorder::CleanupLocked()
{
	if(_fd >= 0) {
		::fsync(_fd);
		::close(_fd);
		_fd = -1;
	}
	std::lock_guard<std::mutex> qlock(_queueMutex);
	_videoQueue.clear();
	_audioQueue.clear();
}

bool Mp4VideoRecorder::AddFrame(void* frameBuffer, uint32_t width, uint32_t height, double fps)
{
	if(!_recording.load(std::memory_order_acquire) || _failed.load(std::memory_order_acquire)) {
		return !_failed.load(std::memory_order_acquire);
	}
	if(!frameBuffer || width == 0 || height == 0) {
		return true;
	}

	const double effectiveFps = fps > 1.0 ? fps : _fps;
	const uint32_t* src = static_cast<const uint32_t*>(frameBuffer);
	const size_t pixelCount = static_cast<size_t>(width) * height;

	std::lock_guard<std::mutex> qlock(_queueMutex);
	if(_stopRequested) {
		return true;
	}
	const int64_t ptsUs = static_cast<int64_t>(std::llround(static_cast<double>(_videoFrameCount) * (1000000.0 / effectiveFps)));
	++_videoFrameCount;

	if(_videoQueue.size() >= kMaxPendingFrames) {
		return true; // Drop frame rather than blocking the emulation/decode thread.
	}

	PendingFrame frame;
	frame.Width = width;
	frame.Height = height;
	frame.PtsUs = ptsUs;
	frame.Pixels.assign(src, src + pixelCount);
	_videoQueue.push_back(std::move(frame));
	_queueCv.notify_one();
	return true;
}

bool Mp4VideoRecorder::AddSound(int16_t* soundBuffer, uint32_t sampleCount, uint32_t sampleRate)
{
	if(!_recording.load(std::memory_order_acquire) || _failed.load(std::memory_order_acquire)) {
		return !_failed.load(std::memory_order_acquire);
	}
	if(!soundBuffer || sampleCount == 0 || !_hasAudio) {
		return true;
	}
	if(sampleRate > 0 && sampleRate != _audioSampleRate) {
		return true;
	}

	const size_t totalInt16 = static_cast<size_t>(sampleCount) * 2;
	const size_t maxInt16 = static_cast<size_t>(_audioSampleRate) * 2; // 1 second stereo cap

	std::lock_guard<std::mutex> qlock(_queueMutex);
	if(_stopRequested) {
		return true;
	}
	if(_audioQueue.size() + totalInt16 > maxInt16) {
		return true;
	}
	_audioQueue.insert(_audioQueue.end(), soundBuffer, soundBuffer + totalInt16);
	_queueCv.notify_one();
	return true;
}

bool Mp4VideoRecorder::IsRecording()
{
	return _recording.load(std::memory_order_acquire);
}

string Mp4VideoRecorder::GetOutputFile()
{
	return _outputFile;
}

void Mp4VideoRecorder::EncoderLoop()
{
	const int32_t fpsInt = std::max<int32_t>(1, static_cast<int32_t>(std::llround(_fps)));

	auto configureVideo = [&](int32_t colorFormat) -> AMediaCodec* {
		AMediaCodec* codec = AMediaCodec_createEncoderByType("video/avc");
		if(!codec) {
			return nullptr;
		}
		AMediaFormat* fmt = CreateAvcFormat(_videoWidth, _videoHeight, colorFormat, fpsInt);
		if(!fmt) {
			AMediaCodec_delete(codec);
			return nullptr;
		}
		media_status_t st = AMediaCodec_configure(codec, fmt, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
		AMediaFormat_delete(fmt);
		if(st != AMEDIA_OK) {
			AMediaCodec_delete(codec);
			return nullptr;
		}
		return codec;
	};

	AMediaCodec* videoCodec = configureVideo(kColorFormatYuv420SemiPlanar);
	if(videoCodec) {
		_videoSemiPlanar = true;
	} else {
		videoCodec = configureVideo(kColorFormatYuv420Planar);
		_videoSemiPlanar = false;
	}

	if(!videoCodec || AMediaCodec_start(videoCodec) != AMEDIA_OK) {
		__android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to configure/start H.264 encoder");
		if(videoCodec) {
			AMediaCodec_delete(videoCodec);
		}
		_failed.store(true, std::memory_order_release);
		_recording.store(false, std::memory_order_release);
		return;
	}

	if(auto getInFmt = ResolveGetInputFormat()) {
		AMediaFormat* inFmt = getInFmt(videoCodec);
		if(inFmt) {
			int32_t stride = 0;
			int32_t slice = 0;
			int32_t colorFmt = 0;
			if(AMediaFormat_getInt32(inFmt, "stride", &stride) && stride >= static_cast<int32_t>(_videoWidth)) {
				_videoStride = static_cast<uint32_t>(stride);
			}
			if(AMediaFormat_getInt32(inFmt, "slice-height", &slice) && slice >= static_cast<int32_t>(_videoHeight)) {
				_videoSliceHeight = static_cast<uint32_t>(slice);
			}
			if(AMediaFormat_getInt32(inFmt, "color-format", &colorFmt)) {
				if(colorFmt == kColorFormatYuv420Planar) {
					_videoSemiPlanar = false;
				} else if(colorFmt == kColorFormatYuv420SemiPlanar) {
					_videoSemiPlanar = true;
				}
			}
			AMediaFormat_delete(inFmt);
		}
	}

	AMediaCodec* audioCodec = AMediaCodec_createEncoderByType("audio/mp4a-latm");
	if(audioCodec) {
		AMediaFormat* aFmt = CreateAacFormat(_audioSampleRate);
		if(!aFmt || AMediaCodec_configure(audioCodec, aFmt, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) != AMEDIA_OK
			|| AMediaCodec_start(audioCodec) != AMEDIA_OK) {
			__android_log_print(ANDROID_LOG_WARN, kLogTag, "AAC encoder unavailable; recording video-only MP4");
			AMediaCodec_delete(audioCodec);
			audioCodec = nullptr;
			_hasAudio = false;
		}
		if(aFmt) {
			AMediaFormat_delete(aFmt);
		}
	} else {
		_hasAudio = false;
	}

	AMediaMuxer* muxer = AMediaMuxer_new(_fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
	if(!muxer) {
		__android_log_print(ANDROID_LOG_ERROR, kLogTag, "AMediaMuxer_new failed");
		AMediaCodec_stop(videoCodec);
		AMediaCodec_delete(videoCodec);
		if(audioCodec) {
			AMediaCodec_stop(audioCodec);
			AMediaCodec_delete(audioCodec);
		}
		_failed.store(true, std::memory_order_release);
		_recording.store(false, std::memory_order_release);
		return;
	}

	AMediaFormat* videoOutFmt = nullptr;
	AMediaFormat* audioOutFmt = nullptr;
	ssize_t videoTrackIdx = -1;
	ssize_t audioTrackIdx = -1;
	bool muxerStarted = false;
	std::vector<PendingMuxPacket> pendingPackets;
	uint32_t videoPacketsBeforeStart = 0;

	bool videoInputEos = false;
	bool audioInputEos = !audioCodec;
	bool videoOutputEos = false;
	bool audioOutputEos = !audioCodec;
	bool stopStoppingAudio = false;

	auto maybeStartMuxer = [&](bool forceVideoOnly) {
		if(muxerStarted || !videoOutFmt) {
			return;
		}
		if(audioCodec && _hasAudio && !audioOutFmt && !forceVideoOnly) {
			return;
		}
		videoTrackIdx = AMediaMuxer_addTrack(muxer, videoOutFmt);
		if(videoTrackIdx < 0) {
			_failed.store(true, std::memory_order_release);
			return;
		}
		if(audioOutFmt && _hasAudio) {
			audioTrackIdx = AMediaMuxer_addTrack(muxer, audioOutFmt);
			if(audioTrackIdx < 0) {
				_hasAudio = false;
			}
		} else {
			_hasAudio = false;
		}
		if(AMediaMuxer_start(muxer) != AMEDIA_OK) {
			_failed.store(true, std::memory_order_release);
			return;
		}
		muxerStarted = true;
		for(const PendingMuxPacket& pkt : pendingPackets) {
			ssize_t track = pkt.IsVideo ? videoTrackIdx : audioTrackIdx;
			if(track < 0) {
				continue;
			}
			AMediaCodecBufferInfo info{};
			info.offset = 0;
			info.size = static_cast<int32_t>(pkt.Data.size());
			info.presentationTimeUs = pkt.PtsUs;
			info.flags = pkt.Flags;
			AMediaMuxer_writeSampleData(muxer, static_cast<size_t>(track), pkt.Data.data(), &info);
		}
		pendingPackets.clear();
	};

	auto drainCodec = [&](AMediaCodec* codec, bool isVideo, bool& outputEos, AMediaFormat*& outFmt) {
		if(!codec || outputEos) {
			return;
		}
		while(true) {
			AMediaCodecBufferInfo info{};
			ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
			if(outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
				break;
			}
			if(outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
				continue;
			}
			if(outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
				if(outFmt) {
					AMediaFormat_delete(outFmt);
				}
				outFmt = AMediaCodec_getOutputFormat(codec);
				maybeStartMuxer(false);
				continue;
			}
			if(outIdx < 0) {
				break;
			}

			size_t bufSize = 0;
			uint8_t* buf = AMediaCodec_getOutputBuffer(codec, static_cast<size_t>(outIdx), &bufSize);
			const bool isConfig = (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
			const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
			if(buf && info.size > 0 && !isConfig) {
				const uint8_t* ptr = buf + std::max<int32_t>(0, info.offset);
				const uint32_t cleanFlags = info.flags & (AMEDIACODEC_BUFFER_FLAG_KEY_FRAME | AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
				if(muxerStarted) {
					ssize_t track = isVideo ? videoTrackIdx : audioTrackIdx;
					if(track >= 0) {
						AMediaCodecBufferInfo writeInfo{};
						writeInfo.offset = 0;
						writeInfo.size = info.size;
						writeInfo.presentationTimeUs = info.presentationTimeUs;
						writeInfo.flags = cleanFlags;
						AMediaMuxer_writeSampleData(muxer, static_cast<size_t>(track), ptr, &writeInfo);
					}
				} else {
					PendingMuxPacket pkt;
					pkt.IsVideo = isVideo;
					pkt.Data.assign(ptr, ptr + info.size);
					pkt.PtsUs = info.presentationTimeUs;
					pkt.Flags = cleanFlags;
					pendingPackets.push_back(std::move(pkt));
					if(isVideo) {
						++videoPacketsBeforeStart;
						if(videoPacketsBeforeStart >= 45 && !audioOutFmt) {
							maybeStartMuxer(true);
						}
					}
				}
			}
			AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outIdx), false);
			if(isEos) {
				outputEos = true;
				break;
			}
		}
	};

	PendingFrame heldFrame;
	bool hasHeldFrame = false;
	std::vector<int16_t> heldAudio;
	size_t heldAudioOffset = 0;

	auto eosDeadline = std::chrono::steady_clock::time_point::max();
	while(!_failed.load(std::memory_order_acquire)) {
		bool stopping = false;

		{
			std::unique_lock<std::mutex> qlock(_queueMutex);
			if(!hasHeldFrame && _videoQueue.empty()
				&& heldAudioOffset >= heldAudio.size() && _audioQueue.size() < kAudioSamplesPerPacket * 2
				&& !_stopRequested) {
				_queueCv.wait_for(qlock, std::chrono::milliseconds(8));
			}
			stopping = _stopRequested;
			if(!hasHeldFrame && !_videoQueue.empty()) {
				heldFrame = std::move(_videoQueue.front());
				_videoQueue.pop_front();
				hasHeldFrame = true;
			}
			if(heldAudioOffset >= heldAudio.size() && audioCodec && _hasAudio) {
				heldAudio.clear();
				heldAudioOffset = 0;
				const size_t minInts = stopping ? 2 : (kAudioSamplesPerPacket * 2);
				if(_audioQueue.size() >= minInts) {
					const size_t take = std::min(_audioQueue.size(), kAudioSamplesPerPacket * 2);
					heldAudio.assign(_audioQueue.begin(), _audioQueue.begin() + static_cast<ptrdiff_t>(take));
					_audioQueue.erase(_audioQueue.begin(), _audioQueue.begin() + static_cast<ptrdiff_t>(take));
				}
			}
		}

		if(hasHeldFrame && !videoInputEos) {
			ssize_t inIdx = AMediaCodec_dequeueInputBuffer(videoCodec, 5000);
			if(inIdx >= 0) {
				size_t outSize = 0;
				uint8_t* inBuf = AMediaCodec_getInputBuffer(videoCodec, static_cast<size_t>(inIdx), &outSize);
				const size_t expectedSize = static_cast<size_t>(_videoStride) * _videoSliceHeight * 3 / 2;
				const size_t useSize = std::min(outSize, expectedSize);
				if(inBuf && HandheldCapture::ConvertArgbToYuv420(
					heldFrame.Pixels.data(), heldFrame.Width, heldFrame.Height,
					_videoWidth, _videoHeight, _videoStride, _videoSliceHeight,
					_videoSemiPlanar, inBuf, outSize)) {
					AMediaCodec_queueInputBuffer(
						videoCodec, static_cast<size_t>(inIdx), 0, useSize,
						static_cast<uint64_t>(std::max<int64_t>(0, heldFrame.PtsUs)), 0);
				} else {
					AMediaCodec_queueInputBuffer(videoCodec, static_cast<size_t>(inIdx), 0, 0, 0, 0);
				}
				hasHeldFrame = false;
			}
		}

		if(heldAudioOffset < heldAudio.size() && audioCodec && _hasAudio && !audioInputEos) {
			ssize_t inIdx = AMediaCodec_dequeueInputBuffer(audioCodec, 2000);
			if(inIdx >= 0) {
				size_t outSize = 0;
				uint8_t* inBuf = AMediaCodec_getInputBuffer(audioCodec, static_cast<size_t>(inIdx), &outSize);
				const size_t remainingInts = heldAudio.size() - heldAudioOffset;
				const size_t maxIntsFit = (outSize / (sizeof(int16_t) * 2)) * 2;
				const size_t sendInts = std::min(remainingInts, maxIntsFit);
				if(inBuf && sendInts >= 2) {
					const size_t bytes = sendInts * sizeof(int16_t);
					std::memcpy(inBuf, heldAudio.data() + heldAudioOffset, bytes);
					const int64_t audioPtsUs = static_cast<int64_t>(
						(_audioFramesQueued * 1000000ULL) / _audioSampleRate);
					_audioFramesQueued += sendInts / 2;
					heldAudioOffset += sendInts;
					AMediaCodec_queueInputBuffer(
						audioCodec, static_cast<size_t>(inIdx), 0, bytes,
						static_cast<uint64_t>(audioPtsUs), 0);
				} else {
					AMediaCodec_queueInputBuffer(audioCodec, static_cast<size_t>(inIdx), 0, 0, 0, 0);
					heldAudioOffset = heldAudio.size();
				}
			}
		}

		if(stopping) {
			if(eosDeadline == std::chrono::steady_clock::time_point::max()) {
				eosDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(2000);
			}
			bool queuesEmpty = false;
			{
				std::lock_guard<std::mutex> qlock(_queueMutex);
				queuesEmpty = !hasHeldFrame && _videoQueue.empty()
					&& (heldAudioOffset >= heldAudio.size() && (_audioQueue.empty() || !audioCodec || !_hasAudio));
			}
			if(queuesEmpty) {
				if(!videoInputEos) {
					ssize_t inIdx = AMediaCodec_dequeueInputBuffer(videoCodec, 5000);
					if(inIdx >= 0) {
						const int64_t eosPts = static_cast<int64_t>(std::llround(static_cast<double>(_videoFrameCount) * (1000000.0 / _fps)));
						AMediaCodec_queueInputBuffer(videoCodec, static_cast<size_t>(inIdx), 0, 0,
							static_cast<uint64_t>(eosPts), AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
						videoInputEos = true;
					}
				}
				if(audioCodec && _hasAudio && !audioInputEos && !stopStoppingAudio) {
					ssize_t inIdx = AMediaCodec_dequeueInputBuffer(audioCodec, 2000);
					if(inIdx >= 0) {
						const int64_t eosPts = static_cast<int64_t>((_audioFramesQueued * 1000000ULL) / _audioSampleRate);
						AMediaCodec_queueInputBuffer(audioCodec, static_cast<size_t>(inIdx), 0, 0,
							static_cast<uint64_t>(eosPts), AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
						audioInputEos = true;
					}
				} else if(!_hasAudio && !stopStoppingAudio) {
					audioOutputEos = true;
					stopStoppingAudio = true;
				}
			}
		}

		drainCodec(videoCodec, true, videoOutputEos, videoOutFmt);
		if(audioCodec && _hasAudio) {
			drainCodec(audioCodec, false, audioOutputEos, audioOutFmt);
		}

		if(stopping && videoOutputEos && (audioOutputEos || !audioCodec || !_hasAudio)) {
			break;
		}
		if(stopping && std::chrono::steady_clock::now() >= eosDeadline) {
			__android_log_print(ANDROID_LOG_WARN, kLogTag, "EOS drain timed out; finalizing MP4");
			break;
		}
	}

	if(!muxerStarted && videoOutFmt) {
		maybeStartMuxer(true);
	}
	if(muxerStarted) {
		AMediaMuxer_stop(muxer);
	}
	AMediaMuxer_delete(muxer);

	if(videoOutFmt) {
		AMediaFormat_delete(videoOutFmt);
	}
	if(audioOutFmt) {
		AMediaFormat_delete(audioOutFmt);
	}

	AMediaCodec_stop(videoCodec);
	AMediaCodec_delete(videoCodec);
	if(audioCodec) {
		AMediaCodec_stop(audioCodec);
		AMediaCodec_delete(audioCodec);
	}

	if(!muxerStarted) {
		::unlink(_outputFile.c_str());
		_failed.store(true, std::memory_order_release);
	}
}

#else

Mp4VideoRecorder::Mp4VideoRecorder() = default;
Mp4VideoRecorder::~Mp4VideoRecorder() = default;
bool Mp4VideoRecorder::Init(string) { return false; }
bool Mp4VideoRecorder::StartRecording(uint32_t, uint32_t, uint32_t, uint32_t, double) { return false; }
void Mp4VideoRecorder::StopRecording() {}
bool Mp4VideoRecorder::AddFrame(void*, uint32_t, uint32_t, double) { return false; }
bool Mp4VideoRecorder::AddSound(int16_t*, uint32_t, uint32_t) { return false; }
bool Mp4VideoRecorder::IsRecording() { return false; }
string Mp4VideoRecorder::GetOutputFile() { return {}; }
void Mp4VideoRecorder::EncoderLoop() {}
void Mp4VideoRecorder::CleanupLocked() {}

#endif
