using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Mesen.Config;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	/// <summary>
	/// One preview frame per ROM path for the ROM library. Shared by every Mesen profile on the
	/// machine (agent and beta profiles included). While a game runs, a gameplay frame is saved
	/// every 20 seconds; Scripts/QA/library_previews.py in Oracle fills in builds never played.
	/// </summary>
	public static class LibraryPreviews
	{
		private static DispatcherTimer? _timer;
		private static bool _busy;

		public static string Folder
		{
			get
			{
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return OperatingSystem.IsMacOS()
					? Path.Combine(home, "Library", "Application Support", "Mesen2", "LibraryPreviews")
					: Path.Combine(ConfigManager.HomeFolder, "LibraryPreviews");
			}
		}

		public static string KeyFor(string romPath)
		{
			byte[] hash = SHA1.HashData(Encoding.UTF8.GetBytes(romPath));
			return Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
		}

		public static string PathFor(string romPath) => Path.Combine(Folder, KeyFor(romPath) + ".png");

		public static Bitmap? Load(string? path)
		{
			if(string.IsNullOrEmpty(path) || !File.Exists(path)) {
				return null;
			}
			try {
				using FileStream stream = File.OpenRead(path);
				return new Bitmap(stream);
			} catch {
				return null;
			}
		}

		public static void StartAutoCapture()
		{
			if(_timer != null) {
				return;
			}
			_timer = new DispatcherTimer(TimeSpan.FromSeconds(20), DispatcherPriority.Background, (_, _) => _ = CaptureCurrent(onlyGameplay: true));
			_timer.Start();
		}

		/// <summary>Saves the current frame as the running ROM's preview.</summary>
		public static async Task CaptureCurrent(bool onlyGameplay)
		{
			if(_busy || !EmuApi.IsRunning() || (onlyGameplay && EmuApi.IsPaused())) {
				return;
			}
			string romPath = EmuApi.GetRomInfo().RomPath;
			if(string.IsNullOrEmpty(romPath) || !File.Exists(romPath)) {
				return;
			}
			if(onlyGameplay) {
				OraclePanelRuntimeSummary? s = null;
				try {
					s = OraclePanelBridge.ReadRuntimeSummary();
				} catch {
					s = null;
				}
				//Zelda-based ROMs: only keep frames from real gameplay, not titles or menus
				if(s != null && !(s.ModeName is "Dungeon" or "Overworld" or "Special Area")) {
					return;
				}
			}

			_busy = true;
			try {
				Directory.CreateDirectory(Folder);
				await OracleLocalSocketClient.ExecuteAsync("SCREENSHOT", new Dictionary<string, string>() {
					["path"] = PathFor(romPath),
					["overwrite"] = "true"
				});
			} finally {
				_busy = false;
			}
		}
	}
}
