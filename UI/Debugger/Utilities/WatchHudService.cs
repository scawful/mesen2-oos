using Mesen.Config;
using Mesen.Debugger;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Mesen.Debugger.Utilities
{
	public static class WatchHudService
	{
		private static readonly object _updateLock = new();
		private static readonly object _watchHookLock = new();
		private static bool _watchHooksInstalled = false;
		private static DateTime _lastUpdate = DateTime.MinValue;
		private static Dictionary<CpuType, List<WatchValueInfo>> _previousValuesByCpu = new();
		private static string _lastText = "";
		private static string _lastDataJson = "";
		private static bool _cleared = true;
		private static bool _dataCleared = true;

		private sealed class WatchEntryDto
		{
			[JsonPropertyName("expression")] public string Expression { get; set; } = "";
			[JsonPropertyName("value")] public string Value { get; set; } = "";
			[JsonPropertyName("numeric")] public long NumericValue { get; set; }
			[JsonPropertyName("changed")] public bool IsChanged { get; set; }
		}

		private static readonly JsonSerializerOptions _watchJsonOptions = new()
		{
			DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
		};

		public static void ProcessNotification(NotificationEventArgs e)
		{
			bool showHud = ConfigManager.Config.Debug.Debugger.ShowWatchHud;
			bool hasWatchEntries = HasAnyWatchEntries();
			if(!showHud && !hasWatchEntries) {
				Clear();
				return;
			}
			if(showHud || hasWatchEntries) {
				EnsureWatchHooks();
			}

			switch(e.NotificationType) {
				case ConsoleNotificationType.BeforeGameLoad:
				case ConsoleNotificationType.BeforeGameUnload:
				case ConsoleNotificationType.GameLoaded:
					_previousValuesByCpu = new();
					UpdateHud(force: true);
					break;
				case ConsoleNotificationType.GameLoadFailed:
				case ConsoleNotificationType.StateLoaded:
				case ConsoleNotificationType.GamePaused:
				case ConsoleNotificationType.CodeBreak:
					UpdateHud(force: true);
					break;
				case ConsoleNotificationType.PpuFrameDone:
					UpdateHud(force: false);
					break;
				case ConsoleNotificationType.EmulationStopped:
				case ConsoleNotificationType.BeforeEmulationStop:
					Clear();
					break;
			}
		}

		public static void Clear()
		{
			lock(_updateLock) {
				ClearHudInternal();
				ClearDataInternal();
			}
		}

		public static void Shutdown()
		{
			lock(_updateLock) {
				ClearHudInternal();
				ClearDataInternal();
				_previousValuesByCpu = new();
				_lastUpdate = DateTime.MinValue;
				_lastText = "";
				_lastDataJson = "";
			}

			lock(_watchHookLock) {
				if(!_watchHooksInstalled) {
					return;
				}

				foreach(CpuType cpuType in Enum.GetValues<CpuType>()) {
					WatchManager.GetWatchManager(cpuType).WatchChanged -= WatchManager_WatchChanged;
				}

				_watchHooksInstalled = false;
			}
		}

		private static void ClearHudInternal()
		{
			if(_cleared) {
				return;
			}

			EmuApi.SetWatchHudText(string.Empty);
			_lastText = "";
			_cleared = true;
		}

		private static void ClearDataInternal()
		{
			if(_dataCleared) {
				return;
			}

			EmuApi.SetWatchHudData(string.Empty);
			_lastDataJson = "";
			_dataCleared = true;
		}

		private static void EnsureWatchHooks()
		{
			if(_watchHooksInstalled) {
				return;
			}

			lock(_watchHookLock) {
				if(_watchHooksInstalled) {
					return;
				}

				DebugApi.InitializeDebugger();

				foreach(CpuType cpuType in Enum.GetValues<CpuType>()) {
					WatchManager.GetWatchManager(cpuType).WatchChanged += WatchManager_WatchChanged;
				}

				_watchHooksInstalled = true;
			}
		}

		private static void WatchManager_WatchChanged(bool resetSelection)
		{
			if(!ConfigManager.Config.Debug.Debugger.ShowWatchHud && !HasAnyWatchEntries()) {
				return;
			}
			UpdateHud(force: true);
		}

		private static void UpdateHud(bool force)
		{
			lock(_updateLock) {
				DateTime now = DateTime.UtcNow;
				if(!force && (now - _lastUpdate).TotalMilliseconds < 50) {
					return;
				}

				_lastUpdate = now;

				if(!EmuApi.IsRunning()) {
					ClearHudInternal();
					ClearDataInternal();
					return;
				}

				RomInfo romInfo = EmuApi.GetRomInfo();
				if(romInfo.Format == RomFormat.Unknown) {
					ClearHudInternal();
					ClearDataInternal();
					return;
				}

				DebuggerConfig cfg = ConfigManager.Config.Debug.Debugger;
				bool showHud = cfg.ShowWatchHud;

				CpuType mainCpu = romInfo.ConsoleType.GetMainCpuType();
				Dictionary<string, List<WatchEntryDto>> watchData = new();
				Dictionary<CpuType, List<WatchValueInfo>> newPrevious = new();
				List<WatchValueInfo>? mainCpuValues = null;

				foreach(CpuType cpuType in Enum.GetValues<CpuType>()) {
					WatchManager manager = WatchManager.GetWatchManager(cpuType);
					if(manager.WatchEntries.Count == 0) {
						continue;
					}

					if(!_previousValuesByCpu.TryGetValue(cpuType, out List<WatchValueInfo>? previousValues)) {
						previousValues = new List<WatchValueInfo>();
					}

					List<WatchValueInfo> values = manager.GetWatchContent(previousValues);
					newPrevious[cpuType] = values;
					if(cpuType == mainCpu) {
						mainCpuValues = values;
					}

					List<WatchEntryDto> entries = new();
					foreach(WatchValueInfo entry in values) {
						if(string.IsNullOrWhiteSpace(entry.Expression)) {
							continue;
						}
						entries.Add(new WatchEntryDto() {
							Expression = entry.Expression,
							Value = entry.Value,
							NumericValue = entry.NumericValue,
							IsChanged = entry.IsChanged
						});
					}

					if(entries.Count > 0) {
						watchData[cpuType.ToString().ToLowerInvariant()] = entries;
					}
				}

				_previousValuesByCpu = newPrevious;

				if(watchData.Count == 0) {
					ClearDataInternal();
				} else {
					string dataJson = JsonSerializer.Serialize(watchData, _watchJsonOptions);
					if(force || dataJson != _lastDataJson) {
						EmuApi.SetWatchHudData(dataJson);
						_lastDataJson = dataJson;
						_dataCleared = false;
					}
				}

				if(!showHud) {
					ClearHudInternal();
					return;
				}

				if(mainCpuValues == null) {
					ClearHudInternal();
					return;
				}

				int maxEntries = cfg.WatchHudMaxEntries;
				int count = 0;
				StringBuilder sb = new StringBuilder();
				foreach(WatchValueInfo entry in mainCpuValues) {
					if(string.IsNullOrWhiteSpace(entry.Expression)) {
						continue;
					}
					if(maxEntries > 0 && count >= maxEntries) {
						break;
					}

					if(count > 0) {
						sb.Append('\n');
					}
					sb.Append(entry.Expression);
					sb.Append(" = ");
					sb.Append(entry.Value);
					count++;
				}

				string text = sb.ToString();
				if(!force && text == _lastText) {
					return;
				}

				EmuApi.SetWatchHudText(text);
				_lastText = text;
				_cleared = text.Length == 0;
			}
		}

		private static bool HasAnyWatchEntries()
		{
			foreach(CpuType cpuType in Enum.GetValues<CpuType>()) {
				if(WatchManager.GetWatchManager(cpuType).WatchEntries.Count > 0) {
					return true;
				}
			}
			return false;
		}
	}
}
