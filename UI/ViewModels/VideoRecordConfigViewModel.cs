using Mesen.Config;
using Mesen.Interop;
using Mesen.Utilities;
using ReactiveUI;
using ReactiveUI.Fody.Helpers;
using System;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Reactive.Linq;

namespace Mesen.ViewModels
{
	public class CaptureClip
	{
		public string Name { get; init; } = "";
		public string Path { get; init; } = "";
		public string Detail { get; init; } = "";
	}

	public class VideoRecordConfigViewModel : ViewModelBase
	{
		public static readonly string[] FormatNames = {
			"MP4 (H.264, for sharing)",
			"AVI (lossless, for editing)",
			"GIF"
		};
		public static readonly string[] FramingNames = {
			"Full screen (256x224)",
			"Follow Link, 720p",
			"Wide 720p",
			"Square 1024x896"
		};
		public static readonly string[] ScaleNames = { "1x (256x224)", "2x (512x448)", "3x (768x672)", "4x (1024x896)" };

		public VideoRecordConfig Config { get; }

		[Reactive] public int FormatIndex { get; set; }
		[Reactive] public int FramingIndex { get; set; }
		[Reactive] public int ScaleIndex { get; set; }
		[Reactive] public string ClipsFolder { get; set; } = "";

		[Reactive] public bool IsRecording { get; private set; }
		[Reactive] public bool CanChangeSettings { get; private set; } = true;
		[Reactive] public string RecordButtonText { get; private set; } = "Record";
		[Reactive] public string ElapsedText { get; private set; } = "";
		[Reactive] public string StatusText { get; private set; } = "";
		[Reactive] public bool ScaleAvailable { get; private set; }
		[Reactive] public bool FramingAvailable { get; private set; }
		[Reactive] public string FfmpegText { get; private set; } = "";
		[Reactive] public CaptureClip? SelectedClip { get; set; }
		public ObservableCollection<CaptureClip> Clips { get; } = new();

		public VideoRecordConfigViewModel()
		{
			Config = ConfigManager.Config.VideoRecord;
			FormatIndex = (int)Config.Format;
			FramingIndex = (int)Config.Framing;
			ScaleIndex = Math.Clamp(Config.Mp4Scale, 1, 4) - 1;
			ClipsFolder = CaptureSession.ClipsFolder;
			FfmpegText = CaptureSession.FfmpegPath == null ? "ffmpeg not found: MP4 falls back to AVI (brew install ffmpeg)" : "";

			this.WhenAnyValue(x => x.FormatIndex, x => x.FramingIndex, x => x.ScaleIndex).Subscribe(_ => {
				Config.Format = (CaptureFormat)Math.Clamp(FormatIndex, 0, 2);
				Config.Framing = (CaptureFraming)Math.Clamp(FramingIndex, 0, 3);
				Config.Mp4Scale = ScaleIndex + 1;
				FramingAvailable = Config.Format != CaptureFormat.Gif;
				ScaleAvailable = Config.Format == CaptureFormat.Mp4 && (Config.Framing == CaptureFraming.FullScreen || !FramingAvailable);
			});

			CaptureSession.Changed += OnSessionChanged;
			OnSessionChanged();
			RefreshClips();
		}

		public void Dispose()
		{
			CaptureSession.Changed -= OnSessionChanged;
		}

		public async void ToggleRecording()
		{
			ConfigManager.Config.Save();
			await CaptureSession.Toggle();
			OnSessionChanged();
		}

		public void SetClipsFolder(string folder)
		{
			Config.ClipsFolder = folder;
			ClipsFolder = CaptureSession.ClipsFolder;
			ConfigManager.Config.Save();
			RefreshClips();
		}

		private string? _lastClipShown;

		private void OnSessionChanged()
		{
			IsRecording = CaptureSession.IsRecording;
			CanChangeSettings = !IsRecording && !CaptureSession.IsExporting;
			RecordButtonText = IsRecording ? "Stop" : (CaptureSession.IsExporting ? "Exporting..." : "Record");
			StatusText = EmuApi.IsRunning() || IsRecording || CaptureSession.IsExporting ? CaptureSession.Status : "Load a game to record.";

			if(IsRecording && CaptureSession.StartedAt is DateTime started) {
				TimeSpan t = DateTime.Now - started;
				ElapsedText = $"{(int)t.TotalMinutes:00}:{t.Seconds:00}  ·  {CaptureSession.CurrentFileSize() / (1024.0 * 1024.0):0.0} MB";
			} else {
				ElapsedText = "";
			}

			if(CaptureSession.LastClip != _lastClipShown) {
				_lastClipShown = CaptureSession.LastClip;
				RefreshClips();
			}
		}

		public void RefreshClips()
		{
			string? selected = SelectedClip?.Path ?? CaptureSession.LastClip;
			Clips.Clear();
			try {
				foreach(FileInfo f in new DirectoryInfo(CaptureSession.ClipsFolder).EnumerateFiles()
					.Where(f => CaptureSession.ClipExtensions.Contains(f.Extension.ToLowerInvariant()))
					.OrderByDescending(f => f.LastWriteTime)
					.Take(100)) {
					Clips.Add(new CaptureClip() {
						Name = f.Name,
						Path = f.FullName,
						Detail = $"{f.LastWriteTime:MMM d, HH:mm}  ·  {f.Length / (1024.0 * 1024.0):0.0} MB"
					});
				}
			} catch(Exception) {
				//Folder missing or unreadable: show an empty list
			}
			SelectedClip = Clips.FirstOrDefault(c => c.Path == selected) ?? Clips.FirstOrDefault();
		}
	}
}
