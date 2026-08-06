#include "Common.h"
#include "Core/Shared/Emulator.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Core/Shared/Movies/MovieManager.h"
#include "InteropLifecycle.h"

extern unique_ptr<Emulator>& _emu;

extern "C"
{
	DllExport void __stdcall AviRecord(char* filename, RecordAviOptions options) { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetVideoRenderer()->StartRecording(filename, options); }
	DllExport void __stdcall AviStop() { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetVideoRenderer()->StopRecording(); }
	DllExport bool __stdcall AviIsRecording() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetVideoRenderer()->IsRecording(); }

	DllExport void __stdcall WaveRecord(char* filename) { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetSoundMixer()->StartRecording(filename); }
	DllExport void __stdcall WaveStop() { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetSoundMixer()->StopRecording(); }
	DllExport bool __stdcall WaveIsRecording() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetSoundMixer()->IsRecording(); }

	DllExport void __stdcall MoviePlay(char* filename) { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetMovieManager()->Play(string(filename)); }
	DllExport void __stdcall MovieStop() { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetMovieManager()->Stop(); }
	DllExport bool __stdcall MoviePlaying() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetMovieManager()->Playing(); }
	DllExport bool __stdcall MovieRecording() { INTEROP_EMU_LEASE_OR_RETURN_VALUE(false); return _emu->GetMovieManager()->Recording(); }
	DllExport void __stdcall MovieRecord(RecordMovieOptions options) { INTEROP_EMU_LEASE_OR_RETURN(); _emu->GetMovieManager()->Record(options); }
}
