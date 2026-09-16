#include "../app/src/main/cpp/HandheldFramePresenter.h"

#include <algorithm>
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <thread>

using HandheldVideo::Frame;
using HandheldVideo::FrameMailbox;
using HandheldVideo::PresentationState;
using HandheldVideo::SurfaceState;

static void Check(bool condition, const char* message)
{
	if(!condition) {
		throw std::runtime_error(message);
	}
}

static void TestCallbackOwnsPixels()
{
	FrameMailbox mailbox;
	uint32_t borrowed[] = {1, 2, 3, 4};
	Check(mailbox.Publish(borrowed, 2, 2), "first callback must publish");
	borrowed[0] = 99; // The core is free to reuse its callback buffer now.
	Frame snapshot;
	Check(mailbox.CopyLatest(snapshot), "first copy missing");
	Check(snapshot.Pixels[0] == 1, "callback retained a borrowed pointer");
	Check(mailbox.Publish(borrowed, 2, 2), "same-dimension changed pixels were lost");
	Check(mailbox.CopyLatest(snapshot) && snapshot.Pixels[0] == 99, "changed pixels did not arrive");
}

static void TestRepeatedPausedCallbacks()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	const uint32_t pixels[] = {0xFF123456, 0xFF654321};
	Check(mailbox.Publish(pixels, 2, 1), "initial pixels missing");
	mailbox.CopyLatest(snapshot);
	presentation.Uploaded(snapshot);
	presentation.Presented(snapshot);
	const auto* allocation = snapshot.Pixels.data();
	const auto generation = snapshot.Generation;
	for(int i = 0; i < 1000; ++i) {
		Check(!mailbox.Publish(pixels, 2, 1), "unchanged callback created a generation");
		Check(!mailbox.CopyLatest(snapshot), "unchanged callback recopied pixels");
		Check(!presentation.NeedsUpload(snapshot), "paused frame requested another upload");
		Check(!presentation.NeedsPresent(snapshot), "paused frame requested another present");
	}
	Check(snapshot.Generation == generation, "paused generation changed");
	Check(snapshot.Pixels.data() == allocation, "paused staging allocation changed");
}

static void TestSameSizeStagingReusedAndLatestWins()
{
	FrameMailbox mailbox;
	Frame snapshot;
	uint32_t pixels[] = {1, 2, 3, 4};
	mailbox.Publish(pixels, 2, 2);
	mailbox.CopyLatest(snapshot);
	const auto* allocation = snapshot.Pixels.data();
	for(uint32_t value = 5; value < 10; ++value) {
		pixels[3] = value;
		mailbox.Publish(pixels, 2, 2);
	}
	Check(mailbox.CopyLatest(snapshot), "latest pixels missing");
	Check(snapshot.Pixels[3] == 9, "mailbox returned an obsolete queued frame");
	Check(snapshot.Pixels.data() == allocation, "same-size copy reallocated staging");
}

static void TestDimensionChangeWithIdenticalBytes()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	const uint32_t pixels[] = {1, 2, 3, 4};
	mailbox.Publish(pixels, 2, 2);
	mailbox.CopyLatest(snapshot);
	presentation.Uploaded(snapshot);
	presentation.Presented(snapshot);
	Check(mailbox.Publish(pixels, 4, 1), "equal-byte-count dimension change was ignored");
	mailbox.CopyLatest(snapshot);
	Check(snapshot.Width == 4 && snapshot.Height == 1, "dimension change was not copied");
	Check(presentation.NeedsUpload(snapshot) && presentation.NeedsPresent(snapshot), "dimension change did not invalidate output");
}

static void TestForcedRedrawDoesNotUpload()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	const uint32_t pixels[] = {42};
	mailbox.Publish(pixels, 1, 1);
	mailbox.CopyLatest(snapshot);
	presentation.Uploaded(snapshot);
	presentation.Presented(snapshot);
	// Expose, resize and fit-mode changes do not replace the window surface.
	for(int i = 0; i < 4; ++i) {
		presentation.RequestRedraw();
		Check(presentation.NeedsPresent(snapshot), "explicit redraw was ignored");
		Check(!presentation.NeedsUpload(snapshot), "explicit redraw reuploaded unchanged pixels");
		presentation.Presented(snapshot);
		Check(!presentation.NeedsPresent(snapshot), "redraw was not consumed");
	}
}

