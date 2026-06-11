using Mesen.Config;
using Mesen.Interop;
using Mesen.Utilities;
using ReactiveUI;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public class OracleMacroButtonViewModel
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Shortcut { get; init; } = string.Empty;
		public bool IsPrimary { get; init; }
		public string DisplayLabel {
			get {
				string baseLabel = IsPrimary ? "[P] " + Label : Label;
				return string.IsNullOrWhiteSpace(Shortcut) ? baseLabel : $"{baseLabel} ({Shortcut})";
			}
		}
	}

	public class OracleDebugPanelViewModel : ViewModelBase
	{
		private OracleDebugPanelVisibility _visibilityMode;
		private bool _isTextInputFocused;
		private bool _refreshInFlight;
		private bool _commandInFlight;
		private readonly List<string> _sessionEntries = new();
		private readonly Dictionary<string, List<MacroStep>> _macroStepsById = new(StringComparer.OrdinalIgnoreCase);
		private const int MaxSessionEntries = 200;

		private string _collapsedSummary = "Oracle panel ready";
		private string _romSummary = "ROM: not loaded";
		private string _locationSummary = "Location: unavailable";
		private string _modeSummary = "Mode: unavailable";
		private string _playerSummary = "Link: unavailable";
		private string _progressSummary = "Progress: unavailable";
		private string _emulationSummary = "Emulation: stopped";
		private string _timingSummary = "Frame: n/a";
		private string _gatewaySummary = "Gateway: unknown";
		private string _socketSummary = "Socket: unknown";
		private string _yazeSummary = "Yaze: unknown";
		private string _lastRefreshSummary = "Last update: never";
		private string _statusLine = "Ready.";
		private string _sessionLog = "";
		private string _captureLabel = "";
		private string _captureTags = "";
		private bool _captureVerifyNow;
		private string _captureVerifier = "";
		private string _captureTrustedSeedTask = "";
		private string _saveDataProfile = "";
		private string _setFlagName = "";
		private string _setFlagValue = "";
		private string _flyDestination = "";
		private string _frameAdvanceCount = "1";
		private string _macroProfilePath = "";
		private string _macroStatus = "No macro profile loaded.";
		private bool _hasMacroError;

		public OracleDebugPanelViewModel()
		{
			_visibilityMode = ConfigManager.Config.Oracle.DebugPanelVisibility;
			if(!Enum.IsDefined(_visibilityMode)) {
				_visibilityMode = OracleDebugPanelVisibility.Collapsed;
			}

			MacroProfilePath = ExpandHome(ConfigManager.Config.Oracle.DebugPanelMacroProfilePath);
			MacroButtons = new ObservableCollection<OracleMacroButtonViewModel>();
			if(!string.IsNullOrWhiteSpace(MacroProfilePath)) {
				_ = ReloadMacrosAsync();
			}
		}

		public OracleDebugPanelVisibility VisibilityMode {
			get => _visibilityMode;
			private set {
				this.RaiseAndSetIfChanged(ref _visibilityMode, value);
				this.RaisePropertyChanged(nameof(IsPanelVisible));
				this.RaisePropertyChanged(nameof(IsHidden));
				this.RaisePropertyChanged(nameof(IsCollapsed));
				this.RaisePropertyChanged(nameof(IsExpanded));
				this.RaisePropertyChanged(nameof(DetailsButtonText));
				this.RaisePropertyChanged(nameof(VisibilityButtonText));
				PersistVisibilityState(value);
			}
		}

		public bool IsTextInputFocused {
			get => _isTextInputFocused;
			set => this.RaiseAndSetIfChanged(ref _isTextInputFocused, value);
		}

		public bool IsPanelVisible => true;
		public bool IsHidden => VisibilityMode == OracleDebugPanelVisibility.Hidden;
		public bool IsCollapsed => VisibilityMode == OracleDebugPanelVisibility.Collapsed;
		public bool IsExpanded => VisibilityMode == OracleDebugPanelVisibility.Expanded;
		public string DetailsButtonText => VisibilityMode == OracleDebugPanelVisibility.Expanded ? "Collapse" : "Expand";
		public string VisibilityButtonText => VisibilityMode == OracleDebugPanelVisibility.Hidden ? "Show" : "Hide";
		public bool IsCommandInFlight {
			get => _commandInFlight;
			private set => this.RaiseAndSetIfChanged(ref _commandInFlight, value);
		}

		public string CollapsedSummary {
			get => _collapsedSummary;
			private set => this.RaiseAndSetIfChanged(ref _collapsedSummary, value);
		}

		public string RomSummary {
			get => _romSummary;
			private set => this.RaiseAndSetIfChanged(ref _romSummary, value);
		}

		public string LocationSummary {
			get => _locationSummary;
			private set => this.RaiseAndSetIfChanged(ref _locationSummary, value);
		}

		public string ModeSummary {
			get => _modeSummary;
			private set => this.RaiseAndSetIfChanged(ref _modeSummary, value);
		}

		public string PlayerSummary {
			get => _playerSummary;
			private set => this.RaiseAndSetIfChanged(ref _playerSummary, value);
		}

		public string ProgressSummary {
			get => _progressSummary;
			private set => this.RaiseAndSetIfChanged(ref _progressSummary, value);
		}

		public string EmulationSummary {
			get => _emulationSummary;
			private set => this.RaiseAndSetIfChanged(ref _emulationSummary, value);
		}

		public string TimingSummary {
			get => _timingSummary;
			private set => this.RaiseAndSetIfChanged(ref _timingSummary, value);
		}

		public string GatewaySummary {
			get => _gatewaySummary;
			private set => this.RaiseAndSetIfChanged(ref _gatewaySummary, value);
		}

		public string SocketSummary {
			get => _socketSummary;
			private set => this.RaiseAndSetIfChanged(ref _socketSummary, value);
		}

		public string YazeSummary {
			get => _yazeSummary;
			private set => this.RaiseAndSetIfChanged(ref _yazeSummary, value);
		}

		public string LastRefreshSummary {
			get => _lastRefreshSummary;
			private set => this.RaiseAndSetIfChanged(ref _lastRefreshSummary, value);
		}

		public string StatusLine {
			get => _statusLine;
			private set => this.RaiseAndSetIfChanged(ref _statusLine, value);
		}

		public string SessionLog {
			get => _sessionLog;
			private set => this.RaiseAndSetIfChanged(ref _sessionLog, value);
		}

		public string CaptureLabel {
			get => _captureLabel;
			set => this.RaiseAndSetIfChanged(ref _captureLabel, value);
		}

		public string CaptureTags {
			get => _captureTags;
			set => this.RaiseAndSetIfChanged(ref _captureTags, value);
		}

		public bool CaptureVerifyNow {
			get => _captureVerifyNow;
			set => this.RaiseAndSetIfChanged(ref _captureVerifyNow, value);
		}

		public string CaptureVerifier {
			get => _captureVerifier;
			set => this.RaiseAndSetIfChanged(ref _captureVerifier, value);
		}

		public string CaptureTrustedSeedTask {
			get => _captureTrustedSeedTask;
			set => this.RaiseAndSetIfChanged(ref _captureTrustedSeedTask, value);
		}

		public string SaveDataProfile {
			get => _saveDataProfile;
			set => this.RaiseAndSetIfChanged(ref _saveDataProfile, value);
		}

		public string SetFlagName {
			get => _setFlagName;
			set => this.RaiseAndSetIfChanged(ref _setFlagName, value);
		}

		public string SetFlagValue {
			get => _setFlagValue;
			set => this.RaiseAndSetIfChanged(ref _setFlagValue, value);
		}

		public string FlyDestination {
			get => _flyDestination;
			set => this.RaiseAndSetIfChanged(ref _flyDestination, value);
		}

		public string FrameAdvanceCount {
			get => _frameAdvanceCount;
			set => this.RaiseAndSetIfChanged(ref _frameAdvanceCount, value);
		}

		public string MacroProfilePath {
			get => _macroProfilePath;
			set {
				this.RaiseAndSetIfChanged(ref _macroProfilePath, value);
				ConfigManager.Config.Oracle.DebugPanelMacroProfilePath = value?.Trim() ?? "";
			}
		}

		public string MacroStatus {
			get => _macroStatus;
			private set => this.RaiseAndSetIfChanged(ref _macroStatus, value);
		}

		public bool HasMacroError {
			get => _hasMacroError;
			private set => this.RaiseAndSetIfChanged(ref _hasMacroError, value);
		}

		public ObservableCollection<OracleMacroButtonViewModel> MacroButtons { get; }

		public void ToggleHiddenCollapsed()
		{
			if(VisibilityMode == OracleDebugPanelVisibility.Hidden) {
				VisibilityMode = OracleDebugPanelVisibility.Collapsed;
				RequestRefresh(true);
			} else {
				VisibilityMode = OracleDebugPanelVisibility.Hidden;
			}
		}

		public void ToggleCollapsedExpanded()
		{
			if(VisibilityMode == OracleDebugPanelVisibility.Hidden) {
				VisibilityMode = OracleDebugPanelVisibility.Expanded;
				RequestRefresh(true);
				return;
			}

			VisibilityMode = VisibilityMode == OracleDebugPanelVisibility.Collapsed
				? OracleDebugPanelVisibility.Expanded
				: OracleDebugPanelVisibility.Collapsed;
			if(VisibilityMode == OracleDebugPanelVisibility.Expanded) {
				RequestRefresh(true);
			}
		}

		public void RequestRefresh(bool force = false)
		{
			if(_refreshInFlight && !force) {
				return;
			}

			_ = RefreshAsync();
		}

		public async Task ExecuteApplyProfileAsync()
		{
			if(string.IsNullOrWhiteSpace(SaveDataProfile)) {
				StatusLine = "Profile is required.";
				return;
			}
			await ExecuteGatewayActionAsync("apply_profile", new Dictionary<string, string> {
				["profile"] = SaveDataProfile.Trim()
			}, "Apply save-data profile");
		}

		public async Task ExecuteSetFlagAsync()
		{
			if(string.IsNullOrWhiteSpace(SetFlagName) || string.IsNullOrWhiteSpace(SetFlagValue)) {
				StatusLine = "Flag and value are required.";
				return;
			}
			await ExecuteGatewayActionAsync("setflag", new Dictionary<string, string> {
				["flag"] = SetFlagName.Trim(),
				["value"] = SetFlagValue.Trim()
			}, "Set flag");
		}

		public async Task ExecuteFlyAsync()
		{
			if(string.IsNullOrWhiteSpace(FlyDestination)) {
				StatusLine = "Destination is required.";
				return;
			}
			await ExecuteGatewayActionAsync("fly", new Dictionary<string, string> {
				["destination"] = FlyDestination.Trim()
			}, "Fly");
		}

		public async Task ExecuteSyncSramAsync()
		{
			await ExecuteGatewayActionAsync("sync_sram", null, "Sync WRAMSAVE -> SRAM");
		}

		public async Task ExecuteFrameAdvanceAsync()
		{
			string frames = string.IsNullOrWhiteSpace(FrameAdvanceCount) ? "1" : FrameAdvanceCount.Trim();
			await ExecuteGatewayActionAsync("frame", new Dictionary<string, string> {
				["frames"] = frames
			}, "Advance frame");
		}

		public async Task ExecuteCaptureStateAsync()
		{
			Dictionary<string, string> args = new();
			if(!string.IsNullOrWhiteSpace(CaptureLabel)) {
				args["label"] = CaptureLabel.Trim();
			}
			if(!string.IsNullOrWhiteSpace(CaptureTags)) {
				args["tags"] = CaptureTags.Trim();
			}
			if(CaptureVerifyNow) {
				args["verify_now"] = "true";
			}
			if(!string.IsNullOrWhiteSpace(CaptureVerifier)) {
				args["verifier"] = CaptureVerifier.Trim();
			}
			if(!string.IsNullOrWhiteSpace(CaptureTrustedSeedTask)) {
				args["trusted_seed_task"] = CaptureTrustedSeedTask.Trim();
			}
			await ExecuteGatewayActionAsync("capture_state", args.Count == 0 ? null : args, "Capture state");
		}

		public void ClearSessionLog()
		{
			_sessionEntries.Clear();
			SessionLog = string.Empty;
			StatusLine = "Session log cleared.";
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
					return Task.CompletedTask;
				}
				if(!File.Exists(path)) {
					HasMacroError = true;
					MacroStatus = "Macro profile not found: " + path;
					AppendLog("macro", false, MacroStatus);
					return Task.CompletedTask;
				}

				string json = File.ReadAllText(path);
				ParseAndValidateMacroProfile(json);
				MacroStatus = $"Loaded {MacroButtons.Count} macro button{(MacroButtons.Count == 1 ? "" : "s")}.";
				AppendLog("macro", true, MacroStatus);
			} catch(Exception ex) {
				MacroButtons.Clear();
				_macroStepsById.Clear();
				HasMacroError = true;
				MacroStatus = "Macro profile invalid: " + ex.Message;
				AppendLog("macro", false, MacroStatus);
			}
			return Task.CompletedTask;
		}

		public async Task RunMacroAsync(string macroId)
		{
			if(string.IsNullOrWhiteSpace(macroId) || !_macroStepsById.TryGetValue(macroId, out List<MacroStep>? steps)) {
				StatusLine = "Macro not found.";
				return;
			}

			if(steps.Count == 0) {
				StatusLine = "Macro has no steps.";
				return;
			}

			StatusLine = $"Running macro '{macroId}'...";
			AppendLog("macro", true, $"start {macroId}");
			for(int i = 0; i < steps.Count; i++) {
				MacroStep step = steps[i];
				string description = $"{macroId} step {i + 1}/{steps.Count}: {step.Action}";
				bool ok = await ExecuteGatewayActionAsync(step.Action, step.Args, description, true);
				if(!ok) {
					StatusLine = $"Macro '{macroId}' failed at step {i + 1}.";
					AppendLog("macro", false, $"failed {macroId} at step {i + 1}");
					return;
				}
			}
			StatusLine = $"Macro '{macroId}' completed.";
			AppendLog("macro", true, $"completed {macroId}");
		}

		private async Task RefreshAsync()
		{
			if(_refreshInFlight) {
				return;
			}
			_refreshInFlight = true;

			try {
				bool isRunning = EmuApi.IsRunning();
				bool isPaused = isRunning && EmuApi.IsPaused();
				RomInfo rom = EmuApi.GetRomInfo();
				CpuType cpu = rom.ConsoleType.GetMainCpuType();
				TimingInfo timing = isRunning ? EmuApi.GetTimingInfo(cpu) : default;

				Task<OracleStatusSnapshot> statusTask = OracleAgentStatus.FetchAsync();
				Task<OraclePanelRuntimeSummary?> runtimeTask = isRunning
					? OraclePanelBridge.FetchRuntimeSummaryAsync()
					: Task.FromResult<OraclePanelRuntimeSummary?>(null);

				OracleStatusSnapshot status;
				try {
					status = await statusTask;
				} catch(Exception ex) {
					status = OracleStatusSnapshot.Empty() with {
						GatewayError = ex.Message,
						Timestamp = DateTimeOffset.Now
					};
				}

				OraclePanelRuntimeSummary? runtime = null;
				try {
					runtime = await runtimeTask;
				} catch {
					runtime = null;
				}

				UpdateFields(rom, isRunning, isPaused, timing, status, runtime);
			} catch(Exception ex) {
				LastRefreshSummary = "Last update failed: " + ex.Message;
			} finally {
				_refreshInFlight = false;
			}
		}

		private void UpdateFields(RomInfo rom, bool isRunning, bool isPaused, TimingInfo timing, OracleStatusSnapshot status, OraclePanelRuntimeSummary? runtime)
		{
			string romName = rom.GetRomName();
			RomSummary = string.IsNullOrWhiteSpace(romName)
				? "ROM: not loaded"
				: $"ROM: {romName} ({rom.ConsoleType})";

			if(!isRunning) {
				EmulationSummary = "Emulation: stopped";
				TimingSummary = "Frame: n/a";
				LocationSummary = "Location: no ROM loaded";
				ModeSummary = "Mode: unavailable";
				PlayerSummary = "Link: unavailable";
				ProgressSummary = "Progress: unavailable";
			} else if(isPaused) {
				EmulationSummary = "Emulation: paused";
				TimingSummary = $"Frame: {timing.FrameCount:N0} | FPS: {timing.Fps:0.00}";
			} else {
				EmulationSummary = "Emulation: running";
				TimingSummary = $"Frame: {timing.FrameCount:N0} | FPS: {timing.Fps:0.00}";
			}

			if(isRunning && runtime != null) {
				LocationSummary = $"Area: {runtime.AreaName} (0x{runtime.AreaId:X2}) | Map: {runtime.MapName} | Room: {runtime.RoomName} ({runtime.RoomLabel})";
				ModeSummary = $"Mode: {runtime.ModeName} | Submode: 0x{runtime.Submode:X2} | Form: {runtime.LinkFormName} | State: 0x{runtime.LinkState:X2}";
				PlayerSummary = $"Link: {runtime.LinkX}, {runtime.LinkY} facing {runtime.LinkDirection} | HP {runtime.Health}/{runtime.MaxHealth} | Magic {runtime.Magic} | Rupees {runtime.Rupees}";
				ProgressSummary = $"Progress: GameState {runtime.GameState} | Crystals 0x{runtime.Crystals:X2} | Pendants 0x{runtime.Pendants:X2}";
			} else if(isRunning) {
				LocationSummary = "Location: unavailable";
				ModeSummary = "Mode: unavailable";
				PlayerSummary = "Link: unavailable";
				ProgressSummary = "Progress: unavailable";
			}

			GatewaySummary = status.GatewayOnline
				? (status.Mesen2Connected == true ? "Gateway: online + attached" : "Gateway: online")
				: "Gateway: offline";
			SocketSummary = status.SocketFound
				? "Socket: " + (string.IsNullOrWhiteSpace(status.SocketPath) ? "found" : status.SocketPath)
				: "Socket: missing";

			if(status.YazeRunning.HasValue) {
				YazeSummary = status.YazeRunning.Value ? "Yaze: running" : "Yaze: stopped";
			} else {
				YazeSummary = "Yaze: unknown";
			}

			string updatedAt = status.Timestamp == DateTimeOffset.MinValue
				? DateTimeOffset.Now.ToString("HH:mm:ss")
				: status.Timestamp.ToLocalTime().ToString("HH:mm:ss");
			LastRefreshSummary = string.IsNullOrWhiteSpace(status.GatewayError)
				? "Last update: " + updatedAt
				: $"Last update: {updatedAt} (gateway: {status.GatewayError})";

			string frameSummary = isRunning
				? $"Frame {timing.FrameCount:N0} @ {timing.Fps:0.00} FPS"
				: "Frame n/a";
			string romSummary = string.IsNullOrWhiteSpace(romName) ? "No ROM loaded" : romName;
			string locationSummary = LocationSummary
				.Replace("Area: ", string.Empty)
				.Replace("Location: ", string.Empty);
			string socketSummary = status.SocketFound
				? (string.IsNullOrWhiteSpace(status.SocketPath) ? "socket ready" : Path.GetFileName(status.SocketPath))
				: "no socket";
			string yazeSummary = status.YazeRunning == true ? "Yaze on" : status.YazeRunning == false ? "Yaze off" : "Yaze ?";

			CollapsedSummary = $"{romSummary} | {locationSummary} | {EmulationSummary.Replace("Emulation: ", string.Empty)} | {frameSummary}";
		}

		private async Task<bool> ExecuteGatewayActionAsync(string action, IReadOnlyDictionary<string, string>? args, string description, bool suppressStatusOverride = false)
		{
			if(IsCommandInFlight) {
				if(!suppressStatusOverride) {
					StatusLine = "Another command is still running.";
				}
				return false;
			}

			if(!EmuApi.IsRunning() && action != "health") {
				if(!suppressStatusOverride) {
					StatusLine = "No ROM running.";
				}
				AppendLog(description, false, "No ROM running.");
				return false;
			}

			IsCommandInFlight = true;
			try {
				string? output = await AgentLauncher.RunGatewayActionWithOutputAsync(action, args);
				if(output == null) {
					if(!suppressStatusOverride) {
						StatusLine = description + " failed.";
					}
					AppendLog(description, false, "No output returned.");
					return false;
				}

				bool success = !output.Contains("STDERR:", StringComparison.OrdinalIgnoreCase);
				if(!suppressStatusOverride) {
					StatusLine = success ? (description + " succeeded.") : (description + " failed.");
				}
				AppendLog(description, success, output);
				if(action == "sync_sram" || action == "setflag" || action == "fly" || action == "apply_profile" || action == "capture_state" || action == "frame") {
					RequestRefresh(true);
				}
				return success;
			} catch(Exception ex) {
				if(!suppressStatusOverride) {
					StatusLine = description + " failed: " + ex.Message;
				}
				AppendLog(description, false, ex.Message);
				return false;
			} finally {
				IsCommandInFlight = false;
			}
		}

		private void AppendLog(string source, bool success, string details)
		{
			string sanitized = (details ?? string.Empty).Replace("\r", " ").Replace("\n", " ").Trim();
			if(sanitized.Length > 220) {
				sanitized = sanitized.Substring(0, 220) + "...";
			}
			string line = $"[{DateTime.Now:HH:mm:ss}] {(success ? "OK" : "ERR")} {source}: {sanitized}";
			_sessionEntries.Add(line);
			if(_sessionEntries.Count > MaxSessionEntries) {
				_sessionEntries.RemoveRange(0, _sessionEntries.Count - MaxSessionEntries);
			}
			SessionLog = string.Join(Environment.NewLine, _sessionEntries);
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

		private static void PersistVisibilityState(OracleDebugPanelVisibility visibility)
		{
			ConfigManager.Config.Oracle.DebugPanelVisibility = visibility;
		}

		private static readonly HashSet<string> AllowedMacroActions = new(StringComparer.Ordinal) {
			"apply_profile",
			"setflag",
			"sync_sram",
			"fly",
			"frame"
		};

		private sealed record class MacroStep(string Action, IReadOnlyDictionary<string, string>? Args);
		private sealed record class ParsedMacro(string Id, string Label, string Shortcut, bool Primary, List<MacroStep> Steps);
	}
}
