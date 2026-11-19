#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace HandheldCapture {

struct EncodedPacket
{
	bool IsVideo = true;
	bool IsKeyFrame = false;
	int64_t PtsUs = 0;
	uint32_t Flags = 0;
	std::vector<uint8_t> Data;
};

/**
 * Bounded circular buffer of encoded H.264 + AAC packets aligned on IDR keyframes.
 * Evicts oldest GOPs past the configured duration or memory cap, and produces
 * timestamp-rebased snapshots suitable for writing directly into an AMediaMuxer MP4.
 */
class ReplayRingBuffer
{
private:
	int64_t _windowUs;
	size_t _maxBytes;
	std::deque<EncodedPacket> _packets;
	size_t _totalBytes = 0;
	int64_t _latestVideoPtsUs = -1;

	void Trim()
	{
		if(_packets.empty()) {
			return;
		}

		// Keep up to windowUs + 2s of slack before trimming on GOP boundaries so a
		// Snapshot(windowUs) always finds a clean keyframe at or just before the cutoff.
		const int64_t retainMarginUs = 2000000LL;
		const int64_t targetStartUs = (_latestVideoPtsUs >= 0)
			? (_latestVideoPtsUs - _windowUs - retainMarginUs)
			: 0;

		auto findNextKeyframeAfter = [&](size_t startIdx) -> size_t {
			for(size_t i = startIdx; i < _packets.size(); ++i) {
				if(_packets[i].IsVideo && _packets[i].IsKeyFrame) {
					return i;
				}
			}
			return _packets.size();
		};

		// First: drop any packets before the first video keyframe so head is always decodable.
		size_t firstKf = findNextKeyframeAfter(0);
		if(firstKf > 0 && firstKf < _packets.size()) {
			DropPrefix(firstKf);
		}

		// Time-based GOP eviction: advance to a later keyframe as long as that later
		// keyframe is still at or before targetStartUs.
		while(!_packets.empty()) {
			size_t nextKf = findNextKeyframeAfter(1);
			if(nextKf >= _packets.size()) {
				break;
			}
			if(_latestVideoPtsUs >= 0 && _packets[nextKf].PtsUs <= targetStartUs) {
				DropPrefix(nextKf);
				continue;
			}
			if(_totalBytes > _maxBytes) {
				DropPrefix(nextKf);
				continue;
			}
			break;
		}

		// Hard memory cap fallback if a single GOP somehow exceeds maxBytes.
		while(_totalBytes > _maxBytes && _packets.size() > 1) {
			_totalBytes -= _packets.front().Data.size();
			_packets.pop_front();
		}
	}

	void DropPrefix(size_t count)
	{
		const size_t n = std::min(count, _packets.size());
		for(size_t i = 0; i < n; ++i) {
			_totalBytes -= _packets.front().Data.size();
			_packets.pop_front();
		}
	}

public:
	explicit ReplayRingBuffer(int64_t windowUs = 20000000LL, size_t maxBytes = 24u * 1024u * 1024u)
		: _windowUs(std::max<int64_t>(1000000LL, windowUs)),
		  _maxBytes(std::max<size_t>(256u * 1024u, maxBytes))
	{
	}

	void SetWindowUs(int64_t windowUs)
	{
		_windowUs = std::max<int64_t>(1000000LL, windowUs);
		Trim();
	}

	int64_t WindowUs() const { return _windowUs; }
	size_t PacketCount() const { return _packets.size(); }
	size_t TotalBytes() const { return _totalBytes; }

	int64_t BufferedDurationUs() const
	{
		if(_latestVideoPtsUs < 0) {
			return 0;
		}
		for(const EncodedPacket& pkt : _packets) {
			if(pkt.IsVideo && pkt.IsKeyFrame) {
				return std::max<int64_t>(0, _latestVideoPtsUs - pkt.PtsUs);
			}
		}
		return 0;
	}

	void Clear()
	{
		_packets.clear();
		_totalBytes = 0;
		_latestVideoPtsUs = -1;
	}

	void Push(EncodedPacket packet)
	{
		if(packet.Data.empty()) {
			return;
		}
		if(packet.IsVideo && packet.PtsUs > _latestVideoPtsUs) {
			_latestVideoPtsUs = packet.PtsUs;
		}
		_totalBytes += packet.Data.size();
		_packets.push_back(std::move(packet));
		Trim();
	}

	/**
	 * Extracts packets covering up to `requestedWindowUs` ending at the latest video frame,
	 * starting cleanly at an IDR video keyframe with all timestamps rebased to 0.
	 */
	std::vector<EncodedPacket> Snapshot(int64_t requestedWindowUs = 0) const
	{
		std::vector<EncodedPacket> out;
		if(_packets.empty() || _latestVideoPtsUs < 0) {
			return out;
		}

		const int64_t winUs = requestedWindowUs > 0 ? requestedWindowUs : _windowUs;
		const int64_t cutoffUs = _latestVideoPtsUs - winUs;

		// Pick the latest keyframe at or before cutoffUs so we capture at least winUs;
		// if none is that old, pick the earliest keyframe in the buffer.
		size_t startIdx = _packets.size();
		for(size_t i = 0; i < _packets.size(); ++i) {
			if(_packets[i].IsVideo && _packets[i].IsKeyFrame) {
				if(_packets[i].PtsUs <= cutoffUs) {
					startIdx = i;
				} else {
					if(startIdx == _packets.size()) {
						startIdx = i;
					}
					break;
				}
			}
		}
		if(startIdx >= _packets.size()) {
			return out;
		}

		const int64_t basePtsUs = _packets[startIdx].PtsUs;
		out.reserve(_packets.size() - startIdx);
		for(size_t i = startIdx; i < _packets.size(); ++i) {
			const EncodedPacket& src = _packets[i];
			if(src.PtsUs < basePtsUs) {
				continue;
			}
			EncodedPacket copy = src;
			copy.PtsUs = src.PtsUs - basePtsUs;
			out.push_back(std::move(copy));
		}
		return out;
	}
};

} // namespace HandheldCapture
