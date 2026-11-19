#include <cassert>
#include <iostream>
#include "ReplayRingBuffer.h"

using namespace HandheldCapture;

static EncodedPacket MakeVideo(int64_t ptsUs, bool keyFrame, size_t bytes = 1024)
{
	EncodedPacket pkt;
	pkt.IsVideo = true;
	pkt.IsKeyFrame = keyFrame;
	pkt.PtsUs = ptsUs;
	pkt.Flags = keyFrame ? 1u : 0u;
	pkt.Data.assign(bytes, keyFrame ? 0x65 : 0x41);
	return pkt;
}

static EncodedPacket MakeAudio(int64_t ptsUs, size_t bytes = 256)
{
	EncodedPacket pkt;
	pkt.IsVideo = false;
	pkt.IsKeyFrame = false;
	pkt.PtsUs = ptsUs;
	pkt.Flags = 0u;
	pkt.Data.assign(bytes, 0xAA);
	return pkt;
}

static void TestKeyframeAlignmentAndRebase()
{
	ReplayRingBuffer ring(5'000'000LL, 1024 * 1024);
	// Leading non-keyframe & audio should be discarded cleanly.
	ring.Push(MakeAudio(100'000));
	ring.Push(MakeVideo(200'000, false));
	assert(ring.Snapshot().empty());

	// Push 10 seconds of 1-second GOPs (10 keyframes at t=1..10s, P-frames at +0.5s, audio at +0.25s).
	for(int s = 1; s <= 10; ++s) {
		const int64_t t = s * 1'000'000LL;
		ring.Push(MakeVideo(t, true, 2000));
		ring.Push(MakeAudio(t + 250'000, 300));
		ring.Push(MakeVideo(t + 500'000, false, 800));
	}

	auto snap = ring.Snapshot(5'000'000LL);
	assert(!snap.empty());
	assert(snap.front().IsVideo && snap.front().IsKeyFrame);
	assert(snap.front().PtsUs == 0);
	// Latest video PTS is 10.5s; 5s cutoff is 5.5s -> latest keyframe <= 5.5s is t=5.0s -> span 5.5s.
	int64_t maxPts = 0;
	for(const auto& p : snap) {
		assert(p.PtsUs >= 0);
		if(p.PtsUs > maxPts) maxPts = p.PtsUs;
	}
	assert(maxPts == 5'500'000LL);
}

static void TestMemoryCapEvictsOldGops()
{
	ReplayRingBuffer ring(60'000'000LL, 256u * 1024u);
	for(int s = 0; s < 20; ++s) {
		ring.Push(MakeVideo(s * 1'000'000LL, true, 40 * 1024));
		ring.Push(MakeVideo(s * 1'000'000LL + 500'000, false, 10 * 1024));
	}
	assert(ring.TotalBytes() <= 256u * 1024u);
	auto snap = ring.Snapshot();
	assert(!snap.empty());
	assert(snap.front().IsVideo && snap.front().IsKeyFrame);
	assert(snap.front().PtsUs == 0);
}

static void TestShortClipReturnsFromEarliestKeyframe()
{
	ReplayRingBuffer ring(20'000'000LL);
	ring.Push(MakeVideo(3'000'000LL, true, 512));
	ring.Push(MakeAudio(2'900'000LL, 128)); // earlier than base keyframe -> skipped in snapshot
	ring.Push(MakeAudio(3'100'000LL, 128));
	ring.Push(MakeVideo(3'500'000LL, false, 256));

	auto snap = ring.Snapshot(20'000'000LL);
	assert(snap.size() == 3);
	assert(snap[0].IsVideo && snap[0].PtsUs == 0);
	assert(!snap[1].IsVideo && snap[1].PtsUs == 100'000LL);
	assert(snap[2].IsVideo && snap[2].PtsUs == 500'000LL);
}

int main()
{
	TestKeyframeAlignmentAndRebase();
	TestMemoryCapEvictsOldGops();
	TestShortClipReturnsFromEarliestKeyframe();
	std::cout << "replay_ring_buffer_test OK (3 suites)\n";
	return 0;
}
