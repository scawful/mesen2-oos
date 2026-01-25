using Mesen.Config;
using Mesen.Interop;
using Mesen.Localization;
using Mesen.Utilities;
using ReactiveUI;
using ReactiveUI.Fody.Helpers;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Reactive.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public class RecentGamesViewModel : ViewModelBase
	{
		[Reactive] public bool Visible { get; set; }
		[Reactive] public bool NeedResume { get; private set; }
		[Reactive] public string Title { get; private set; } = "";
		[Reactive] public GameScreenMode Mode { get; private set; }
		[Reactive] public List<RecentGameInfo> GameEntries { get; private set; } = new List<RecentGameInfo>();
		
		public RecentGamesViewModel()
		{
			Visible = ConfigManager.Config.Preferences.GameSelectionScreenMode != GameSelectionMode.Disabled;
		}

		public void Init(GameScreenMode mode)
		{
			if(mode == GameScreenMode.RecentGames && ConfigManager.Config.Preferences.GameSelectionScreenMode == GameSelectionMode.Disabled) {
				Visible = false;
				GameEntries = new List<RecentGameInfo>();
				return;
			} else if(mode != GameScreenMode.RecentGames && Mode == mode && Visible) {
				Visible = false;
				if(NeedResume) {
					EmuApi.Resume();
				}
				return;
			}

			if(Mode == mode && Visible && GameEntries.Count > 0) {
				//Prevent flickering when closing the config window while no game is running
				//No need to update anything if the game selection screen is already visible
				return;
			}

			Mode = mode;

			List<RecentGameInfo> entries = new();

			if(mode == GameScreenMode.RecentGames) {
				NeedResume = false;
				Title = string.Empty;

				List<string> files = Directory.GetFiles(ConfigManager.RecentGamesFolder, "*.rgd").OrderByDescending((file) => new FileInfo(file).LastWriteTime).ToList();
				for(int i = 0; i < files.Count && entries.Count < 72; i++) {
					entries.Add(new RecentGameInfo() { FileName = files[i], Name = Path.GetFileNameWithoutExtension(files[i]) });
				}
			} else {
				if(!Visible) {
					NeedResume = Pause();
				}

				Title = mode == GameScreenMode.LoadState ? ResourceHelper.GetMessage("LoadStateDialog") : ResourceHelper.GetMessage("SaveStateDialog");
				
				int maxSlots = SaveStateSlotHelper.GetMaxSlots();
				for(int i = 0; i < maxSlots; i++) {
					int slotIndex = i + 1;
					string statePath = SaveStateSlotHelper.GetSaveStatePath(slotIndex);
					entries.Add(new RecentGameInfo() {
						FileName = statePath,
						StateIndex = slotIndex,
						Name = BuildStateEntryName(slotIndex, statePath, false),
						SaveMode = mode == GameScreenMode.SaveState
					});
				}
				if(mode == GameScreenMode.LoadState) {
					int autoSlot = SaveStateSlotHelper.GetAutoSlot();
					string autoStatePath = SaveStateSlotHelper.GetSaveStatePath(autoSlot);
					entries.Add(new RecentGameInfo() {
						FileName = autoStatePath,
						StateIndex = autoSlot,
						Name = BuildStateEntryName(autoSlot, autoStatePath, true),
						SaveMode = false
					});
					entries.Add(new RecentGameInfo() {
						FileName = SaveStateSlotHelper.GetRecentGamePath(),
						Name = ResourceHelper.GetMessage("LastSession")
					});
				}
			}

			Visible = entries.Count > 0;
			GameEntries = entries;
		}

		private static string BuildStateEntryName(int slotIndex, string statePath, bool isAutoSlot)
		{
			string baseName = isAutoSlot
				? ResourceHelper.GetMessage("AutoSave")
				: ResourceHelper.GetMessage("SlotNumber", slotIndex);

			string? label = SaveStateLabelStore.TryGetLabel(statePath);
			string normalized = NormalizeSaveStateLabel(label);
			return string.IsNullOrWhiteSpace(normalized) ? baseName : baseName + ": " + normalized;
		}

		private static string NormalizeSaveStateLabel(string? label, int maxLength = 48)
		{
			if(string.IsNullOrWhiteSpace(label)) {
				return string.Empty;
			}

			string normalized = label.Replace("\r", " ").Replace("\n", " ").Trim();
			if(normalized.Length <= maxLength) {
				return normalized;
			}

			int trimmedLength = Math.Max(0, maxLength - 3);
			if(trimmedLength == 0) {
				return string.Empty;
			}

			return normalized.Substring(0, trimmedLength) + "...";
		}

		private bool Pause()
		{
			if(!EmuApi.IsPaused()) {
				EmuApi.Pause();
				return true;
			}
			return false;
		}
	}

	public enum GameScreenMode
	{
		RecentGames,
		LoadState,
		SaveState
	}

	public class RecentGameInfo
	{
		public string FileName { get; set; } = "";
		public int StateIndex { get; set; } = -1;
		public string Name { get; set; } = "";
		public bool SaveMode { get; set; } = false;

		public bool IsEnabled()
		{
			return SaveMode || File.Exists(FileName);
		}

		public void Load()
		{
			if(StateIndex > 0) {
				Task.Run(() => {
					//Run in another thread to prevent deadlocks etc. when emulator notifications are processed UI-side
					if(SaveMode) {
						EmuApi.SaveState((uint)StateIndex);
					} else {
						EmuApi.LoadState((uint)StateIndex);
					}
					EmuApi.Resume();
				});
			} else {
				LoadRomHelper.LoadRecentGame(FileName, false);
				EmuApi.Resume();
			}
		}
	}
}