static void TestEmptyFrameAndInvalidCallback()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	uint32_t pixel = 42;
	Check(!mailbox.CopyLatest(snapshot), "empty mailbox produced pixels");
	Check(presentation.NeedsPresent(snapshot), "initial empty background missing");
	Check(!presentation.NeedsUpload(snapshot), "empty background tried to upload");
	// A failed initial background draw is retried until presentation succeeds.
	Check(presentation.NeedsPresent(snapshot), "empty background failure consumed redraw");
	presentation.Presented(snapshot);
	Check(!presentation.NeedsPresent(snapshot), "empty background redraws continuously");
	Check(!mailbox.Publish(nullptr, 1, 1), "null callback accepted");
	Check(!mailbox.Publish(&pixel, 0, 1), "zero width accepted");
	Check(!mailbox.Publish(&pixel, 1, 0), "zero height accepted");
	Check(!mailbox.Publish(&pixel, UINT32_MAX, UINT32_MAX), "overflow dimensions accepted");
	Check(mailbox.Publish(&pixel, 1, 1), "valid callback failed after invalid callback");
	mailbox.CopyLatest(snapshot);
	Check(!mailbox.Publish(nullptr, 1, 1), "null callback accepted after valid frame");
	Check(!mailbox.CopyLatest(snapshot) && snapshot.Pixels[0] == pixel, "invalid callback destroyed the last frame");
	Check(presentation.NeedsUpload(snapshot) && presentation.NeedsPresent(snapshot), "first real frame did not replace background");
}

static void TestFailedUploadAndDrawRetry()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	const uint32_t pixel = 42;
	mailbox.Publish(&pixel, 1, 1);
	mailbox.CopyLatest(snapshot);
	for(int attempt = 0; attempt < 3; ++attempt) {
		// A failed texture creation or upload must not call Uploaded/Presented.
		Check(presentation.NeedsUpload(snapshot), "upload failure discarded pending pixels");
		Check(presentation.NeedsPresent(snapshot), "upload failure discarded redraw");
	}
	presentation.Uploaded(snapshot);
	Check(!presentation.NeedsUpload(snapshot), "successful upload was not retained");
	Check(presentation.NeedsPresent(snapshot), "a later draw failure cannot consume redraw");
	presentation.Presented(snapshot);
	Check(!presentation.NeedsPresent(snapshot), "successful draw did not settle output");
}

static void TestTextureResetWhilePaused()
{
	FrameMailbox mailbox;
	Frame snapshot;
	PresentationState presentation;
	const uint32_t pixel = 42;
	mailbox.Publish(&pixel, 1, 1);
	mailbox.CopyLatest(snapshot);
	presentation.Uploaded(snapshot);
	presentation.Presented(snapshot);
	presentation.InvalidateTexture();
	Check(!mailbox.CopyLatest(snapshot), "reset should reuse existing staging pixels");
	Check(presentation.NeedsUpload(snapshot), "renderer reset did not reupload paused pixels");
	Check(presentation.NeedsPresent(snapshot), "renderer reset did not redraw");
	presentation.Uploaded(snapshot);
	presentation.Presented(snapshot);
	Check(!presentation.NeedsUpload(snapshot) && !presentation.NeedsPresent(snapshot), "reset retry failed to settle");
}

static void TestConcurrentPublicationHasNoTornFrames()
{
	FrameMailbox mailbox;
	std::atomic<bool> done{false};
	std::thread producer([&]() {
		std::vector<uint32_t> pixels(1024);
		for(uint32_t value = 1; value <= 500; ++value) {
			std::fill(pixels.begin(), pixels.end(), value);
			mailbox.Publish(pixels.data(), 32, 32);
		}
		done.store(true);
	});
	Frame snapshot;
	bool valid = true;
	do {
		if(mailbox.CopyLatest(snapshot)) {
			for(uint32_t pixel : snapshot.Pixels) {
				valid = valid && pixel == snapshot.Pixels.front();
			}
		}
	} while(!done.load());
	producer.join();
	mailbox.CopyLatest(snapshot);
	Check(valid, "concurrent callback and copy produced a torn frame");
	Check(snapshot.Generation == 500 && snapshot.Pixels.front() == 500, "final publication was lost");
}

