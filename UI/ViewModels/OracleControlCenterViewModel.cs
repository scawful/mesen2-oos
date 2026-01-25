using System;
using System.Threading.Tasks;
using Mesen.Utilities;
using ReactiveUI.Fody.Helpers;

namespace Mesen.ViewModels
{
	public class OracleControlCenterViewModel : ViewModelBase
	{
		[Reactive] public string GatewayStatus { get; private set; } = "Gateway: unknown";
		[Reactive] public string MesenStatus { get; private set; } = "Mesen2: unknown";
		[Reactive] public string SocketStatus { get; private set; } = "Socket: unknown";
		[Reactive] public string YazeStatus { get; private set; } = "Yaze: unknown";
		[Reactive] public string UpdatedStatus { get; private set; } = "Updated: unknown";
		[Reactive] public string GatewayError { get; private set; } = "";
		[Reactive] public bool HasGatewayError { get; private set; }
		[Reactive] public string SocketPath { get; private set; } = "";
		[Reactive] public bool HasSocketPath { get; private set; }

		[Reactive] public string StateLibrarySummary { get; private set; } = "No state library loaded.";
		[Reactive] public string StateLibraryPath { get; private set; } = "";

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

		private void ApplySnapshot(OracleStatusSnapshot snapshot)
		{
			GatewayStatus = snapshot.GatewayOnline ? "Gateway: online" : "Gateway: offline";
			if(snapshot.Mesen2Connected.HasValue) {
				MesenStatus = snapshot.Mesen2Connected.Value ? "Mesen2: connected" : "Mesen2: disconnected";
			} else {
				MesenStatus = "Mesen2: unknown";
			}

			SocketPath = snapshot.SocketPath ?? "";
			if(snapshot.SocketFound) {
				SocketStatus = string.IsNullOrWhiteSpace(SocketPath) ? "Socket: found" : $"Socket: found ({SocketPath})";
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

			GatewayError = snapshot.GatewayError ?? "";
			HasGatewayError = !string.IsNullOrWhiteSpace(GatewayError);
			HasSocketPath = !string.IsNullOrWhiteSpace(SocketPath);
		}
	}
}
