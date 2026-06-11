using Mesen.Interop;
using Mesen.Utilities;
using ReactiveUI.Fody.Helpers;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public class OracleSaveDataToggleViewModel : ViewModelBase
	{
		private bool _originalChecked;

		public string Key { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;

		[Reactive] public bool IsChecked { get; set; }

		public bool HasChanges => IsChecked != _originalChecked;

		public void SetOriginalChecked(bool value)
		{
			_originalChecked = value;
		}
	}

	public class OracleSaveDataToggleGroupViewModel : ViewModelBase
	{
		public string Title { get; init; } = string.Empty;
		public ObservableCollection<OracleSaveDataToggleViewModel> Options { get; init; } = new();
	}

	public class OracleSaveDataChoiceValueViewModel : ViewModelBase
	{
		public int Value { get; init; }
		public string Label { get; init; } = string.Empty;
	}

	public class OracleSaveDataChoiceViewModel : ViewModelBase
	{
		private int _originalValue;

		public string Key { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
		public ObservableCollection<OracleSaveDataChoiceValueViewModel> Choices { get; init; } = new();

		[Reactive] public OracleSaveDataChoiceValueViewModel? SelectedChoice { get; set; }

		public bool HasChanges => (SelectedChoice?.Value ?? _originalValue) != _originalValue;
		public int CurrentValue => SelectedChoice?.Value ?? _originalValue;

		public void SetOriginalValue(int value)
		{
			_originalValue = value;
			SelectedChoice = Choices.FirstOrDefault(choice => choice.Value == value) ?? Choices.FirstOrDefault();
		}
	}

	public class OracleSaveDataChoiceGroupViewModel : ViewModelBase
	{
		public string Title { get; init; } = string.Empty;
		public ObservableCollection<OracleSaveDataChoiceViewModel> Fields { get; init; } = new();
	}

	public class OracleSaveDataPresetViewModel : ViewModelBase
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
	}

	public class OracleSaveDataEditorViewModel : ViewModelBase
	{
		[Reactive] public ObservableCollection<OracleSaveDataToggleGroupViewModel> ItemGroups { get; private set; } = new();
		[Reactive] public ObservableCollection<OracleSaveDataToggleGroupViewModel> FlagGroups { get; private set; } = new();
		[Reactive] public ObservableCollection<OracleSaveDataChoiceGroupViewModel> ItemChoiceGroups { get; private set; } = new();
		[Reactive] public ObservableCollection<OracleSaveDataChoiceGroupViewModel> FlagChoiceGroups { get; private set; } = new();
		[Reactive] public ObservableCollection<OracleSaveDataPresetViewModel> Presets { get; private set; } = new();
		[Reactive] public string LocationSummary { get; private set; } = "Location: unavailable";
		[Reactive] public string StatsSummary { get; private set; } = string.Empty;
		[Reactive] public string StatusText { get; private set; } = "Loading save-data editor...";
		[Reactive] public bool IsBusy { get; private set; }
		[Reactive] public bool SyncToSramAfterApply { get; set; } = true;

		public async Task LoadAsync(bool preserveStatus = false)
		{
			if(IsBusy) {
				return;
			}

			if(!EmuApi.IsRunning()) {
				ItemGroups = new ObservableCollection<OracleSaveDataToggleGroupViewModel>();
				FlagGroups = new ObservableCollection<OracleSaveDataToggleGroupViewModel>();
				ItemChoiceGroups = new ObservableCollection<OracleSaveDataChoiceGroupViewModel>();
				FlagChoiceGroups = new ObservableCollection<OracleSaveDataChoiceGroupViewModel>();
				Presets = new ObservableCollection<OracleSaveDataPresetViewModel>();
				LocationSummary = "Location: no ROM loaded";
				StatsSummary = string.Empty;
				StatusText = "Load a ROM before editing Oracle save data.";
				return;
			}

			IsBusy = true;
			if(!preserveStatus) {
				StatusText = "Loading save-data editor...";
			}

			try {
				OraclePanelEditorData? data = await OraclePanelBridge.FetchEditorDataAsync();
				if(data == null) {
					if(!preserveStatus) {
						StatusText = "Unable to load Oracle save-data info.";
					}
					return;
				}

				ItemGroups = BuildToggleGroups(data.Items);
				FlagGroups = BuildToggleGroups(data.Flags);
				ItemChoiceGroups = BuildChoiceGroups(data.ItemChoices);
				FlagChoiceGroups = BuildChoiceGroups(data.FlagChoices);
				Presets = new ObservableCollection<OracleSaveDataPresetViewModel>(data.Presets.Select(preset => new OracleSaveDataPresetViewModel() {
					Id = preset.Id,
					Label = preset.Label,
					Description = preset.Description
				}));

				LocationSummary = $"Area: {data.State.AreaName} (0x{data.State.AreaId:X2}) | Map: {data.State.MapName} | Room: {data.State.RoomName} ({data.State.RoomLabel})";
				StatsSummary = $"Mode: {data.State.ModeName} / 0x{data.State.Submode:X2} | Link: {data.State.LinkX}, {data.State.LinkY} facing {data.State.LinkDirection} | HP {data.State.Health}/{data.State.MaxHealth} | Magic {data.State.Magic} | Rupees {data.State.Rupees} | GameState {data.State.GameState} | Crystals 0x{data.State.Crystals:X2} | Pendants 0x{data.State.Pendants:X2}";
				if(!preserveStatus) {
					StatusText = "Loaded live Oracle save-data controls.";
				}
			} finally {
				IsBusy = false;
			}
		}

		public async Task ApplyAsync()
		{
			if(IsBusy) {
				return;
			}

			List<OraclePanelValueUpdate> itemUpdates = ItemGroups
				.SelectMany(group => group.Options)
				.Where(option => option.HasChanges)
				.Select(option => new OraclePanelValueUpdate() { Key = option.Key, Value = option.IsChecked ? 1 : 0 })
				.Concat(ItemChoiceGroups
					.SelectMany(group => group.Fields)
					.Where(field => field.HasChanges)
					.Select(field => new OraclePanelValueUpdate() { Key = field.Key, Value = field.CurrentValue }))
				.ToList();

			List<OraclePanelValueUpdate> flagUpdates = FlagGroups
				.SelectMany(group => group.Options)
				.Where(option => option.HasChanges)
				.Select(option => new OraclePanelValueUpdate() { Key = option.Key, Value = option.IsChecked ? 1 : 0 })
				.Concat(FlagChoiceGroups
					.SelectMany(group => group.Fields)
					.Where(field => field.HasChanges)
					.Select(field => new OraclePanelValueUpdate() { Key = field.Key, Value = field.CurrentValue }))
				.ToList();

			if(itemUpdates.Count == 0 && flagUpdates.Count == 0) {
				StatusText = "No save-data changes to apply.";
				return;
			}

			await ApplyInternalAsync(itemUpdates, flagUpdates, null, "Applying save-data changes...");
		}

		public async Task ApplyPresetAsync(string presetId)
		{
			if(string.IsNullOrWhiteSpace(presetId) || IsBusy) {
				return;
			}

			await ApplyInternalAsync(new List<OraclePanelValueUpdate>(), new List<OraclePanelValueUpdate>(), presetId, $"Applying preset '{presetId}'...");
		}

		private async Task ApplyInternalAsync(IReadOnlyList<OraclePanelValueUpdate> itemUpdates, IReadOnlyList<OraclePanelValueUpdate> flagUpdates, string? presetId, string busyStatus)
		{
			IsBusy = true;
			StatusText = busyStatus;

			try {
				OraclePanelApplyResult? result = await OraclePanelBridge.ApplySaveDataChangesAsync(itemUpdates, flagUpdates, SyncToSramAfterApply, presetId);
				if(result == null) {
					StatusText = "Failed to apply save-data changes.";
					return;
				}

				await LoadAsync(true);
				string syncText = result.Synced
					? (result.SyncSlot.HasValue ? $" and synced to slot {result.SyncSlot.Value}" : " and synced to SRAM")
					: string.Empty;
				string presetText = string.IsNullOrWhiteSpace(presetId) ? string.Empty : $"Preset '{presetId}' applied; ";
				StatusText = $"{presetText}{result.ChangedItems + result.ChangedFlags} change(s) applied{syncText}.";
				MainWindowViewModel.Instance.OracleDebugPanel.RequestRefresh(true);
			} finally {
				IsBusy = false;
			}
		}

		private static ObservableCollection<OracleSaveDataToggleGroupViewModel> BuildToggleGroups(IEnumerable<OraclePanelToggleOption> options)
		{
			List<OracleSaveDataToggleGroupViewModel> groups = new();
			Dictionary<string, OracleSaveDataToggleGroupViewModel> index = new();

			foreach(OraclePanelToggleOption option in options) {
				if(!index.TryGetValue(option.Group, out OracleSaveDataToggleGroupViewModel? group)) {
					group = new OracleSaveDataToggleGroupViewModel() {
						Title = option.Group,
						Options = new ObservableCollection<OracleSaveDataToggleViewModel>()
					};
					index[option.Group] = group;
					groups.Add(group);
				}

				OracleSaveDataToggleViewModel toggle = new OracleSaveDataToggleViewModel() {
					Key = option.Key,
					Label = option.Label,
					Description = option.Description,
					IsChecked = option.Checked
				};
				toggle.SetOriginalChecked(option.Checked);
				group.Options.Add(toggle);
			}

			return new ObservableCollection<OracleSaveDataToggleGroupViewModel>(groups);
		}

		private static ObservableCollection<OracleSaveDataChoiceGroupViewModel> BuildChoiceGroups(IEnumerable<OraclePanelChoiceField> fields)
		{
			List<OracleSaveDataChoiceGroupViewModel> groups = new();
			Dictionary<string, OracleSaveDataChoiceGroupViewModel> index = new();

			foreach(OraclePanelChoiceField field in fields) {
				if(!index.TryGetValue(field.Group, out OracleSaveDataChoiceGroupViewModel? group)) {
					group = new OracleSaveDataChoiceGroupViewModel() {
						Title = field.Group,
						Fields = new ObservableCollection<OracleSaveDataChoiceViewModel>()
					};
					index[field.Group] = group;
					groups.Add(group);
				}

				OracleSaveDataChoiceViewModel choice = new OracleSaveDataChoiceViewModel() {
					Key = field.Key,
					Label = field.Label,
					Description = field.Description,
					Choices = new ObservableCollection<OracleSaveDataChoiceValueViewModel>(field.Choices.Select(value => new OracleSaveDataChoiceValueViewModel() {
						Value = value.Value,
						Label = value.Label
					}))
				};
				choice.SetOriginalValue(field.Value);
				group.Fields.Add(choice);
			}

			return new ObservableCollection<OracleSaveDataChoiceGroupViewModel>(groups);
		}
	}
}
