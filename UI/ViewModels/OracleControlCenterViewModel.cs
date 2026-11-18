using System;
using System.Threading.Tasks;
using Mesen.Config;
using Mesen.Utilities;
using ReactiveUI.Fody.Helpers;

namespace Mesen.ViewModels
{
	public class OracleControlCenterViewModel : ViewModelBase
	{
		[Reactive] public string MesenStatus { get; private set; } = "Mesen2: unknown";
		[Reactive] public string SocketStatus { get; private set; } = "Socket: unknown";
		[Reactive] public string YazeStatus { get; private set; } = "Yaze: unknown";
		[Reactive] public string UpdatedStatus { get; private set; } = "Updated: unknown";
		[Reactive] public string SocketPath { get; private set; } = "";
		[Reactive] public bool HasSocketPath { get; private set; }

		[Reactive] public string StateLibrarySummary { get; private set; } = "No state library loaded.";
		[Reactive] public string StateLibraryPath { get; private set; } = "";
		[Reactive] public string SaveStateConfigSummary { get; private set; } = "";
		[Reactive] public string SaveStateFolder { get; private set; } = "";
		[Reactive] public UInt32 SaveStateSlotCount { get; set; }
		[Reactive] public bool SeparateSaveStatesByPatch { get; set; }

		public OracleControlCenterViewModel()
		{
			RefreshSaveStateConfig();
		}

		public async Task RefreshStatusAsync()
		{
			OracleStatusSnapshot snapshot = await OracleAgentStatus.FetchAsync();
			ApplySnapshot(snapshot);
		}

		public void RefreshStateLibrarySummary()
		{
			var manifest = OracleStateLibrary.GetManifest();
			int count = manifest.Entries.Count;
			string latest = count > 0 ? manifest.Entries[0].Label : "none";
			StateLibrarySummary = count == 0
				? "No labeled states saved yet."
				: $"{count} labeled state{(count == 1 ? "" : "s")} (latest: {latest})";
			StateLibraryPath = "Library path: " + OracleStateLibrary.GetLibraryRootPath();
		}

		public void RefreshSaveStateConfig()
		{
			var prefs = ConfigManager.Config.Preferences;
			SaveStateSlotCount = prefs.SaveStateSlotCount == 0 ? 20 : prefs.SaveStateSlotCount;
			SeparateSaveStatesByPatch = prefs.SeparateSaveStatesByPatch;
			SaveStateFolder = "Save State folder: " + ConfigManager.SaveStateFolder;

			UpdateSaveStateSummary();
		}

		public void ApplySaveStateConfig()
		{
			var prefs = ConfigManager.Config.Preferences;
			if(SaveStateSlotCount < 1) {
				SaveStateSlotCount = 1;
			} else if(SaveStateSlotCount > 99) {
				SaveStateSlotCount = 99;
			}

			prefs.SaveStateSlotCount = SaveStateSlotCount;
			prefs.SeparateSaveStatesByPatch = SeparateSaveStatesByPatch;
			prefs.ApplyConfig();

			ConfigManager.Config.Save();

			SaveStateFolder = "Save State folder: " + ConfigManager.SaveStateFolder;
			UpdateSaveStateSummary();
		}

		private void UpdateSaveStateSummary()
		{
			uint autoSlot = SaveStateSlotCount + 1;
			string patchMode = SeparateSaveStatesByPatch ? "per patch" : "shared";
			SaveStateConfigSummary = $"Slots: {SaveStateSlotCount} (auto: {autoSlot}) · {patchMode}";
		}

		private void ApplySnapshot(OracleStatusSnapshot snapshot)
		{
			if(snapshot.MesenRunning) {
				string romSuffix = string.IsNullOrWhiteSpace(snapshot.RomName) ? "" : $" ({snapshot.RomName})";
				MesenStatus = snapshot.MesenPaused ? $"Mesen2: paused{romSuffix}" : $"Mesen2: running{romSuffix}";
			} else {
				MesenStatus = "Mesen2: idle (no ROM)";
			}

			SocketPath = snapshot.SocketPath ?? "";
			if(snapshot.SocketFound) {
				SocketStatus = string.IsNullOrWhiteSpace(SocketPath) ? "Socket: active" : $"Socket: active ({SocketPath})";
			} else {
				SocketStatus = "Socket: missing";
			}

			if(snapshot.YazeRunning.HasValue) {
				YazeStatus = snapshot.YazeRunning.Value ? "Yaze: running" : "Yaze: stopped";
			} else {
				YazeStatus = "Yaze: unknown";
			}

			if(snapshot.Timestamp == DateTimeOffset.MinValue) {
				UpdatedStatus = "Updated: unknown";
			} else {
				UpdatedStatus = "Updated: " + snapshot.Timestamp.ToLocalTime().ToString("HH:mm:ss");
			}

			HasSocketPath = !string.IsNullOrWhiteSpace(SocketPath);
		}
	}
}
