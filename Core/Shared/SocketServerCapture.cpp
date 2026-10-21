#include "pch.h"
#include "Shared/SocketServer.h"
#include "Shared/Emulator.h"
#include "Shared/EmuSettings.h"
#include "Shared/RomInfo.h"
#include "Shared/Audio/SoundMixer.h"
#include "Shared/Video/VideoDecoder.h"
#include "Shared/Video/VideoRenderer.h"
#include "Shared/Video/CaptureCamera.h"
#include "Shared/Video/LiveStreamSink.h"
#include "Utilities/PNGHelper.h"
#include "Utilities/Video/AviWriter.h"
#include "Utilities/FolderUtilities.h"
#include <filesystem>

#ifndef _WIN32
#include <sys/stat.h>
#endif

namespace {
	SocketResponse Fail(const string& error, SocketErrorCode code = SocketErrorCode::InvalidParameter)
	{
		SocketResponse resp;
		resp.success = false;
		resp.error = error;
		resp.errorCode = code;
		return resp;
	}

	SocketResponse Ok(const string& data)
	{
		SocketResponse resp;
		resp.success = true;
		resp.data = data;
		return resp;
	}

	string Lower(string value)
	{
		std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return (char)std::tolower(c); });
		return value;
	}

	string Escape(const string& value)
	{
		string out;
		out.reserve(value.size() + 2);
		for(char c : value) {
			switch(c) {
				case '"': out += "\\\""; break;
				case '\\': out += "\\\\"; break;
				case '\n': out += "\\n"; break;
				case '\r': out += "\\r"; break;
				case '\t': out += "\\t"; break;
				default:
					if((unsigned char)c < 0x20) {
						char buf[8];
						snprintf(buf, sizeof(buf), "\\u%04x", c);
						out += buf;
					} else {
						out += c;
					}
			}
		}
		return out;
	}

	bool ParseBool(const string& value)
	{
		string v = Lower(value);
		return v == "1" || v == "true" || v == "yes" || v == "on";
	}

	bool ParseDouble(const SocketCommand& cmd, const string& key, double& out, string& error)
	{
		if(!cmd.HasParam(key)) {
			return true;
		}
		try {
			size_t used = 0;
			string value = cmd.GetParam(key);
			out = std::stod(value, &used);
			if(used != value.size() || !std::isfinite(out)) {
				throw std::invalid_argument(key);
			}
			return true;
		} catch(...) {
			error = "Invalid number for " + key + ": " + cmd.GetParam(key);
			return false;
		}
	}

	bool ParseUInt(const SocketCommand& cmd, const string& key, uint32_t& out, string& error)
	{
		double value = out;
		if(!ParseDouble(cmd, key, value, error)) {
			return false;
		}
		if(value < 0 || value > 16384 || value != std::floor(value)) {
			error = "Invalid value for " + key + ": " + cmd.GetParam(key);
			return false;
		}
		out = (uint32_t)value;
		return true;
	}

	// Accepts hex ("0x7E0022", "$7E0022") or decimal. "-1"/"none" clears the address.
	bool ParseAddress(const SocketCommand& cmd, const string& key, MemoryType memType, int32_t& out, string& error)
	{
		if(!cmd.HasParam(key)) {
			return true;
		}
		string value = Lower(cmd.GetParam(key));
		if(value.empty() || value == "-1" || value == "none") {
			out = -1;
			return true;
		}
		try {
			size_t used = 0;
			uint32_t addr;
			if(value.rfind("0x", 0) == 0) {
				addr = (uint32_t)std::stoul(value.substr(2), &used, 16);
				used += 2;
			} else if(value[0] == '$') {
				addr = (uint32_t)std::stoul(value.substr(1), &used, 16);
				used += 1;
			} else {
				addr = (uint32_t)std::stoul(value, &used, 10);
			}
			if(used != value.size()) {
				throw std::invalid_argument(key);
			}
			//Bus addresses for WRAM ($7E0000-$7FFFFF) map onto the work RAM buffer
			if(memType == MemoryType::SnesWorkRam && addr >= 0x7E0000 && addr <= 0x7FFFFF) {
				addr -= 0x7E0000;
			}
			out = (int32_t)addr;
			return true;
		} catch(...) {
			error = "Invalid address for " + key + ": " + cmd.GetParam(key);
			return false;
		}
	}

	bool IsAbsolute(const string& path)
	{
		return (!path.empty() && (path[0] == '/' || path[0] == '\\')) || (path.size() > 1 && path[1] == ':');
	}

	bool FileExists(const string& path)
	{
		ifstream file(path, std::ios::in | std::ios::binary);
		return file.good();
	}

	// Output files must be absolute, inside an existing folder, and not replace a file unless asked.
	bool ValidateOutputPath(const SocketCommand& cmd, const string& path, string& error)
	{
		if(path.empty() || !IsAbsolute(path)) {
			error = "path must be an absolute file path";
			return false;
		}
		string folder = FolderUtilities::GetFolderName(path);
		if(folder.empty() || !std::filesystem::is_directory(folder)) {
			error = "Folder does not exist: " + folder;
			return false;
		}
		if(FileExists(path) && !ParseBool(cmd.GetParam("overwrite", "false"))) {
			error = "File exists (pass overwrite=true to replace): " + path;
			return false;
		}
		return true;
	}

	bool IsFifo(const string& path)
	{
#ifndef _WIN32
		struct stat info;
		return stat(path.c_str(), &info) == 0 && S_ISFIFO(info.st_mode);
#else
		(void)path;
		return false;
#endif
	}

	string Base64(const string& data)
	{
		static const char* chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		string out;
		out.reserve((data.size() + 2) / 3 * 4);
		size_t i = 0;
		for(; i + 2 < data.size(); i += 3) {
			uint32_t n = ((uint8_t)data[i] << 16) | ((uint8_t)data[i + 1] << 8) | (uint8_t)data[i + 2];
			out += chars[(n >> 18) & 63];
			out += chars[(n >> 12) & 63];
			out += chars[(n >> 6) & 63];
			out += chars[n & 63];
		}
		if(i < data.size()) {
			uint32_t n = (uint8_t)data[i] << 16;
			if(i + 1 < data.size()) {
				n |= (uint8_t)data[i + 1] << 8;
			}
			out += chars[(n >> 18) & 63];
			out += chars[(n >> 12) & 63];
			out += i + 1 < data.size() ? chars[(n >> 6) & 63] : '=';
			out += '=';
		}
		return out;
	}

	string CameraJson(VideoRenderer* renderer)
	{
		CaptureCamera& camera = renderer->GetCaptureCamera();
		CaptureCameraState state = camera.GetState();
		FrameInfo game = renderer->GetGameFrameSize();
		uint32_t width, height;
		camera.GetOutputSize(game.Width, game.Height, width, height);
		CaptureCameraRect view = camera.GetViewRect(game.Width, game.Height);
		CaptureFollowSource follow = renderer->GetCameraFollow();

		stringstream ss;
		ss << std::fixed << std::setprecision(2);
		ss << "{\"mode\":\"" << CaptureCamera::ModeName(state.Mode) << "\"";
		ss << ",\"filter\":\"" << CaptureCamera::FilterName(state.Filter) << "\"";
		ss << ",\"width\":" << width << ",\"height\":" << height;
		ss << ",\"size_pinned\":" << (state.OutputWidth ? "true" : "false");
		ss << ",\"x\":" << (view.X + view.Width / 2) << ",\"y\":" << (view.Y + view.Height / 2);
		ss << ",\"zoom\":" << state.Zoom;
		ss << ",\"clamp\":" << (state.Clamp ? "true" : "false");
		ss << ",\"smoothing\":" << state.Smoothing;
		ss << ",\"deadzone_x\":" << state.DeadzoneX << ",\"deadzone_y\":" << state.DeadzoneY;
		ss << ",\"pan_active\":" << (state.PanActive ? "true" : "false");
		ss << ",\"pan_remaining\":" << (state.PanActive ? state.PanFrames - state.PanElapsed : 0);
		ss << ",\"view\":{\"x\":" << view.X << ",\"y\":" << view.Y << ",\"w\":" << view.Width << ",\"h\":" << view.Height << "}";
		ss << ",\"game_width\":" << game.Width << ",\"game_height\":" << game.Height;
		ss << ",\"follow\":{\"memtype\":" << (int)follow.MemType;
		ss << ",\"x\":" << follow.TargetXAddr << ",\"y\":" << follow.TargetYAddr;
		ss << ",\"scroll_x\":" << follow.ScrollXAddr << ",\"scroll_y\":" << follow.ScrollYAddr;
		ss << ",\"offset_x\":" << follow.OffsetX << ",\"offset_y\":" << follow.OffsetY;
		ss << ",\"size\":" << (int)follow.ValueSize << "}}";
		return ss.str();
	}

	string WavPath;
}

