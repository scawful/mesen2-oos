#include "pch.h"
#include <algorithm>
#include <chrono>
#include "Shared/Audio/SoundMixer.h"
#include "Shared/Audio/AudioPlayerHud.h"
#include "Shared/Emulator.h"
#include "Shared/EmuSettings.h"
#include "Shared/Audio/SoundResampler.h"
#include "Shared/RewindManager.h"
#include "Shared/Video/VideoRenderer.h"
#include "Shared/Audio/WaveRecorder.h"
#include "Shared/Interfaces/IAudioProvider.h"
#include "Utilities/Audio/Equalizer.h"
#include "Utilities/Audio/ReverbFilter.h"
#include "Utilities/Audio/CrossFeedFilter.h"

SoundMixer::SoundMixer(Emulator* emu)
{
	_emu = emu;
	_audioDevice = nullptr;
	_resampler.reset(new SoundResampler(emu));
	_sampleBuffer = new int16_t[0x10000];
	_reverbFilter.reset(new ReverbFilter());
	_crossFeedFilter.reset(new CrossFeedFilter());
}

SoundMixer::~SoundMixer()
{
	delete[] _sampleBuffer;
}

void SoundMixer::RegisterAudioDevice(IAudioDevice *audioDevice)
{
	_audioDevice = audioDevice;
}

void SoundMixer::RegisterAudioProvider(IAudioProvider* provider)
{
	_audioProviders.push_back(provider);
}

void SoundMixer::UnregisterAudioProvider(IAudioProvider* provider)
{
	vector<IAudioProvider*>& vec = _audioProviders;
	vec.erase(std::remove(vec.begin(), vec.end(), provider), vec.end());
}

AudioStatistics SoundMixer::GetStatistics()
{
	if(_audioDevice) {
		return _audioDevice->GetStatistics();
	} else {
		return AudioStatistics();
	}
}

void SoundMixer::StopAudio(bool clearBuffer)
{
	if(_audioDevice) {
		if(clearBuffer) {
			_audioDevice->Stop();
		} else {
			_audioDevice->Pause();
		}
	}
}

bool SoundMixer::ShouldDiscardHostAudio()
{
	EmuSettings* settings = _emu->GetSettings();
	AudioPlayerHud* audioPlayer = _emu->GetAudioPlayerHud();
	if(audioPlayer || _waveRecorder || _emu->GetVideoRenderer()->IsRecording()) {
		return false;
	}
	if(settings->GetPreferences().RewindBufferSize != 0) {
		return false;
	}

	AudioConfig cfg = settings->GetAudioConfig();
	uint32_t masterVolume = cfg.MasterVolume;
	if(settings->CheckFlag(EmulationFlags::InBackground)) {
		if(cfg.MuteSoundInBackground) {
			masterVolume = 0;
		} else if(cfg.ReduceSoundInBackground) {
			masterVolume = cfg.VolumeReduction == 100 ? 0 : masterVolume * (100 - cfg.VolumeReduction) / 100;
		}
	} else if(cfg.ReduceSoundInFastForward && settings->CheckFlag(EmulationFlags::TurboOrRewind)) {
		masterVolume = cfg.VolumeReduction == 100 ? 0 : masterVolume * (100 - cfg.VolumeReduction) / 100;
	}
	return masterVolume == 0;
}

void SoundMixer::PlayAudioBuffer(int16_t* samples, uint32_t sampleCount, uint32_t sourceRate)
{
	if(sampleCount == 0) {
		return;
	}

	EmuSettings* settings = _emu->GetSettings();
	AudioPlayerHud* audioPlayer = _emu->GetAudioPlayerHud();
	AudioConfig cfg = settings->GetAudioConfig();
	bool isRecording = _waveRecorder || _emu->GetVideoRenderer()->IsRecording();

	uint32_t masterVolume = audioPlayer ? audioPlayer->GetVolume() : cfg.MasterVolume;
	if(!isRecording) {
		if(!audioPlayer && settings->CheckFlag(EmulationFlags::InBackground)) {
			if(cfg.MuteSoundInBackground) {
				masterVolume = 0;
			} else if(cfg.ReduceSoundInBackground) {
				masterVolume = cfg.VolumeReduction == 100 ? 0 : masterVolume * (100 - cfg.VolumeReduction) / 100;
			}
		} else if(cfg.ReduceSoundInFastForward && settings->CheckFlag(EmulationFlags::TurboOrRewind)) {
			masterVolume = cfg.VolumeReduction == 100 ? 0 : masterVolume * (100 - cfg.VolumeReduction) / 100;
		}
	}

	_leftSample = samples[0];
	_rightSample = samples[1];

	// Handheld turbo mutes output (VolumeReduction=100) and disables rewind.
	// Must Stop (clear) the device once — returning without feeding the SDL ring
	// lets ReadFromBuffer loop stale PCM. Do not re-Stop every SPC frame.
	if(masterVolume == 0 && !isRecording && !audioPlayer
			&& settings->GetPreferences().RewindBufferSize == 0) {
		if(!_audioStoppedForMute) {
			StopAudio(true);
			_audioStoppedForMute = true;
		}
		return;
	}
	_audioStoppedForMute = false;

	int16_t *out = _sampleBuffer;
	double resampleSourceRate = GetTurboSourceRate(sampleCount, sourceRate);
	uint32_t count = _resampler->Resample(samples, sampleCount, (uint32_t)resampleSourceRate, cfg.SampleRate, out, 0x10000);

	uint32_t targetRate = (uint32_t)(cfg.SampleRate * _resampler->GetRateAdjustment());
	for(IAudioProvider* provider : _audioProviders) {
		provider->MixAudio(out, count, targetRate);
	}

	if(cfg.EnableEqualizer) {
		ProcessEqualizer(out, count, targetRate);
	}

	if(audioPlayer) {
		audioPlayer->ProcessSamples(out, count, targetRate);
	}

	if(cfg.ReverbEnabled) {
		if(cfg.ReverbStrength > 0) {
			_reverbFilter->ApplyFilter(out, count, cfg.SampleRate, cfg.ReverbStrength / 10.0, cfg.ReverbDelay / 10.0);
		} else {
			_reverbFilter->ResetFilter();
		}
	}

	if(cfg.CrossFeedEnabled) {
		_crossFeedFilter->ApplyFilter(out, count, cfg.CrossFeedRatio);
	}

	if(masterVolume < 100) {
		//Apply volume if not using the default value
		for(uint32_t i = 0; i < count * 2; i++) {
			out[i] = (int32_t)out[i] * (int32_t)masterVolume / 100;
		}
	}

	RewindManager* rewindManager = _emu->GetRewindManager();
	if(!_emu->IsRunAheadFrame() && rewindManager && rewindManager->SendAudio(out, count)) {
		if(isRecording) {
			shared_ptr<WaveRecorder> recorder = _waveRecorder.lock();
			if(recorder) {
				if(!recorder->WriteSamples(out, count, cfg.SampleRate, true)) {
					StopRecording();
				}
			}
			_emu->GetVideoRenderer()->AddRecordingSound(out, count, cfg.SampleRate);
		}

		//Only send the audio to the device if the emulation is running
		//(this is to prevent playing an audio blip when loading a save state)
		if(!_emu->IsPaused() && _audioDevice) {
			if(cfg.EnableAudio) {
				_audioDevice->PlayBuffer(out, count, cfg.SampleRate, true);
				_audioDevice->ProcessEndOfFrame();
			} else {
				_audioDevice->Stop();
			}
		}
	}
}

