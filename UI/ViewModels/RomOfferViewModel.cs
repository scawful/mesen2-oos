using Avalonia.Threading;
using Mesen.Interop;
using ReactiveUI.Fody.Helpers;
using System;
using System.Text.Json;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public record RomOfferLoadResult(bool Ok, string Path, string Label);

	/// <summary>
	/// "New ROM available" banner. Agents create the offer over the socket (OFFER_ROM);
	/// the core keeps it until it is loaded, replaced or withdrawn.
	/// Load now runs the same keep-the-save load as the socket LOAD_ROM_LIVE command.
	/// </summary>
	public class RomOfferViewModel : ViewModelBase
	{
		[Reactive] public bool IsVisible { get; private set; }
		[Reactive] public bool IsLoading { get; private set; }
		[Reactive] public string Title { get; private set; } = "";
		[Reactive] public string Notes { get; private set; } = "";
		[Reactive] public string Details { get; private set; } = "";

		public bool IsPending { get; private set; }
		public string Label { get; private set; } = "";

		public event EventHandler? VisibilityChanged;
		public event EventHandler? LiveLoadStarted;
		public event EventHandler<RomOfferLoadResult>? LiveLoadFinished;

		private uint _loadCount;
		private bool _coreLoading;

		/// <summary>Apply the core's offer state (JSON from EmuApi.GetRomOffer). UI thread only.</summary>
		public void Update(string json)
		{
			bool pending, snoozed, loading;
			string path, label, notes;
			uint loadCount = 0;
			bool lastOk = false;
			string lastPath = "";
			string lastLabel = "";
			string lastError = "";
			try {
				using JsonDocument doc = JsonDocument.Parse(json);
				JsonElement root = doc.RootElement;
				pending = root.GetProperty("pending").GetBoolean();
				snoozed = root.GetProperty("snoozed").GetBoolean();
				loading = root.GetProperty("loading").GetBoolean();
				path = root.GetProperty("path").GetString() ?? "";
				label = root.GetProperty("label").GetString() ?? "";
				notes = root.GetProperty("notes").GetString() ?? "";
				if(root.GetProperty("last_load") is { ValueKind: JsonValueKind.Object } last) {
					loadCount = last.GetProperty("count").GetUInt32();
					lastOk = last.GetProperty("ok").GetBoolean();
					lastPath = last.GetProperty("path").GetString() ?? "";
					lastLabel = last.GetProperty("label").GetString() ?? "";
					lastError = last.GetProperty("error").GetString() ?? "";
				}
			} catch(Exception ex) when(ex is JsonException || ex is InvalidOperationException || ex is System.Collections.Generic.KeyNotFoundException) {
				return;
			}

			if(loading && !_coreLoading) {
				LiveLoadStarted?.Invoke(this, EventArgs.Empty);
			}
			_coreLoading = loading;

			IsPending = pending;
			IsLoading = loading;
			Label = label;
			Title = loading ? $"Loading {label}..." : $"New ROM available: {label}";
			Notes = notes.Replace("\r", " ").Replace("\n", " ");
			Details = path;

			if(loadCount != _loadCount) {
				_loadCount = loadCount;
				if(!lastOk && pending) {
					Notes = "Load failed: " + lastError;
				}
				LiveLoadFinished?.Invoke(this, new RomOfferLoadResult(lastOk, lastPath, lastLabel));
			}

			//A direct load-rom-live of another path changes nothing on screen
			bool visible = pending && (loading || !snoozed);
			if(visible != IsVisible) {
				IsVisible = visible;
				VisibilityChanged?.Invoke(this, EventArgs.Empty);
			}
		}

		public void LoadNow()
		{
			EmuApi.WriteLogEntry($"[RomOffer] Load now: label={Label} pending={IsPending} loading={IsLoading}");
			if(!IsPending || IsLoading) {
				return;
			}

			IsLoading = true;
			Title = $"Loading {Label}...";
			Task.Run(() => {
				//The core reports progress through RomOfferChanged; refresh here only when it refused to start
				if(!EmuApi.AcceptRomOffer()) {
					EmuApi.WriteLogEntry("[RomOffer] Load now refused by the core; see OFFER_ROM_STATUS");
					Dispatcher.UIThread.Post(() => {
						IsLoading = false;
						Update(EmuApi.GetRomOffer());
					});
				}
			});
		}

		public void Later()
		{
			EmuApi.SnoozeRomOffer();
		}
	}
}