SocketResponse SocketServer::HandleCamera(Emulator* emu, const SocketCommand& cmd)
{
	VideoRenderer* renderer = emu->GetVideoRenderer();
	CaptureCamera& camera = renderer->GetCaptureCamera();
	string action = Lower(cmd.GetParam("action", "get"));
	string error;

	if(action == "get") {
		return Ok(CameraJson(renderer));
	}

	if(action == "reset") {
		if(renderer->GetLiveStream() || renderer->IsRecordingThroughCamera()) {
			return Fail("Camera output size is pinned while LIVE or a camera RECORD is active");
		}
		camera.Reset();
		renderer->SetCameraFollow({});
		return Ok(CameraJson(renderer));
	}

	if(action == "pan") {
		double x = -1, y = -1, zoom = 0;
		uint32_t frames = 60;
		if(!ParseDouble(cmd, "x", x, error) || !ParseDouble(cmd, "y", y, error) || !ParseDouble(cmd, "zoom", zoom, error) || !ParseUInt(cmd, "frames", frames, error)) {
			return Fail(error);
		}
		string ease = Lower(cmd.GetParam("ease", "smooth"));
		if(ease != "smooth" && ease != "linear") {
			return Fail("ease must be smooth or linear");
		}
		camera.StartPan(x, y, zoom, frames, ease == "smooth");
		return Ok(CameraJson(renderer));
	}

	if(action != "set" && action != "follow") {
		return Fail("Unknown action: " + action + " (get/set/pan/reset)");
	}

	CaptureCameraState state = camera.GetState();
	if(action == "follow") {
		state.Mode = CaptureCameraMode::Follow;
	}
	if(cmd.HasParam("mode") && !CaptureCamera::TryParseMode(Lower(cmd.GetParam("mode")), state.Mode)) {
		return Fail("mode must be full, manual or follow");
	}
	if(cmd.HasParam("filter") && !CaptureCamera::TryParseFilter(Lower(cmd.GetParam("filter")), state.Filter)) {
		return Fail("filter must be nearest or bilinear");
	}

	uint32_t width = state.OutputWidth, height = state.OutputHeight;
	if(!ParseUInt(cmd, "width", width, error) || !ParseUInt(cmd, "height", height, error)) {
		return Fail(error);
	}
	if((width == 0) != (height == 0)) {
		return Fail("width and height must both be set, or both be 0 (game size)");
	}
	if((width != state.OutputWidth || height != state.OutputHeight) && (renderer->GetLiveStream() || renderer->IsRecordingThroughCamera())) {
		return Fail("Camera output size is pinned while LIVE or a camera RECORD is active");
	}
	state.OutputWidth = width;
	state.OutputHeight = height;

	if(!ParseDouble(cmd, "x", state.CenterX, error) || !ParseDouble(cmd, "y", state.CenterY, error) ||
		!ParseDouble(cmd, "zoom", state.Zoom, error) || !ParseDouble(cmd, "smoothing", state.Smoothing, error) ||
		!ParseDouble(cmd, "deadzone_x", state.DeadzoneX, error) || !ParseDouble(cmd, "deadzone_y", state.DeadzoneY, error)) {
		return Fail(error);
	}
	if(cmd.HasParam("clamp")) {
		state.Clamp = ParseBool(cmd.GetParam("clamp"));
	}
	state.PanActive = false;

	CaptureFollowSource follow = renderer->GetCameraFollow();
	if(cmd.HasParam("memtype") && !ParseMemoryTypeName(cmd.GetParam("memtype"), follow.MemType)) {
		return Fail("Unknown memtype: " + cmd.GetParam("memtype"));
	}
	double offsetX = follow.OffsetX, offsetY = follow.OffsetY;
	uint32_t size = follow.ValueSize;
	if(!ParseAddress(cmd, "follow_x", follow.MemType, follow.TargetXAddr, error) || !ParseAddress(cmd, "follow_y", follow.MemType, follow.TargetYAddr, error) ||
		!ParseAddress(cmd, "scroll_x", follow.MemType, follow.ScrollXAddr, error) || !ParseAddress(cmd, "scroll_y", follow.MemType, follow.ScrollYAddr, error) ||
		!ParseDouble(cmd, "offset_x", offsetX, error) || !ParseDouble(cmd, "offset_y", offsetY, error) || !ParseUInt(cmd, "size", size, error)) {
		return Fail(error);
	}
	if(size != 1 && size != 2) {
		return Fail("size must be 1 or 2");
	}
	follow.OffsetX = (int32_t)offsetX;
	follow.OffsetY = (int32_t)offsetY;
	follow.ValueSize = (uint8_t)size;
	if(state.Mode == CaptureCameraMode::Follow && (follow.TargetXAddr < 0 || follow.TargetYAddr < 0)) {
		return Fail("follow mode needs follow_x and follow_y addresses");
	}

	renderer->SetCameraFollow(follow);
	camera.SetState(state);
	return Ok(CameraJson(renderer));
}

