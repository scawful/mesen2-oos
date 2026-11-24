#pragma once
#include "pch.h"
#include "Core/Shared/Interfaces/IAudioDevice.h"
#include "Utilities/safe_ptr.h"
#include <chrono>

class Emulator;
class Equalizer;
class SoundResampler;
class WaveRecorder;
class IAudioProvider;
class CrossFeedFilter;
class ReverbFilter;

class SoundMixer 
{
private:
	IAudioDevice *_audioDevice;
	vector<IAudioProvider*> _audioProviders;
	Emulator *_emu;
	unique_ptr<Equalizer> _equalizer;
	unique_ptr<SoundResampler> _resampler;
	safe_ptr<WaveRecorder> _waveRecorder;
	int16_t *_sampleBuffer = nullptr;

	int16_t _leftSample = 0;
	int16_t _rightSample = 0;

	unique_ptr<CrossFeedFilter> _crossFeedFilter;
	unique_ptr<ReverbFilter> _reverbFilter;

	// Sticky so muted turbo does not call SDL_PauseAudioDevice every SPC frame.
	bool _audioStoppedForMute = false;

	// Audible fast-forward: emulated speed measured from incoming SPC samples
	// per wall-clock second. Used to resample turbo audio back to real time
	// (pitched up) so the device buffer neither overflows nor goes silent.
	bool _turboAudioActive = false;
	double _turboSpeedRatio = 1.0;
	uint64_t _turboWindowSamples = 0;
	std::chrono::steady_clock::time_point _turboWindowStart;
	double GetTurboSourceRate(uint32_t sampleCount, uint32_t sourceRate);

	void ProcessEqualizer(int16_t *samples, uint32_t sampleCount, uint32_t targetRate);

public:
	SoundMixer(Emulator *emu);
	~SoundMixer();

	void PlayAudioBuffer(int16_t *samples, uint32_t sampleCount, uint32_t sourceRate);
	void StopAudio(bool clearBuffer = false);
	/** True when PlayAudioBuffer would discard host output (muted turbo/background). */
	bool ShouldDiscardHostAudio();

	void RegisterAudioDevice(IAudioDevice *audioDevice);

	void RegisterAudioProvider(IAudioProvider* provider);
	void UnregisterAudioProvider(IAudioProvider* provider);

	AudioStatistics GetStatistics();
	double GetRateAdjustment();

	void StartRecording(string filepath);
	void StopRecording();
	bool IsRecording();
	void GetLastSamples(int16_t &left, int16_t &right);
};
