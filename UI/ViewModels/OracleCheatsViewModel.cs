using Mesen.Utilities;
using ReactiveUI;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public enum OracleWarpChoiceKind
	{
		Entrance,
		Area
	}

	public class OracleWarpChoiceViewModel
	{
		public OracleWarpChoiceKind Kind { get; init; }
		public int Id { get; init; }
		public string DisplayLabel { get; init; } = "";
		public string Detail { get; init; } = "";
		public string SearchText { get; init; } = "";
	}

	public class OracleCheckpointOptionViewModel
	{
		public string Id { get; init; } = "";
		public string Label { get; init; } = "";
		public string Notes { get; init; } = "";
		public bool IsTodo { get; init; }
		public string DisplayLabel => IsTodo ? $"TODO · {Label}" : Label;
	}

	public class OracleCheatChoiceValueViewModel
	{
		public int Value { get; init; }
		public string Label { get; init; } = "";
	}

	public class OracleCheatToggleViewModel : ViewModelBase
	{
		private bool _isChecked;
		private bool _suppressWrite;

		public OracleCheatField Field { get; init; } = new();
		public string Label => Field.Label;
		public string Tip => Field.Mask != 0 ? $"${Field.Address:X6} bit ${Field.Mask:X2}" : $"${Field.Address:X6}";

		public bool IsChecked
		{
			get => _isChecked;
			set {
				if(_isChecked == value) {
					return;
				}
				this.RaiseAndSetIfChanged(ref _isChecked, value);
				if(!_suppressWrite) {
					OracleCheatFields.Write(Field, value ? 1 : 0);
					ValueWritten?.Invoke(this, EventArgs.Empty);
				}
			}
		}

		public event EventHandler? ValueWritten;

		public void Load()
		{
			_suppressWrite = true;
			try {
				IsChecked = OracleCheatFields.Read(Field) != 0;
			} finally {
				_suppressWrite = false;
			}
		}
	}

	public class OracleCheatChoiceViewModel : ViewModelBase
	{
		private OracleCheatChoiceValueViewModel? _selected;
		private bool _suppressWrite;

		public OracleCheatField Field { get; init; } = new();
		public string Label => Field.Label;
		public string Tip => $"${Field.Address:X6}";
		public List<OracleCheatChoiceValueViewModel> Choices { get; init; } = new();

		public OracleCheatChoiceValueViewModel? SelectedChoice
		{
			get => _selected;
			set {
				if(ReferenceEquals(_selected, value)) {
					return;
				}
				this.RaiseAndSetIfChanged(ref _selected, value);
				if(!_suppressWrite && value != null) {
					OracleCheatFields.Write(Field, value.Value);
					ValueWritten?.Invoke(this, EventArgs.Empty);
				}
			}
		}

		public event EventHandler? ValueWritten;

		public void Load()
		{
			int value = OracleCheatFields.Read(Field);
			OracleCheatChoiceValueViewModel? match = Choices.FirstOrDefault(c => c.Value == value);
			if(ReferenceEquals(match, _selected)) {
				return;
			}
			_suppressWrite = true;
			try {
				SelectedChoice = match;
			} finally {
				_suppressWrite = false;
			}
		}
	}

	public class OracleCheatFlagGroupViewModel : ViewModelBase
	{
		private string _title = "";
		public uint Address { get; init; }
		public string Name { get; init; } = "";
		public string Title { get => _title; set => this.RaiseAndSetIfChanged(ref _title, value); }
		public List<OracleCheatToggleViewModel> Toggles { get; init; } = new();
	}

	/// <summary>Cheats & Warps section of the Oracle tools pane (see <see cref="OracleCheats"/>).</summary>
	public class OracleCheatsViewModel : ViewModelBase
	{
		private readonly Func<string, Func<OracleActionResult>, Task<bool>> _runAction;
		private readonly List<OracleWarpChoiceViewModel> _allWarps = new();
		private string _warpSearchText = "";
		private OracleWarpChoiceViewModel? _selectedWarp;
		private OracleCheckpointOptionViewModel? _selectedCheckpoint;
		private string _checkpointHint = "";
		private string _dataStatus = "";
		private bool _isItemsOpen;
		private bool _isFlagsOpen;

		public OracleCheatsViewModel(Func<string, Func<OracleActionResult>, Task<bool>> runAction)
		{
			_runAction = runAction;

			ItemChoices = OracleCheatFields.ItemChoices.Select(MakeChoice).ToList();
			ItemToggles = OracleCheatFields.ItemToggles.Select(MakeToggle).ToList();
			FlagChoices = OracleCheatFields.FlagChoices.Select(MakeChoice).ToList();
			FlagGroups = OracleCheatFields.FlagBits.GroupBy(f => f.Group).Select(g => new OracleCheatFlagGroupViewModel() {
				Address = g.First().Address,
				Name = g.Key,
				Title = g.Key,
				Toggles = g.Select(MakeToggle).ToList()
			}).ToList();

			ReloadData(false);
		}

		public ObservableCollection<OracleWarpChoiceViewModel> WarpResults { get; } = new();
		public ObservableCollection<OracleCheckpointOptionViewModel> Checkpoints { get; } = new();
		public List<OracleCheatChoiceViewModel> ItemChoices { get; }
		public List<OracleCheatToggleViewModel> ItemToggles { get; }
		public List<OracleCheatChoiceViewModel> FlagChoices { get; }
		public List<OracleCheatFlagGroupViewModel> FlagGroups { get; }

		public string WarpSearchText
		{
			get => _warpSearchText;
			set {
				this.RaiseAndSetIfChanged(ref _warpSearchText, value);
				FilterWarps();
			}
		}

		public OracleWarpChoiceViewModel? SelectedWarp { get => _selectedWarp; set => this.RaiseAndSetIfChanged(ref _selectedWarp, value); }

		public OracleCheckpointOptionViewModel? SelectedCheckpoint
		{
			get => _selectedCheckpoint;
			set {
				this.RaiseAndSetIfChanged(ref _selectedCheckpoint, value);
				UpdateCheckpointHint();
			}
		}

		public string CheckpointHint { get => _checkpointHint; private set => this.RaiseAndSetIfChanged(ref _checkpointHint, value); }
		public string DataStatus { get => _dataStatus; private set => this.RaiseAndSetIfChanged(ref _dataStatus, value); }

		public bool IsItemsOpen
		{
			get => _isItemsOpen;
			set {
				this.RaiseAndSetIfChanged(ref _isItemsOpen, value);
				RefreshValues();
			}
		}

		public bool IsFlagsOpen
		{
			get => _isFlagsOpen;
			set {
				this.RaiseAndSetIfChanged(ref _isFlagsOpen, value);
				RefreshValues();
			}
		}

		public void ReloadData(bool force)
		{
			OracleCheatData data = OracleCheats.GetData(force);
			DataStatus = data.Error ?? $"{data.Entrances.Count} entrances, {data.Areas.Count} areas, {data.Checkpoints.Count(c => !c.IsTodo)} checkpoints";

			_allWarps.Clear();
			foreach(OracleCheatEntrance e in data.Entrances) {
				string room = e.Room.HasValue ? $"room ${e.Room.Value:X3}" : "room ?";
				string area = e.Area.HasValue ? $", door on area ${e.Area.Value:X2} {e.AreaName}" : "";
				_allWarps.Add(new OracleWarpChoiceViewModel() {
					Kind = OracleWarpChoiceKind.Entrance,
					Id = e.Id,
					DisplayLabel = $"{GroupTag(e.Group)} {e.Label} · ${e.Id:X2}",
					Detail = $"Entrance ${e.Id:X2} -> {room}{area}. Exit: {e.Exit}.",
					SearchText = $"{e.Label} {e.Name} {e.Group} {e.AreaName} {e.Id:X2} 0x{e.Id:X2} ${e.Id:X2}".ToLowerInvariant()
				});
			}
			foreach(OracleCheatArea a in data.Areas) {
				_allWarps.Add(new OracleWarpChoiceViewModel() {
					Kind = OracleWarpChoiceKind.Area,
					Id = a.Area,
					DisplayLabel = $"[area] {a.Name} · ${a.Area:X2}",
					Detail = $"Overworld area ${a.Area:X2} ({a.World}), lands {a.Landing} (exit of room ${a.ViaRoom:X3}).",
					SearchText = $"area {a.Name} {a.World} {a.Landing} {a.Area:X2} 0x{a.Area:X2} ${a.Area:X2}".ToLowerInvariant()
				});
			}
			FilterWarps();

			string? previous = SelectedCheckpoint?.Id;
			Checkpoints.Clear();
			foreach(OracleCheckpoint c in data.Checkpoints) {
				Checkpoints.Add(new OracleCheckpointOptionViewModel() { Id = c.Id, Label = c.Label, Notes = c.Notes, IsTodo = c.IsTodo });
			}
			SelectedCheckpoint = Checkpoints.FirstOrDefault(c => c.Id == previous) ?? Checkpoints.FirstOrDefault(c => !c.IsTodo);
		}

		public async Task ExecuteWarpAsync()
		{
			OracleWarpChoiceViewModel? choice = SelectedWarp ?? (WarpResults.Count == 1 ? WarpResults[0] : null);
			if(choice == null) {
				await _runAction("warp", () => OracleActionResult.Fail("Pick a warp target from the list."));
				return;
			}
			await _runAction("warp", () => choice.Kind == OracleWarpChoiceKind.Entrance
				? OracleCheats.WarpToEntrance(choice.Id)
				: OracleCheats.WarpToArea(choice.Id));
			RefreshValues();
		}

		public async Task ExecuteCheckpointAsync()
		{
			OracleCheckpointOptionViewModel? cp = SelectedCheckpoint;
			if(cp == null) {
				return;
			}
			string id = cp.Id;
			await _runAction("checkpoint", () => OracleCheats.ApplyCheckpoint(id));
			RefreshValues();
			UpdateCheckpointHint();
		}

		public Task ExecuteRefillAsync()
		{
			return _runAction("refill", () => {
				if(!OraclePanelBridge.CanReadState()) {
					return OracleActionResult.Fail("No game running.");
				}
				OraclePanelBridge.WriteByte(0x7EF36D, OraclePanelBridge.ReadByte(0x7EF36C));
				OraclePanelBridge.WriteByte(0x7EF36E, 0x80);
				return OracleActionResult.Ok("Health and magic refilled.");
			});
		}

		/// <summary>Re-read item and flag values (only while their section is open).</summary>
		public void RefreshValues()
		{
			if((!IsItemsOpen && !IsFlagsOpen) || !OraclePanelBridge.CanReadState()) {
				return;
			}
			if(IsItemsOpen) {
				ItemChoices.ForEach(c => c.Load());
				ItemToggles.ForEach(t => t.Load());
			}
			if(IsFlagsOpen) {
				FlagChoices.ForEach(c => c.Load());
				foreach(OracleCheatFlagGroupViewModel group in FlagGroups) {
					group.Toggles.ForEach(t => t.Load());
					group.Title = group.Name.StartsWith("Misc") ? group.Name : $"{group.Name} = ${OraclePanelBridge.ReadByte(group.Address):X2}";
				}
			}
		}

		public string ConfirmCheckpointText()
		{
			OracleCheckpointOptionViewModel? cp = SelectedCheckpoint;
			int slot = OraclePanelBridge.CanReadState() ? OracleCheats.GetActiveSaveSlot() : 1;
			return $"Jump to '{cp?.Label}'?\n\nThis overwrites story flags and items in WRAM and in save file {slot} (SRAM main + mirror). "
				+ "Other save files are not touched. Save a state first if you want to come back.";
		}

		private void UpdateCheckpointHint()
		{
			OracleCheckpointOptionViewModel? cp = SelectedCheckpoint;
			if(cp == null) {
				CheckpointHint = DataStatus;
				return;
			}
			int slot = OraclePanelBridge.CanReadState() ? OracleCheats.GetActiveSaveSlot() : 1;
			CheckpointHint = cp.IsTodo ? cp.Notes : $"Writes WRAM + save file {slot}. {cp.Notes}";
		}

		private void FilterWarps()
		{
			string[] words = (WarpSearchText ?? "").ToLowerInvariant().Split(' ', StringSplitOptions.RemoveEmptyEntries);
			OracleWarpChoiceViewModel? keep = SelectedWarp;
			WarpResults.Clear();
			foreach(OracleWarpChoiceViewModel w in _allWarps.Where(w => words.All(word => w.SearchText.Contains(word)))) {
				WarpResults.Add(w);
			}
			SelectedWarp = keep != null && WarpResults.Contains(keep) ? keep : (WarpResults.Count == 1 ? WarpResults[0] : null);
		}

		private OracleCheatChoiceViewModel MakeChoice(OracleCheatField field)
		{
			OracleCheatChoiceViewModel vm = new() {
				Field = field,
				Choices = field.Choices.Select(c => new OracleCheatChoiceValueViewModel() { Value = c.Value, Label = c.Label }).ToList()
			};
			return vm;
		}

		private OracleCheatToggleViewModel MakeToggle(OracleCheatField field)
		{
			OracleCheatToggleViewModel vm = new() { Field = field };
			vm.ValueWritten += (_, _) => RefreshValues();
			return vm;
		}

		private static string GroupTag(string group)
		{
			return group switch {
				"dungeon" => "[dungeon]",
				"shrine" => "[shrine]",
				"town" => "[town]",
				"cave" => "[cave]",
				_ => "[entrance]"
			};
		}
	}
}