SocketResponse SocketServer::HandleRecord(Emulator* emu, const SocketCommand& cmd)
{
	VideoRenderer* renderer = emu->GetVideoRenderer();
	SoundMixer* mixer = emu->GetSoundMixer();
	string action = Lower(cmd.GetParam("action", "status"));

	auto status = [&]() {
		stringstream ss;
		ss << "{\"video\":{\"active\":" << (renderer->IsRecording() ? "true" : "false");
		ss << ",\"path\":\"" << Escape(renderer->GetRecordingFile()) << "\"";
		ss << ",\"camera\":" << (renderer->IsRecordingThroughCamera() ? "true" : "false") << "}";
		ss << ",\"audio\":{\"active\":" << (mixer->IsRecording() ? "true" : "false");
		ss << ",\"path\":\"" << Escape(mixer->IsRecording() ? WavPath : "") << "\"}}";
		return ss.str();
	};

	if(action == "status") {
		return Ok(status());
	}

	if(action == "stop") {
		string kind = Lower(cmd.GetParam("kind", "all"));
		if(kind != "all" && kind != "video" && kind != "audio") {
			return Fail("kind must be video, audio or all");
		}
		if(kind != "audio" && renderer->IsRecording()) {
			renderer->StopRecording();
		}
		if(kind != "video" && mixer->IsRecording()) {
			mixer->StopRecording();
		}
		return Ok(status());
	}

	if(action != "start") {
		return Fail("Unknown action: " + action + " (start/stop/status)");
	}
	if(!emu->IsRunning()) {
		return Fail("No ROM loaded", SocketErrorCode::EmulatorNotRunning);
	}

	string path = cmd.GetParam("path");
	string error;
	if(!ValidateOutputPath(cmd, path, error)) {
		return Fail(error);
	}

	string ext = FolderUtilities::GetExtension(path);
	string kind = Lower(cmd.GetParam("kind", ext == ".gif" ? "gif" : (ext == ".wav" ? "wav" : "avi")));
	if(kind == "wav") {
		if(mixer->IsRecording()) {
			return Fail("An audio recording is already active");
		}
		mixer->StartRecording(path);
		WavPath = path;
		return Ok(status());
	}
	if(kind != "avi" && kind != "gif") {
		return Fail("kind must be avi, gif or wav");
	}
	if(renderer->IsRecording()) {
		return Fail("A video recording is already active");
	}

	RecordAviOptions options = {};
	if(kind == "gif") {
		options.Codec = VideoCodec::GIF;
	} else {
		string codec = Lower(cmd.GetParam("codec", "zmbv"));
		if(codec == "zmbv") {
			options.Codec = VideoCodec::ZMBV;
		} else if(codec == "cscd") {
			options.Codec = VideoCodec::CSCD;
		} else if(codec == "raw" || codec == "none") {
			options.Codec = VideoCodec::None;
		} else {
			return Fail("codec must be zmbv, cscd or raw");
		}
	}
	uint32_t compression = 6;
	if(!ParseUInt(cmd, "compression", compression, error)) {
		return Fail(error);
	}
	options.CompressionLevel = std::min<uint32_t>(compression, 9);
	options.RecordSystemHud = ParseBool(cmd.GetParam("system_hud", "false"));
	options.RecordInputHud = ParseBool(cmd.GetParam("input_hud", "false"));
	bool throughCamera = ParseBool(cmd.GetParam("camera", "false"));

	if(!renderer->StartRecording(path, options, throughCamera)) {
		return Fail("Could not write to " + path, SocketErrorCode::InternalError);
	}
	return Ok(status());
}

