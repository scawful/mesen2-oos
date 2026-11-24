using Avalonia.Threading;
using Mesen.Config;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	/// <summary>
	/// One-click video capture shared by the Capture window, the Video Recorder menu and the
	/// record-video shortcut. Records lossless AVI in the core, through the virtual camera when a
	/// framed preset is chosen, and exports MP4 with ffmpeg when the recording stops.
	/// </summary>
	public static class CaptureSession
	{
		public static readonly string[] ClipExtensions = { ".mp4", ".avi", ".gif", ".wav", ".mov" };

		private static DispatcherTimer? _timer;
		private static string? _recordingPath;
		private static string? _finalPath;
		private static CaptureFormat _format;
		private static int _scale = 1;
		private static bool _usedCamera;

		public static DateTime? StartedAt { get; private set; }
		public static bool IsExporting { get; private set; }
		public static string Status { get; private set; } = "Ready";
		public static string? LastClip { get; private set; }

		/// <summary>Raised on the UI thread whenever recording, export or status changes.</summary>
		public static event Action? Changed;

		public static bool IsRecording => RecordApi.AviIsRecording();

		public static string ClipsFolder
		{
			get
			{
				string folder = ConfigManager.Config.VideoRecord.ClipsFolder;
				if(string.IsNullOrWhiteSpace(folder)) {
					string movies = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Movies");
					folder = Path.Combine(Directory.Exists(movies) ? movies : ConfigManager.AviFolder, "Mesen2 OOS");
				}
				Directory.CreateDirectory(folder);
				return folder;
			}
		}

		public static string? FfmpegPath
		{
			get
			{
				List<string> candidates = new() { "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg" };
				string? pathVar = Environment.GetEnvironmentVariable("PATH");
				if(pathVar != null) {
					string exe = OperatingSystem.IsWindows() ? "ffmpeg.exe" : "ffmpeg";
					candidates.AddRange(pathVar.Split(Path.PathSeparator).Where(d => d.Length > 0).Select(d => Path.Combine(d, exe)));
				}
				return candidates.FirstOrDefault(File.Exists);
			}
		}

		public static async Task Toggle()
		{
			if(IsRecording) {
				Stop();
			} else {
				await Start();
			}
		}

		public static async Task<bool> Start()
		{
			if(!EmuApi.IsRunning() || IsRecording || IsExporting) {
				return false;
			}

			VideoRecordConfig cfg = ConfigManager.Config.VideoRecord;
			_format = cfg.Format;
			string note = "";
			if(_format == CaptureFormat.Mp4 && FfmpegPath == null) {
				note = " (MP4 needs ffmpeg: brew install ffmpeg; recording lossless AVI)";
				_format = CaptureFormat.Avi;
			}

			string folder = ClipsFolder;
			string name = BuildClipName();
			string ext = _format == CaptureFormat.Gif ? ".gif" : ".avi";
			string recordFolder = folder;
			if(_format == CaptureFormat.Mp4) {
				recordFolder = Path.Combine(folder, ".recording");
				Directory.CreateDirectory(recordFolder);
			}
			_recordingPath = UniquePath(Path.Combine(recordFolder, name + ext));
			_finalPath = _format == CaptureFormat.Mp4 ? UniquePath(Path.Combine(folder, name + ".mp4")) : _recordingPath;
			_usedCamera = false;
			_scale = 1;

			if(cfg.Framing != CaptureFraming.FullScreen && _format != CaptureFormat.Gif) {
				string? error = await StartFramed(cfg);
				if(error == null) {
					_usedCamera = true;
				} else {
					note += " (framing unavailable: " + error + "; recording the full screen)";
				}
			}

			if(!_usedCamera) {
				_scale = _format == CaptureFormat.Mp4 ? Math.Clamp(cfg.Mp4Scale, 1, 6) : 1;
				RecordApi.AviRecord(_recordingPath, new RecordAviOptions() {
					Codec = _format == CaptureFormat.Gif ? VideoCodec.GIF : VideoCodec.ZMBV,
					CompressionLevel = 6,
					RecordSystemHud = cfg.RecordSystemHud,
					RecordInputHud = cfg.RecordInputHud
				});
			}

			if(!RecordApi.AviIsRecording()) {
				SetStatus("Recording did not start. See Tools > Log Viewer.");
				return false;
			}

			StartedAt = DateTime.Now;
			SetStatus("Recording " + Path.GetFileName(_finalPath) + note);
			EnsureTimer();
			return true;
		}

		public static void Stop()
		{
			if(IsRecording) {
				RecordApi.AviStop();
			}
			OnTick(null, EventArgs.Empty);
		}

		private static async Task<string?> StartFramed(VideoRecordConfig cfg)
		{
			Dictionary<string, string> camera = cfg.Framing switch {
				CaptureFraming.FollowLink720p => new() {
					["action"] = "follow", ["width"] = "1280", ["height"] = "720", ["zoom"] = "2", ["filter"] = "nearest",
					["smoothing"] = "0.12", ["deadzone_x"] = "12", ["deadzone_y"] = "8",
					["follow_x"] = "0x7E0022", ["follow_y"] = "0x7E0020", ["scroll_x"] = "0x7E00E2", ["scroll_y"] = "0x7E00E8",
					["offset_x"] = "8", ["offset_y"] = "16", ["size"] = "2"
				},
				CaptureFraming.Wide720p => new() {
					["action"] = "set", ["mode"] = "manual", ["width"] = "1280", ["height"] = "720", ["zoom"] = "1", ["filter"] = "nearest"
				},
				_ => new() {
					["action"] = "set", ["mode"] = "full", ["width"] = "1024", ["height"] = "896", ["filter"] = "nearest"
				}
			};

			(_, string? error) = await OracleLocalSocketClient.ExecuteAsync("CAMERA", camera);
			if(error != null) {
				return error;
			}

			(_, error) = await OracleLocalSocketClient.ExecuteAsync("RECORD", new Dictionary<string, string>() {
				["action"] = "start",
				["path"] = _recordingPath!,
				["kind"] = "avi",
				["codec"] = "zmbv",
				["camera"] = "true",
				["system_hud"] = cfg.RecordSystemHud ? "true" : "false",
				["input_hud"] = cfg.RecordInputHud ? "true" : "false"
			});
			return error;
		}

		private static void EnsureTimer()
		{
			if(_timer == null) {
				_timer = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Background, OnTick);
			}
			_timer.Start();
		}

		private static void OnTick(object? sender, EventArgs e)
		{
			if(StartedAt == null || IsRecording) {
				Changed?.Invoke();
				return;
			}

			//The recording ended: from this window, the menu, the shortcut, or a resolution change
			_timer?.Stop();
			StartedAt = null;
			string recorded = _recordingPath!;
			string final = _finalPath!;
			if(_usedCamera) {
				_ = OracleLocalSocketClient.ExecuteAsync("CAMERA", new Dictionary<string, string>() { ["action"] = "reset" });
			}

			if(_format == CaptureFormat.Mp4) {
				_ = Export(recorded, final, _scale);
			} else {
				LastClip = recorded;
				SetStatus("Saved " + Path.GetFileName(recorded));
			}
		}

		private static async Task Export(string avi, string mp4, int scale)
		{
			IsExporting = true;
			SetStatus("Exporting MP4...");
			string? error = await Task.Run(() => RunFfmpeg(avi, mp4, scale));
			IsExporting = false;
			if(error == null) {
				try { File.Delete(avi); } catch { }
				LastClip = mp4;
				SetStatus("Saved " + Path.GetFileName(mp4));
			} else {
				LastClip = avi;
				SetStatus("MP4 export failed (" + error + "). Kept " + Path.GetFileName(avi));
			}
		}

		private static string? RunFfmpeg(string avi, string mp4, int scale)
		{
			string? ffmpeg = FfmpegPath;
			if(ffmpeg == null || !File.Exists(avi)) {
				return ffmpeg == null ? "ffmpeg not found" : "no recording";
			}

			ProcessStartInfo info = new(ffmpeg) {
				RedirectStandardError = true,
				RedirectStandardOutput = true,
				UseShellExecute = false
			};
			foreach(string arg in new[] { "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", avi }) {
				info.ArgumentList.Add(arg);
			}
			if(scale > 1) {
				info.ArgumentList.Add("-vf");
				info.ArgumentList.Add($"scale=iw*{scale}:ih*{scale}:flags=neighbor");
			}
			foreach(string arg in new[] { "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p",
				"-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", mp4 }) {
				info.ArgumentList.Add(arg);
			}

			try {
				using Process? proc = Process.Start(info);
				if(proc == null) {
					return "could not start ffmpeg";
				}
				Task<string> stderr = proc.StandardError.ReadToEndAsync();
				proc.StandardOutput.ReadToEnd();
				proc.WaitForExit();
				if(proc.ExitCode != 0) {
					string msg = stderr.Result.Trim();
					return "ffmpeg exit " + proc.ExitCode + (msg.Length > 0 ? ": " + msg.Split('\n')[0] : "");
				}
				return File.Exists(mp4) ? null : "no output";
			} catch(Exception ex) {
				return ex.Message;
			}
		}

		private static string BuildClipName()
		{
			string rom = EmuApi.GetRomInfo().GetRomName();
			string place = "";
			try {
				OraclePanelRuntimeSummary? s = OraclePanelBridge.ReadRuntimeSummary();
				if(s != null) {
					place = s.Indoors ? $"room{s.DungeonRoom:X3}" : s.AreaName;
				}
			} catch {
				place = "";
			}

			StringBuilder sb = new();
			foreach(string part in new[] { rom, place, DateTime.Now.ToString("yyyyMMdd-HHmmss") }) {
				if(string.IsNullOrWhiteSpace(part)) {
					continue;
				}
				if(sb.Length > 0) {
					sb.Append('_');
				}
				foreach(char c in part.Trim()) {
					sb.Append(char.IsLetterOrDigit(c) || c == '-' || c == '.' ? c : '-');
				}
			}
			return sb.ToString();
		}

		private static string UniquePath(string path)
		{
			if(!File.Exists(path)) {
				return path;
			}
			string dir = Path.GetDirectoryName(path)!;
			string stem = Path.GetFileNameWithoutExtension(path);
			string ext = Path.GetExtension(path);
			for(int i = 2; ; i++) {
				string candidate = Path.Combine(dir, $"{stem}-{i}{ext}");
				if(!File.Exists(candidate)) {
					return candidate;
				}
			}
		}

		private static void SetStatus(string status)
		{
			Status = status;
			if(Dispatcher.UIThread.CheckAccess()) {
				Changed?.Invoke();
			} else {
				Dispatcher.UIThread.Post(() => Changed?.Invoke());
			}
		}

		public static long CurrentFileSize()
		{
			try {
				return _recordingPath != null && File.Exists(_recordingPath) ? new FileInfo(_recordingPath).Length : 0;
			} catch {
				return 0;
			}
		}
	}
}