double SoundMixer::GetTurboSourceRate(uint32_t sampleCount, uint32_t sourceRate)
{
	EmuSettings* settings = _emu->GetSettings();
	bool turbo = settings->CheckFlag(EmulationFlags::Turbo) && !_waveRecorder && !_emu->GetVideoRenderer()->IsRecording();
	auto now = std::chrono::steady_clock::now();
	if(!turbo) {
		_turboAudioActive = false;
		return sourceRate;
	}
	if(!_turboAudioActive) {
		_turboAudioActive = true;
		_turboWindowStart = now;
		_turboWindowSamples = 0;
		// Keep the last measured speed as the first guess (RG353P max is ~1.5x).
		_turboSpeedRatio = std::clamp(_turboSpeedRatio, 1.0, 8.0);
		if(_turboSpeedRatio <= 1.0) {
			_turboSpeedRatio = 1.5;
		}
	}

	_turboWindowSamples += sampleCount;
	double elapsed = std::chrono::duration<double>(now - _turboWindowStart).count();
	if(elapsed >= 0.1) {
		double measured = (double)_turboWindowSamples / ((double)sourceRate * elapsed);
		measured = std::clamp(measured, 1.0, 8.0);
		_turboSpeedRatio = _turboSpeedRatio * 0.7 + measured * 0.3;
		_turboWindowStart = now;
		_turboWindowSamples = 0;
	}

	// Steer the device buffer toward the configured latency: more queued audio
	// than requested means we are producing too much, so compress harder.
	double ratio = _turboSpeedRatio;
	AudioStatistics stats = GetStatistics();
	if(stats.AverageLatency > 0) {
		double gapMs = stats.AverageLatency - (double)settings->GetAudioConfig().AudioLatency;
		ratio *= std::clamp(1.0 + gapMs / 500.0, 0.9, 1.15);
	}
	return sourceRate * ratio;
}

void SoundMixer::ProcessEqualizer(int16_t* samples, uint32_t sampleCount, uint32_t targetRate)
{
	AudioConfig cfg = _emu->GetSettings()->GetAudioConfig();
	if(!_equalizer) {
		_equalizer.reset(new Equalizer());
	}
	vector<double> bandGains = {
		cfg.Band1Gain, cfg.Band2Gain, cfg.Band3Gain, cfg.Band4Gain, cfg.Band5Gain,
		cfg.Band6Gain, cfg.Band7Gain, cfg.Band8Gain, cfg.Band9Gain, cfg.Band10Gain,
		cfg.Band11Gain, cfg.Band12Gain, cfg.Band13Gain, cfg.Band14Gain, cfg.Band15Gain,
		cfg.Band16Gain, cfg.Band17Gain, cfg.Band18Gain, cfg.Band19Gain, cfg.Band20Gain
	};
	
	_equalizer->UpdateEqualizers(bandGains, cfg.SampleRate);
	_equalizer->ApplyEqualizer(sampleCount, samples);
}

double SoundMixer::GetRateAdjustment()
{
	return _resampler->GetRateAdjustment();
}

void SoundMixer::StartRecording(string filepath)
{
	_waveRecorder.reset(new WaveRecorder(filepath, _emu->GetSettings()->GetAudioConfig().SampleRate, true));
}

void SoundMixer::StopRecording()
{
	_waveRecorder.reset();
}

bool SoundMixer::IsRecording()
{
	return _waveRecorder != nullptr;
}

void SoundMixer::GetLastSamples(int16_t &left, int16_t &right)
{
	left = _leftSample;
	right = _rightSample;
}