SocketResponse SocketServer::HandleLive(Emulator* emu, const SocketCommand& cmd)
{
	VideoRenderer* renderer = emu->GetVideoRenderer();
	string action = Lower(cmd.GetParam("action", "status"));

	auto status = [&]() {
		shared_ptr<LiveStreamSink> live = renderer->GetLiveStream();
		stringstream ss;
		ss << "{\"active\":" << (live ? "true" : "false");
		if(live) {
			auto channel = [&](const LiveStreamSink::ChannelStats& stats) {
				stringstream cs;
				cs << "{\"connected\":" << (stats.Connected ? "true" : "false") << ",\"sent\":" << stats.BuffersSent << ",\"dropped\":" << stats.BuffersDropped << ",\"bytes\":" << stats.BytesSent << "}";
				return cs.str();
			};
			ss << ",\"width\":" << live->GetWidth() << ",\"height\":" << live->GetHeight();
			ss << ",\"pixel_format\":\"bgra\"";
			ss << ",\"fps\":" << std::fixed << std::setprecision(4) << emu->GetFps() / live->GetFrameInterval();
			ss << ",\"every\":" << live->GetFrameInterval() << ",\"downscale\":" << live->GetDownscale();
			ss << ",\"sample_rate\":" << live->GetSampleRate() << ",\"channels\":2,\"sample_format\":\"s16le\"";
			ss << ",\"video\":" << channel(live->GetVideoStats());
			ss << ",\"audio\":" << (live->HasAudio() ? channel(live->GetAudioStats()) : "null");
		}
		ss << "}";
		return ss.str();
	};

	if(action == "status") {
		return Ok(status());
	}
	if(action == "stop") {
		renderer->StopLiveStream();
		return Ok(status());
	}
	if(action != "start") {
		return Fail("Unknown action: " + action + " (start/stop/status)");
	}
	if(!emu->IsRunning()) {
		return Fail("No ROM loaded", SocketErrorCode::EmulatorNotRunning);
	}

	string video = cmd.GetParam("video");
	string audio = cmd.GetParam("audio");
	if(!IsFifo(video)) {
		return Fail("video must be an existing named pipe (mkfifo)");
	}
	if(!audio.empty() && !IsFifo(audio)) {
		return Fail("audio must be an existing named pipe (mkfifo)");
	}
	uint32_t every = 1;
	uint32_t downscale = 1;
	string error;
	if(!ParseUInt(cmd, "every", every, error) || !ParseUInt(cmd, "downscale", downscale, error)) {
		return Fail(error);
	}
	if(downscale < 1 || downscale > 8) {
		return Fail("downscale must be 1-8");
	}
	if(!renderer->StartLiveStream(video, audio, std::max<uint32_t>(1, every), downscale, error)) {
		return Fail(error, SocketErrorCode::InternalError);
	}
	return Ok(status());
}

