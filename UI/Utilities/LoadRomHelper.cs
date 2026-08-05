using Avalonia.Controls;
using Avalonia.Threading;
using Mesen.Config;
using Mesen.Interop;
using Mesen.Localization;
using Mesen.ViewModels;
using Mesen.Windows;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	public static class LoadRomHelper
	{
		private static readonly object _pendingLoadLock = new();
		private static readonly HashSet<Task> _pendingLoadTasks = new();
		private static readonly CancellationTokenSource _loadShutdownCts = new();
		private static bool _loadShutdownStarted;

		private static bool IsLoadShutdownStarted
		{
			get {
				lock(_pendingLoadLock) {
					return _loadShutdownStarted;
				}
			}
		}

		private static Task RunTrackedLoad(Func<CancellationToken, Task> action)
		{
			Task task;
			lock(_pendingLoadLock) {
				if(_loadShutdownStarted) {
					return Task.CompletedTask;
				}

				task = Task.Run(() => action(_loadShutdownCts.Token), _loadShutdownCts.Token);
				_pendingLoadTasks.Add(task);
			}

			_ = task.ContinueWith(completedTask => {
				lock(_pendingLoadLock) {
					_pendingLoadTasks.Remove(completedTask);
				}
			}, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
			return task;
		}

		public static Task BeginShutdownAsync()
		{
			Task[] pendingLoads;
			lock(_pendingLoadLock) {
				_loadShutdownStarted = true;
				pendingLoads = _pendingLoadTasks.ToArray();
			}

			_loadShutdownCts.Cancel();
			return Task.WhenAll(pendingLoads);
		}

		public static async void LoadRom(ResourcePath romPath, ResourcePath? patchPath = null)
		{
			if(IsLoadShutdownStarted) {
				return;
			}

			if(FolderHelper.IsArchiveFile(romPath)) {
				ResourcePath? selectedRom = await SelectRomWindow.Show(romPath);
				if(selectedRom == null || IsLoadShutdownStarted) {
					return;
				}
				romPath = selectedRom.Value;
			}

			if(patchPath == null && ConfigManager.Config.Preferences.AutoLoadPatches) {
				string[] extensions = new string[3] { ".ips", ".ups", ".bps" };
				foreach(string ext in extensions) {
					string file = Path.Combine(romPath.Folder, Path.GetFileNameWithoutExtension(romPath.FileName)) + ext;
					if(File.Exists(file)) {
						patchPath = file;
						break;
					}
				}
			}

			InternalLoadRom(romPath, patchPath);
		}

		private static void InternalLoadRom(ResourcePath romPath, ResourcePath? patchPath)
		{
			if(IsLoadShutdownStarted) {
				return;
			}

			//Temporarily hide selection screen to allow displaying error messages
			MainWindowViewModel.Instance.RecentGames.Visible = false;

			_ = RunTrackedLoad(async shutdownToken => {
				//Run in another thread to prevent deadlocks etc. when emulator notifications are processed UI-side
				shutdownToken.ThrowIfCancellationRequested();
				if(EmuApi.LoadRom(romPath, patchPath)) {
					ConfigManager.Config.RecentFiles.AddRecentFile(romPath, patchPath);
					ConfigManager.Config.Save();
				}
				await ShowSelectionOnScreenAfterError(shutdownToken);
			});
		}

		public static void LoadRecentGame(string filename, bool forceLoadState)
		{
			if(IsLoadShutdownStarted) {
				return;
			}

			//Temporarily hide selection screen to allow displaying error messages
			MainWindowViewModel.Instance.RecentGames.Visible = false;

			_ = RunTrackedLoad(async shutdownToken => {
				//Run in another thread to prevent deadlocks etc. when emulator notifications are processed UI-side
				shutdownToken.ThrowIfCancellationRequested();
				if(File.Exists(filename)) {
					EmuApi.LoadRecentGame(filename, !forceLoadState && ConfigManager.Config.Preferences.GameSelectionScreenMode == GameSelectionMode.PowerOn);
				}
				await ShowSelectionOnScreenAfterError(shutdownToken);
			});
		}

		private static async Task ShowSelectionOnScreenAfterError(CancellationToken shutdownToken)
		{
			if(ConfigManager.Config.Preferences.GameSelectionScreenMode != GameSelectionMode.Disabled) {
				await Task.Delay(3100, shutdownToken).ConfigureAwait(false);
				shutdownToken.ThrowIfCancellationRequested();
				if(!EmuApi.IsRunning()) {
					//No game was loaded, show game selection screen again after ~3 seconds
					//This allows error messages to be visible to the user
					Dispatcher.UIThread.Post(() => {
						if(!IsLoadShutdownStarted) {
							MainWindowViewModel.Instance.RecentGames.Visible = true;
						}
					});
				}
			}
		}

		public static async void LoadPatchFile(string patchFile)
		{
			if(IsLoadShutdownStarted) {
				return;
			}

			string? patchFolder = Path.GetDirectoryName(patchFile);
			if(patchFolder == null) {
				return;
			}

			List<string> romsInFolder = new List<string>();
			foreach(string filepath in Directory.EnumerateFiles(patchFolder)) {
				if(FolderHelper.IsRomFile(filepath)) {
					romsInFolder.Add(filepath);
				}
			}

			if(romsInFolder.Count == 1) {
				//There is a single rom in the same folder as the IPS/BPS patch, use it automatically
				LoadRom(romsInFolder[0], patchFile);
			} else {
				Window? wnd = ApplicationHelper.GetMainWindow();
				if(IsLoadShutdownStarted) {
					return;
				}

				if(!EmuApi.IsRunning()) {
					//Prompt the user for a rom to load
					if(await MesenMsgBox.Show(wnd, "SelectRomIps", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) == DialogResult.OK) {
						if(IsLoadShutdownStarted) {
							return;
						}
						string? filename = await FileDialogHelper.OpenFile(null, wnd, FileDialogHelper.RomExt);
						if(filename != null && !IsLoadShutdownStarted) {
							LoadRom(filename, patchFile);
						}
					}
				} else if(await MesenMsgBox.Show(wnd, "PatchAndReset", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) == DialogResult.OK) {
					//Confirm that the user wants to patch the current rom and reset
					if(IsLoadShutdownStarted) {
						return;
					}
					LoadRom(EmuApi.GetRomInfo().RomPath, patchFile);
				}
			}
		}

		private static bool IsPatchFile(string filename)
		{
			using(FileStream? stream = FileHelper.OpenRead(filename)) {
				if(stream != null) {
					byte[] header = new byte[5];
					stream.Read(header, 0, 5);
					if(header[0] == 'P' && header[1] == 'A' && header[2] == 'T' && header[3] == 'C' && header[4] == 'H') {
						return true;
					} else if((header[0] == 'U' || header[0] == 'B') && header[1] == 'P' && header[2] == 'S' && header[3] == '1') {
						return true;
					}
				}
			}
			return false;
		}

		public static void LoadFile(string filename)
		{
			if(IsLoadShutdownStarted) {
				return;
			}

			if(File.Exists(filename)) {
				if(IsPatchFile(filename)) {
					LoadPatchFile(filename);
				} else if(Path.GetExtension(filename).ToLowerInvariant() == "." + FileDialogHelper.MesenSaveStateExt) {
					EmuApi.LoadStateFile(filename);
				} else if(EmuApi.IsRunning() && Path.GetExtension(filename).ToLowerInvariant() == "." + FileDialogHelper.MesenMovieExt) {
					RecordApi.MoviePlay(filename);
				} else {
					LoadRom(filename);
				}
			} else {
				DisplayMessageHelper.DisplayMessage("Error", ResourceHelper.GetMessage("FileNotFound", filename));
			}
		}
	}
}