static void TestBackgroundNeverDrawsOrRebuilds()
{
	SurfaceState surface;
	Check(!surface.CanRebuild(0) && !surface.CanDraw(), "missing native binding allowed initial creation");
	surface.ObserveBinding(1, 2);
	Check(surface.CanRebuild(0) && !surface.CanDraw(), "initial surface must be created before drawing");
	surface.Rebuilt(true, 0);
	Check(surface.CanDraw(), "created surface unavailable");
	surface.SetWindowVisible(false);
	surface.SetForeground(false);
	// A render-device reset can arrive while Android owns the unbound context.
	surface.Invalidate();
	for(uint64_t time = 1; time < 1000; ++time) {
		Check(!surface.CanDraw() && !surface.CanRebuild(time), "background path touched drawing resources");
	}
	surface.SetForeground(true);
	Check(!surface.CanDraw() && !surface.CanRebuild(1000), "foreground event bypassed minimized window");
	surface.SetWindowVisible(true);
	Check(surface.CanRebuild(1000) && !surface.CanDraw(), "restored surface reused old renderer");
	// Multiple notifications in one event batch must produce one rebuild.
	surface.SetForeground(true);
	surface.SetWindowVisible(true);
	surface.Rebuilt(true, 1000);
	Check(surface.CanDraw() && !surface.CanRebuild(1000), "restore did not consume pending rebuild");
}

static void TestSurfaceRetryIsBoundedAndCanResume()
{
	SurfaceState surface;
	surface.ObserveBinding(1, 2);
	surface.Rebuilt(false, 20);
	Check(!surface.CanDraw() && !surface.CanRebuild(119), "failed surface retried in a busy loop");
	Check(surface.CanRebuild(120), "failed surface was never retried");
	surface.Rebuilt(false, 120);
	surface.SetForeground(false);
	Check(!surface.CanRebuild(1000), "retry continued after backgrounding");
	surface.SetForeground(true);
	Check(surface.CanRebuild(1000), "foreground did not wake pending surface retry");
	surface.Rebuilt(true, 1000);
	Check(surface.CanDraw() && !surface.CanRebuild(2000), "successful retry continuously recreated resources");
}

static void TestSurfaceRestoreRetainsPausedFrame()
{
	FrameMailbox mailbox;
	Frame frame;
	PresentationState presentation;
	SurfaceState surface;
	surface.ObserveBinding(1, 2);
	const uint32_t pixels[] = {0xFF124578, 0xFF963852};
	mailbox.Publish(pixels, 2, 1);
	mailbox.CopyLatest(frame);
	surface.Rebuilt(true, 0);
	presentation.Uploaded(frame);
	presentation.Presented(frame);
	const auto generation = frame.Generation;
	surface.SetForeground(false);
	for(int i = 0; i < 50; ++i) {
		Check(!mailbox.Publish(pixels, 2, 1), "paused callback changed during background");
	}
	surface.SetForeground(true);
	Check(surface.CanRebuild(100), "paused restore did not require new surface");
	// The native rebuild invalidates GPU/resource state, not owned frame pixels.
	presentation.InvalidateTexture();
	surface.Rebuilt(false, 100);
	Check(!surface.CanDraw() && presentation.NeedsUpload(frame), "failed rebuild lost pending upload");
	surface.Rebuilt(true, 200);
	Check(!mailbox.CopyLatest(frame) && frame.Generation == generation && frame.Pixels[0] == pixels[0], "restore lost paused frame");
	Check(surface.CanDraw() && presentation.NeedsUpload(frame) && presentation.NeedsPresent(frame), "restored paused frame cannot redraw");
	presentation.Uploaded(frame);
	presentation.Presented(frame);
	Check(!presentation.NeedsPresent(frame) && !surface.CanRebuild(300), "restored paused frame did not become idle");
}