SocketResponse SocketServer::HandleProject(Emulator* emu, const SocketCommand& cmd)
{
	(void)cmd;
	YazeProjectInfo project = emu->GetProjectInfo();
	stringstream ss;
	ss << "{\"loaded\":" << (project.ProjectPath.empty() ? "false" : "true");
	ss << ",\"project\":\"" << Escape(project.ProjectPath) << "\"";
	ss << ",\"name\":\"" << Escape(project.Name) << "\"";
	ss << ",\"rom\":\"" << Escape(project.RomPath) << "\"";
	ss << ",\"symbols\":\"" << Escape(project.SymbolsPath) << "\"";
	ss << ",\"profile\":\"" << Escape(project.ProfilePath) << "\"";
	ss << ",\"current_rom\":\"" << Escape(emu->IsRunning() ? emu->GetRomInfo().RomFile.GetFilePath() : "") << "\"}";
	return Ok(ss.str());
}

SocketResponse SocketServer::HandleCaptureScreenshot(Emulator* emu, const SocketCommand& cmd)
{
	string path = cmd.GetParam("path");
	string error;
	if(!path.empty() && !ValidateOutputPath(cmd, path, error)) {
		return Fail(error);
	}

	std::stringstream png;
	uint32_t width = 0, height = 0;
	if(ParseBool(cmd.GetParam("camera", "false"))) {
		vector<uint32_t> buffer;
		if(!emu->GetVideoRenderer()->RenderCameraSnapshot(buffer, width, height) || !PNGHelper::WritePNG(png, buffer.data(), width, height)) {
			return Fail("Failed to capture screenshot", SocketErrorCode::InternalError);
		}
	} else {
		emu->GetVideoDecoder()->TakeScreenshot(png);
	}

	string data = png.str();
	if(data.empty()) {
		return Fail("Failed to capture screenshot", SocketErrorCode::InternalError);
	}

	if(path.empty()) {
		return Ok("\"" + Base64(data) + "\"");
	}

	ofstream file(path, std::ios::out | std::ios::binary | std::ios::trunc);
	file.write(data.data(), data.size());
	if(!file.good()) {
		return Fail("Could not write " + path, SocketErrorCode::InternalError);
	}
	stringstream ss;
	ss << "{\"path\":\"" << Escape(path) << "\",\"bytes\":" << data.size();
	if(width) {
		ss << ",\"width\":" << width << ",\"height\":" << height;
	}
	ss << "}";
	return Ok(ss.str());
}
