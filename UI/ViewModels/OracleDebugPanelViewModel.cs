using Mesen.Config;
using Mesen.Interop;
using Mesen.Utilities;
using ReactiveUI;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public enum OracleToolPage
	{
		Travel,
		Items,
		Progress,
		Capture,
		Macros,
		Log
	}

	public class OracleMacroButtonViewModel
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Shortcut { get; init; } = string.Empty;
		public bool IsPrimary { get; init; }
		public string DisplayLabel {
			get {
				string baseLabel = IsPrimary ? "★ " + Label : Label;
				return string.IsNullOrWhiteSpace(Shortcut) ? baseLabel : $"{baseLabel} ({Shortcut})";
			}
		}
	}

	public class OracleWarpTargetViewModel
	{
		public OracleWarpTarget Target { get; init; } = new();
		public string DisplayLabel => Target.Kind == OracleWarpKind.Entrance
			? $"{Target.Label} · entrance ${Target.Value:X2}"
			: $"{Target.Label} · fall";
	}

	public class OraclePresetOptionViewModel
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
	}

	public class OracleFlagOptionViewModel
	{
		public string Key { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
	}

	/// <summary>
	/// Shared model for the Oracle status strip (top) and tools pane (right).
	/// All actions run in-process through <see cref="OracleDebugActions"/>.
	/// </summary>
	public class OracleDebugPanelViewModel : ViewModelBase
	{
		public const double ToolsPaneWidth = 300;
		private const int MaxSessionEntries = 200;

		private OracleDebugPanelVisibility _visibilityMode;
		private bool _isTextInputFocused;
		private bool _refreshInFlight;
		private bool _commandInFlight;
		private bool _canFallToRoom;
		private bool _hasMacroError;
		private OracleToolPage _selectedToolPage = OracleToolPage.Travel;
		private readonly List<string> _sessionEntries = new();
		private readonly Dictionary<string, List<MacroStep>> _macroStepsById = new(StringComparer.OrdinalIgnoreCase);

		private string _locationText = "No game";
		private string _linkText = "";
		private string _healthText = "";
		private string _formText = "";
		private string _frameText = "";
		private string _modeText = "-";
		private string _whereText = "-";
		private string _linkDetailText = "-";
		private string _statsText = "-";
		private string _storyText = "-";
		private string _romText = "-";
		private string _pauseButtonText = "Pause";
		private string _statusLine = "";
		private string _sessionLog = "";
		private string _lastCaptureText = "";
		private string _captureLabel = "";
		private string _captureTags = "";
		private string _captureNotes = "";
		private string _warpEntranceHex = "";
		private string _fallRoomHex = "";
		private string _frameAdvanceCount = "1";
		private string _macroProfilePath = "";
		private string _macroStatus = "No macro profile loaded.";
		private OracleWarpTargetViewModel? _selectedWarpTarget;
		private OraclePresetOptionViewModel? _selectedPreset;
		private OracleFlagOptionViewModel? _selectedFlag;

		public event EventHandler? VisibilityModeChanged;

		public OracleDebugPanelViewModel()
		{
			_visibilityMode = ConfigManager.Config.Oracle.DebugPanelVisibility;
			if(!Enum.IsDefined(_visibilityMode)) {
				_visibilityMode = OracleDebugPanelVisibility.Collapsed;
			}

			WarpTargets = new ObservableCollection<OracleWarpTargetViewModel>(
				OracleDebugActions.WarpTargets.Select(t => new OracleWarpTargetViewModel() { Target = t })
			);
			_selectedWarpTarget = WarpTargets.FirstOrDefault();

			Presets = new ObservableCollection<OraclePresetOptionViewModel>();
			ReloadPresets();

			FlagOptions = new ObservableCollection<OracleFlagOptionViewModel>(
				OracleDebugActions.GetFlags().Select(f => new OracleFlagOptionViewModel() { Key = f.Key, Label = f.Description })
			);
			_selectedFlag = FlagOptions.FirstOrDefault();

			Cheats = new OracleCheatsViewModel(RunActionAsync);

			MacroButtons = new ObservableCollection<OracleMacroButtonViewModel>();
			MacroProfilePath = ExpandHome(ConfigManager.Config.Oracle.DebugPanelMacroProfilePath);
			if(!string.IsNullOrWhiteSpace(MacroProfilePath)) {
				_ = ReloadMacrosAsync();
			}
		}

		public OracleDebugPanelVisibility VisibilityMode {
			get => _visibilityMode;
			private set {
				if(_visibilityMode == value) {
					return;
				}
				this.RaiseAndSetIfChanged(ref _visibilityMode, value);
				this.RaisePropertyChanged(nameof(IsStripVisible));
				this.RaisePropertyChanged(nameof(IsHidden));
				this.RaisePropertyChanged(nameof(IsCollapsed));
				this.RaisePropertyChanged(nameof(IsExpanded));
				this.RaisePropertyChanged(nameof(ToolsButtonText));
				ConfigManager.Config.Oracle.DebugPanelVisibility = value;
				VisibilityModeChanged?.Invoke(this, EventArgs.Empty);
			}
		}

		public bool IsTextInputFocused {
			get => _isTextInputFocused;
			set => this.RaiseAndSetIfChanged(ref _isTextInputFocused, value);
		}

		public bool IsHidden => VisibilityMode == OracleDebugPanelVisibility.Hidden;
		public bool IsCollapsed => VisibilityMode == OracleDebugPanelVisibility.Collapsed;
		public bool IsExpanded => VisibilityMode == OracleDebugPanelVisibility.Expanded;
		public bool IsStripVisible => !IsHidden;
		public string ToolsButtonText => IsExpanded ? "Close tools" : "Tools";

		public OracleToolPage SelectedToolPage {
			get => _selectedToolPage;
			set {
				if(!Enum.IsDefined(value) || _selectedToolPage == value) {
					return;
				}
				this.RaiseAndSetIfChanged(ref _selectedToolPage, value);
				this.RaisePropertyChanged(nameof(IsTravelPage));
				this.RaisePropertyChanged(nameof(IsItemsPage));
				this.RaisePropertyChanged(nameof(IsProgressPage));
				this.RaisePropertyChanged(nameof(IsCapturePage));
				this.RaisePropertyChanged(nameof(IsMacrosPage));
				this.RaisePropertyChanged(nameof(IsLogPage));
				// These gates also refresh the values when a page becomes visible.
				Cheats.IsItemsOpen = IsItemsPage;
				Cheats.IsFlagsOpen = IsProgressPage;
			}
		}

		public bool IsTravelPage => SelectedToolPage == OracleToolPage.Travel;
		public bool IsItemsPage => SelectedToolPage == OracleToolPage.Items;
		public bool IsProgressPage => SelectedToolPage == OracleToolPage.Progress;
		public bool IsCapturePage => SelectedToolPage == OracleToolPage.Capture;
		public bool IsMacrosPage => SelectedToolPage == OracleToolPage.Macros;
		public bool IsLogPage => SelectedToolPage == OracleToolPage.Log;

		public bool IsCommandInFlight {
			get => _commandInFlight;
			private set => this.RaiseAndSetIfChanged(ref _commandInFlight, value);
		}

		// Status strip
		public string LocationText { get => _locationText; private set => this.RaiseAndSetIfChanged(ref _locationText, value); }
		public string LinkText { get => _linkText; private set => this.RaiseAndSetIfChanged(ref _linkText, value); }
		public string HealthText { get => _healthText; private set => this.RaiseAndSetIfChanged(ref _healthText, value); }
		public string FormText { get => _formText; private set => this.RaiseAndSetIfChanged(ref _formText, value); }
		public string FrameText { get => _frameText; private set => this.RaiseAndSetIfChanged(ref _frameText, value); }
		public string StatusLine { get => _statusLine; private set => this.RaiseAndSetIfChanged(ref _statusLine, value); }

		// Tools pane: state
		public string ModeText { get => _modeText; private set => this.RaiseAndSetIfChanged(ref _modeText, value); }
		public string WhereText { get => _whereText; private set => this.RaiseAndSetIfChanged(ref _whereText, value); }
		public string LinkDetailText { get => _linkDetailText; private set => this.RaiseAndSetIfChanged(ref _linkDetailText, value); }
		public string StatsText { get => _statsText; private set => this.RaiseAndSetIfChanged(ref _statsText, value); }
		public string StoryText { get => _storyText; private set => this.RaiseAndSetIfChanged(ref _storyText, value); }
		public string RomText { get => _romText; private set => this.RaiseAndSetIfChanged(ref _romText, value); }
		public string PauseButtonText { get => _pauseButtonText; private set => this.RaiseAndSetIfChanged(ref _pauseButtonText, value); }

		// Tools pane: warp
		public ObservableCollection<OracleWarpTargetViewModel> WarpTargets { get; }
		public OracleWarpTargetViewModel? SelectedWarpTarget { get => _selectedWarpTarget; set => this.RaiseAndSetIfChanged(ref _selectedWarpTarget, value); }
		public string WarpEntranceHex { get => _warpEntranceHex; set => this.RaiseAndSetIfChanged(ref _warpEntranceHex, value); }
		public string FallRoomHex { get => _fallRoomHex; set => this.RaiseAndSetIfChanged(ref _fallRoomHex, value); }
		public bool CanFallToRoom { get => _canFallToRoom; private set => this.RaiseAndSetIfChanged(ref _canFallToRoom, value); }

		// Tools pane: cheats & warps (warp search, checkpoints, items, flags)
		public OracleCheatsViewModel Cheats { get; }

		// Tools pane: capture
		public string CaptureLabel { get => _captureLabel; set => this.RaiseAndSetIfChanged(ref _captureLabel, value); }
		public string CaptureTags { get => _captureTags; set => this.RaiseAndSetIfChanged(ref _captureTags, value); }
		public string CaptureNotes { get => _captureNotes; set => this.RaiseAndSetIfChanged(ref _captureNotes, value); }
		public string LastCaptureText { get => _lastCaptureText; private set => this.RaiseAndSetIfChanged(ref _lastCaptureText, value); }

		// Tools pane: progress
		public ObservableCollection<OraclePresetOptionViewModel> Presets { get; }
		public OraclePresetOptionViewModel? SelectedPreset { get => _selectedPreset; set => this.RaiseAndSetIfChanged(ref _selectedPreset, value); }
		public ObservableCollection<OracleFlagOptionViewModel> FlagOptions { get; }
		public OracleFlagOptionViewModel? SelectedFlag { get => _selectedFlag; set => this.RaiseAndSetIfChanged(ref _selectedFlag, value); }

		// Tools pane: frames
		public string FrameAdvanceCount { get => _frameAdvanceCount; set => this.RaiseAndSetIfChanged(ref _frameAdvanceCount, value); }

		// Tools pane: macros + log
		public ObservableCollection<OracleMacroButtonViewModel> MacroButtons { get; }
		public bool HasMacros => MacroButtons.Count > 0;
		public string MacroStatus { get => _macroStatus; private set => this.RaiseAndSetIfChanged(ref _macroStatus, value); }
		public bool HasMacroError { get => _hasMacroError; private set => this.RaiseAndSetIfChanged(ref _hasMacroError, value); }
		public string SessionLog { get => _sessionLog; private set => this.RaiseAndSetIfChanged(ref _sessionLog, value); }

		public string MacroProfilePath {
			get => _macroProfilePath;
			set {
				this.RaiseAndSetIfChanged(ref _macroProfilePath, value);
				ConfigManager.Config.Oracle.DebugPanelMacroProfilePath = value?.Trim() ?? "";
			}
		}

		public void ToggleHiddenCollapsed()
		{
			VisibilityMode = IsHidden ? OracleDebugPanelVisibility.Collapsed : OracleDebugPanelVisibility.Hidden;
			if(!IsHidden) {
				RequestRefresh(true);
			}
		}

		public void ToggleCollapsedExpanded()
		{
			VisibilityMode = IsExpanded ? OracleDebugPanelVisibility.Collapsed : OracleDebugPanelVisibility.Expanded;
			RequestRefresh(true);
		}

		public void RequestRefresh(bool force = false)
		{
			//Reading RAM attaches Mesen's debugger, so a hidden strip does no work at all.
			if(IsHidden || (_refreshInFlight && !force)) {
				return;
			}
			Refresh();
		}

		public Task ExecuteWarpAsync()
		{
			if(SelectedWarpTarget == null) {
				StatusLine = "Pick a warp target.";
				return Task.CompletedTask;
			}
			OracleWarpTarget target = SelectedWarpTarget.Target;
			return RunActionAsync("warp", () => OracleDebugActions.Warp(target));
		}

		public Task ExecuteWarpEntranceAsync()
		{
			if(!TryParseHex(WarpEntranceHex, out int entrance)) {
				StatusLine = "Enter an entrance ID in hex, e.g. 76.";
				return Task.CompletedTask;
			}
			return RunActionAsync("warp", () => OracleDebugActions.WarpToEntrance(entrance));
		}

		public Task ExecuteFallToRoomAsync()
		{
			if(!TryParseHex(FallRoomHex, out int room)) {
				StatusLine = "Enter a room ID in hex, e.g. 77.";
				return Task.CompletedTask;
			}
			return RunActionAsync("fall", () => OracleDebugActions.FallToRoom(room));
		}

		public async Task ExecuteCaptureStateAsync()
		{
			string label = CaptureLabel.Trim();
			List<string> tags = CaptureTags.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
			string notes = CaptureNotes;
			bool ok = await RunActionAsync("capture", () => OracleDebugActions.CaptureState(label, tags, notes));
			LastCaptureText = StatusLine;
			if(ok) {
				CaptureLabel = "";
				CaptureNotes = "";
			}
		}

		public Task ExecuteApplyPresetAsync()
		{
			if(SelectedPreset == null) {
				StatusLine = "Pick a profile.";
				return Task.CompletedTask;
			}
			string id = SelectedPreset.Id;
			return RunActionAsync("profile", () => OracleDebugActions.ApplySaveProfile(id));
		}

		public Task ExecuteSetFlagAsync(bool on)
		{
			if(SelectedFlag == null) {
				StatusLine = "Pick a flag.";
				return Task.CompletedTask;
			}
			string key = SelectedFlag.Key;
			return RunActionAsync("flag", () => OracleDebugActions.SetFlag(key, on ? 1 : 0));
		}

		public Task ExecuteSyncSramAsync()
		{
			return RunActionAsync("sram", OracleDebugActions.SyncSram);
		}

		public Task ExecuteFrameAdvanceAsync()
		{
			if(!int.TryParse(FrameAdvanceCount.Trim(), out int frames)) {
				StatusLine = "Frame count must be a number.";
				return Task.CompletedTask;
			}
			return RunActionAsync("frames", () => OracleDebugActions.StepFrames(frames));
		}

		public void TogglePause()
		{
			if(!EmuApi.IsRunning()) {
				return;
			}
			if(EmuApi.IsPaused()) {
				EmuApi.Resume();
			} else {
				EmuApi.Pause();
			}
			RequestRefresh(true);
		}

		public void ClearSessionLog()
		{
			_sessionEntries.Clear();
			SessionLog = string.Empty;
		}

		public string GetSessionLogText()
		{
			return SessionLog;
		}

		public Task ReloadMacrosAsync()
		{
			try {
				MacroButtons.Clear();
				_macroStepsById.Clear();
				HasMacroError = false;

				string path = ExpandHome(MacroProfilePath);
				if(string.IsNullOrWhiteSpace(path)) {
					MacroStatus = "No macro profile configured.";
				} else if(!File.Exists(path)) {
					HasMacroError = true;
					MacroStatus = "Macro profile not found: " + path;
					AppendLog("macro", false, MacroStatus);
				} else {
					ParseAndValidateMacroProfile(File.ReadAllText(path));
					MacroStatus = $"Loaded {MacroButtons.Count} macro{(MacroButtons.Count == 1 ? "" : "s")}.";
					AppendLog("macro", true, MacroStatus);
				}
			} catch(Exception ex) {
				MacroButtons.Clear();
				_macroStepsById.Clear();
				HasMacroError = true;
				MacroStatus = "Macro profile invalid: " + ex.Message;
				AppendLog("macro", false, MacroStatus);
			}
			this.RaisePropertyChanged(nameof(HasMacros));
			return Task.CompletedTask;
		}

		public async Task RunMacroAsync(string macroId)
		{
			if(string.IsNullOrWhiteSpace(macroId) || !_macroStepsById.TryGetValue(macroId, out List<MacroStep>? steps)) {
				StatusLine = "Macro not found.";
				return;
			}

			AppendLog("macro", true, $"start {macroId}");
			for(int i = 0; i < steps.Count; i++) {
				MacroStep step = steps[i];
				bool ok = await RunActionAsync($"{macroId} {i + 1}/{steps.Count} {step.Action}", () => ExecuteMacroStep(step));
				if(!ok) {
					StatusLine = $"Macro '{macroId}' stopped at step {i + 1}: {StatusLine}";
					return;
				}
			}
			StatusLine = $"Macro '{macroId}' done ({steps.Count} step{(steps.Count == 1 ? "" : "s")}).";
		}

		private void ReloadPresets()
		{
			Presets.Clear();
			foreach(OracleSaveProfile profile in OracleDebugActions.GetSaveProfiles()) {
				Presets.Add(new OraclePresetOptionViewModel() { Id = profile.Id, Label = profile.Label, Description = profile.Description });
			}
			SelectedPreset = Presets.FirstOrDefault();
		}

		private void Refresh()
		{
			_refreshInFlight = true;
			try {
				bool isRunning = EmuApi.IsRunning();
				RomInfo rom = EmuApi.GetRomInfo();
				string romName = rom.GetRomName();
				RomText = string.IsNullOrWhiteSpace(romName) ? "-" : romName;

				if(!isRunning) {
					LocationText = "No game";
					LinkText = HealthText = FormText = FrameText = "";
					ModeText = WhereText = LinkDetailText = StatsText = StoryText = "-";
					CanFallToRoom = false;
					PauseButtonText = "Pause";
					return;
				}

				bool isPaused = EmuApi.IsPaused();
				TimingInfo timing = EmuApi.GetTimingInfo(rom.ConsoleType.GetMainCpuType());
				FrameText = $"F {timing.FrameCount:N0}{(isPaused ? " ‖" : "")}";
				PauseButtonText = isPaused ? "Resume" : "Pause";

				OraclePanelRuntimeSummary? s = OraclePanelBridge.FetchRuntimeSummaryAsync().Result;
				if(s == null) {
					LocationText = "State unavailable";
					return;
				}

				string arrow = s.LinkDirection switch { "Up" => "↑", "Down" => "↓", "Left" => "←", "Right" => "→", _ => "?" };
				LocationText = s.Indoors ? $"Room ${s.DungeonRoom:X3}" : $"OW ${s.AreaId:X2} {s.AreaName}";
				LinkText = $"{s.LinkX},{s.LinkY} {arrow}";
				HealthText = $"♥ {s.Health / 8.0:0.#}/{s.MaxHealth / 8.0:0.#}";
				FormText = s.LinkFormName;

				ModeText = $"{s.ModeName} · sub ${s.Submode:X2}";
				WhereText = s.Indoors
					? $"Room ${s.DungeonRoom:X3} (layout ${s.RoomLayout:X2})"
					: $"{s.AreaName} (area ${s.AreaId:X2})";
				LinkDetailText = $"{s.LinkX},{s.LinkY} {arrow} · state ${s.LinkState:X2} · {s.LinkFormName}";
				StatsText = $"HP {s.Health}/{s.MaxHealth} · MP {s.Magic} · Rupees {s.Rupees}";
				StoryText = $"GS {s.GameState} · Crys ${s.Crystals:X2} · Pend ${s.Pendants:X2}";
				CanFallToRoom = s.Indoors;
				if(IsExpanded) {
					Cheats.RefreshValues();
				}
			} catch(Exception ex) {
				LocationText = "Refresh failed";
				StatusLine = ex.Message;
			} finally {
				_refreshInFlight = false;
			}
		}

		private async Task<bool> RunActionAsync(string source, Func<OracleActionResult> action)
		{
			if(IsCommandInFlight) {
				StatusLine = "Another action is still running.";
				return false;
			}

			IsCommandInFlight = true;
			try {
				OracleActionResult result = await Task.Run(action);
				StatusLine = result.Message;
				AppendLog(source, result.Success, result.Message);
				return result.Success;
			} catch(Exception ex) {
				StatusLine = $"{source} failed: {ex.Message}";
				AppendLog(source, false, ex.Message);
				return false;
			} finally {
				IsCommandInFlight = false;
				RequestRefresh(true);
			}
		}

		private static OracleActionResult ExecuteMacroStep(MacroStep step)
		{
			string Arg(string name, string fallback = "")
			{
				return step.Args != null && step.Args.TryGetValue(name, out string? value) ? value : fallback;
			}

			switch(step.Action) {
				case "apply_profile":
					return OracleDebugActions.ApplySaveProfile(Arg("profile"));
				case "setflag":
					return TryParseFlagValue(Arg("value"), out int flagValue)
						? OracleDebugActions.SetFlag(Arg("flag"), flagValue)
						: OracleActionResult.Fail($"Invalid flag value '{Arg("value")}'.");
				case "sync_sram":
					return OracleDebugActions.SyncSram();
				case "frame":
					return int.TryParse(Arg("frames", "1"), out int frames)
						? OracleDebugActions.StepFrames(frames)
						: OracleActionResult.Fail($"Invalid frame count '{Arg("frames")}'.");
				case "warp":
					return TryParseHex(Arg("entrance"), out int entrance)
						? OracleDebugActions.WarpToEntrance(entrance)
						: OracleActionResult.Fail($"Invalid entrance '{Arg("entrance")}'.");
				case "fall":
					return TryParseHex(Arg("room"), out int room)
						? OracleDebugActions.FallToRoom(room)
						: OracleActionResult.Fail($"Invalid room '{Arg("room")}'.");
				default:
					return OracleActionResult.Fail($"Unsupported action '{step.Action}'.");
			}
		}

		private void AppendLog(string source, bool success, string details)
		{
			string sanitized = (details ?? string.Empty).Replace("\r", " ").Replace("\n", " ").Trim();
			if(sanitized.Length > 220) {
				sanitized = sanitized.Substring(0, 220) + "...";
			}
			_sessionEntries.Add($"[{DateTime.Now:HH:mm:ss}] {(success ? "OK " : "ERR")} {source}: {sanitized}");
			if(_sessionEntries.Count > MaxSessionEntries) {
				_sessionEntries.RemoveRange(0, _sessionEntries.Count - MaxSessionEntries);
			}
			SessionLog = string.Join(Environment.NewLine, _sessionEntries);
		}

		private static bool TryParseHex(string? text, out int value)
		{
			string trimmed = (text ?? "").Trim().TrimStart('$');
			if(trimmed.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) {
				trimmed = trimmed.Substring(2);
			}
			return int.TryParse(trimmed, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out value);
		}

		private static bool TryParseFlagValue(string text, out int value)
		{
			switch(text.Trim().ToLowerInvariant()) {
				case "true": case "yes": case "on": value = 1; return true;
				case "false": case "no": case "off": value = 0; return true;
			}
			if(text.Trim().StartsWith("0x", StringComparison.OrdinalIgnoreCase) || text.Trim().StartsWith("$")) {
				return TryParseHex(text, out value);
			}
			return int.TryParse(text.Trim(), out value);
		}

		private void ParseAndValidateMacroProfile(string json)
		{
			using JsonDocument doc = JsonDocument.Parse(json);
			JsonElement root = doc.RootElement;
			if(root.ValueKind != JsonValueKind.Object) {
				throw new InvalidDataException("Root must be an object.");
			}

			HashSet<string> rootAllowed = new(StringComparer.Ordinal) { "macros", "version", "name" };
			foreach(JsonProperty property in root.EnumerateObject()) {
				if(!rootAllowed.Contains(property.Name)) {
					throw new InvalidDataException($"Unexpected root key '{property.Name}'.");
				}
			}

			if(!root.TryGetProperty("macros", out JsonElement macrosElement) || macrosElement.ValueKind != JsonValueKind.Array) {
				throw new InvalidDataException("Missing required 'macros' array.");
			}

			foreach(JsonElement macroElement in macrosElement.EnumerateArray()) {
				ParsedMacro macro = ParseMacro(macroElement);
				MacroButtons.Add(new OracleMacroButtonViewModel {
					Id = macro.Id,
					Label = macro.Label,
					Shortcut = macro.Shortcut,
					IsPrimary = macro.Primary
				});
				_macroStepsById[macro.Id] = macro.Steps;
			}
		}

		private static ParsedMacro ParseMacro(JsonElement macroElement)
		{
			if(macroElement.ValueKind != JsonValueKind.Object) {
				throw new InvalidDataException("Each macro must be an object.");
			}

			HashSet<string> allowed = new(StringComparer.Ordinal) { "id", "label", "shortcut", "primary", "steps" };
			foreach(JsonProperty property in macroElement.EnumerateObject()) {
				if(!allowed.Contains(property.Name)) {
					throw new InvalidDataException($"Unexpected macro key '{property.Name}'.");
				}
			}

			string id = GetRequiredString(macroElement, "id");
			string label = GetRequiredString(macroElement, "label");
			string shortcut = GetOptionalString(macroElement, "shortcut");
			bool primary = GetOptionalBool(macroElement, "primary");

			if(!macroElement.TryGetProperty("steps", out JsonElement stepsElement) || stepsElement.ValueKind != JsonValueKind.Array) {
				throw new InvalidDataException($"Macro '{id}' requires a 'steps' array.");
			}
			List<MacroStep> steps = new();
			foreach(JsonElement stepElement in stepsElement.EnumerateArray()) {
				steps.Add(ParseStep(id, stepElement));
			}
			if(steps.Count == 0) {
				throw new InvalidDataException($"Macro '{id}' has no steps.");
			}

			return new ParsedMacro(id, label, shortcut, primary, steps);
		}

		private static MacroStep ParseStep(string macroId, JsonElement stepElement)
		{
			if(stepElement.ValueKind != JsonValueKind.Object) {
				throw new InvalidDataException($"Macro '{macroId}' has a non-object step.");
			}

			HashSet<string> allowed = new(StringComparer.Ordinal) { "action", "args" };
			foreach(JsonProperty property in stepElement.EnumerateObject()) {
				if(!allowed.Contains(property.Name)) {
					throw new InvalidDataException($"Macro '{macroId}' has unexpected step key '{property.Name}'.");
				}
			}

			string action = GetRequiredString(stepElement, "action");
			if(action == "fly") {
				throw new InvalidDataException($"Macro '{macroId}' uses 'fly', which was removed. Use 'warp' with an 'entrance' arg.");
			}
			if(!AllowedMacroActions.Contains(action)) {
				throw new InvalidDataException($"Macro '{macroId}' uses unsupported action '{action}'.");
			}

			Dictionary<string, string>? args = null;
			if(stepElement.TryGetProperty("args", out JsonElement argsElement)) {
				if(argsElement.ValueKind != JsonValueKind.Object) {
					throw new InvalidDataException($"Macro '{macroId}' step '{action}' has invalid 'args' value.");
				}
				args = new Dictionary<string, string>(StringComparer.Ordinal);
				foreach(JsonProperty property in argsElement.EnumerateObject()) {
					if(property.Value.ValueKind != JsonValueKind.String) {
						throw new InvalidDataException($"Macro '{macroId}' step '{action}' arg '{property.Name}' must be a string.");
					}
					args[property.Name] = property.Value.GetString() ?? string.Empty;
				}
			}

			return new MacroStep(action, args);
		}

		private static string GetRequiredString(JsonElement element, string property)
		{
			if(!element.TryGetProperty(property, out JsonElement value) || value.ValueKind != JsonValueKind.String) {
				throw new InvalidDataException($"Missing required string '{property}'.");
			}
			string result = value.GetString() ?? string.Empty;
			if(string.IsNullOrWhiteSpace(result)) {
				throw new InvalidDataException($"Property '{property}' cannot be empty.");
			}
			return result.Trim();
		}

		private static string GetOptionalString(JsonElement element, string property)
		{
			if(!element.TryGetProperty(property, out JsonElement value)) {
				return string.Empty;
			}
			if(value.ValueKind != JsonValueKind.String) {
				throw new InvalidDataException($"Property '{property}' must be a string.");
			}
			return value.GetString()?.Trim() ?? string.Empty;
		}

		private static bool GetOptionalBool(JsonElement element, string property)
		{
			if(!element.TryGetProperty(property, out JsonElement value)) {
				return false;
			}
			if(value.ValueKind != JsonValueKind.True && value.ValueKind != JsonValueKind.False) {
				throw new InvalidDataException($"Property '{property}' must be a boolean.");
			}
			return value.GetBoolean();
		}

		private static string ExpandHome(string? path)
		{
			if(string.IsNullOrWhiteSpace(path)) {
				return string.Empty;
			}
			string trimmed = path.Trim();
			if(trimmed.StartsWith("~", StringComparison.Ordinal)) {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return Path.Combine(home, trimmed.TrimStart('~').TrimStart(Path.DirectorySeparatorChar));
			}
			return trimmed;
		}

		private static readonly HashSet<string> AllowedMacroActions = new(StringComparer.Ordinal) {
			"apply_profile",
			"setflag",
			"sync_sram",
			"frame",
			"warp",
			"fall"
		};

		private sealed record class MacroStep(string Action, IReadOnlyDictionary<string, string>? Args);
		private sealed record class ParsedMacro(string Id, string Label, string Shortcut, bool Primary, List<MacroStep> Steps);
	}
}