static void TestNullBindingAndBootstrapNeverAllowDrawing()
{
	SurfaceState surface;
	surface.ObserveBinding(0, 0, true);
	Check(!surface.CanRebuild(0), "bootstrap ignored missing native window");
	surface.ObserveBinding(1, 0, true);
	Check(surface.CanRebuild(0) && !surface.CanDraw(), "initial non-GL window cannot create its framebuffer");
	// SDL can report successful creation with no usable Android surface.
	surface.Rebuilt(true, 0);
	Check(!surface.CanDraw(), "successful SDL return bypassed missing EGL surface");
	surface.ObserveBinding(1, 2, false);
	Check(surface.CanRebuild(1), "new complete binding did not retry creation");
	surface.Rebuilt(true, 1);
	Check(surface.CanDraw(), "valid initialization was rejected");
	surface.ObserveBinding(1, 0, true);
	Check(!surface.CanDraw() && !surface.CanRebuild(1000), "bootstrap remained allowed after initialization");
}

static void TestBindingChangesInvalidateWithoutLifecycleEvents()
{
	SurfaceState surface;
	surface.ObserveBinding(1, 2);
	surface.Rebuilt(true, 0);
	Check(!surface.ObserveBinding(1, 2) && surface.CanDraw(), "unchanged pair invalidated resources");
	Check(surface.ObserveBinding(3, 2) && !surface.CanDraw(), "native-window replacement reused renderer");
	surface.Rebuilt(true, 10);
	Check(surface.ObserveBinding(3, 4) && surface.CanRebuild(11), "EGL-only replacement was missed");
	surface.Rebuilt(true, 11);
	surface.ObserveBinding(0, 0);
	surface.Rebuilt(true, 12); // Simulates binding loss during SDL creation.
	Check(!surface.CanDraw() && !surface.CanRebuild(1000), "post-create binding loss was marked ready");
	surface.ObserveBinding(5, 6);
	Check(surface.CanRebuild(1000), "binding return required a missing lifecycle event");
}

static void TestBindingPollingPreservesFailedPresentBackoff()
{
	SurfaceState surface;
	surface.ObserveBinding(1, 2);
	surface.Rebuilt(true, 0);
	surface.Rebuilt(false, 10); // Fresh SDL present error requests resource retry.
	for(uint64_t now = 11; now < 110; ++now) {
		Check(!surface.ObserveBinding(1, 2), "stable pair changed during polling");
		Check(!surface.CanDraw() && !surface.CanRebuild(now), "polling erased failed-present backoff");
	}
	Check(surface.CanRebuild(110), "failed present never retried");
}

int main()
{
	const std::pair<const char*, void(*)()> tests[] = {
		{"callback owns pixels", TestCallbackOwnsPixels},
		{"repeated paused callbacks", TestRepeatedPausedCallbacks},
		{"staging reused and latest wins", TestSameSizeStagingReusedAndLatestWins},
		{"dimensions with identical bytes", TestDimensionChangeWithIdenticalBytes},
		{"forced redraw without upload", TestForcedRedrawDoesNotUpload},
		{"empty and invalid callbacks", TestEmptyFrameAndInvalidCallback},
		{"failed upload and drawing retry", TestFailedUploadAndDrawRetry},
		{"texture reset while paused", TestTextureResetWhilePaused},
		{"concurrent publication", TestConcurrentPublicationHasNoTornFrames},
		{"background display isolation", TestBackgroundNeverDrawsOrRebuilds},
		{"bounded surface retry", TestSurfaceRetryIsBoundedAndCanResume},
		{"surface restore retains paused pixels", TestSurfaceRestoreRetainsPausedFrame},
		{"missing binding and bounded bootstrap", TestNullBindingAndBootstrapNeverAllowDrawing},
		{"surface identity without lifecycle events", TestBindingChangesInvalidateWithoutLifecycleEvents},
		{"stable binding preserves retry backoff", TestBindingPollingPreservesFailedPresentBackoff},
	};
	try {
		for(const auto& test : tests) {
			test.second();
			std::cout << "PASS " << test.first << '\n';
		}
	} catch(const std::exception& error) {
		std::cerr << "FAIL " << error.what() << '\n';
		return 1;
	}
	std::cout << "15 tests passed\n";
	return 0;
}
