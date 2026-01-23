using Mesen.Config;
using Mesen.Debugger;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Text;

namespace Mesen.Debugger.Utilities
{
	public static class WatchHudService
	{
		private static readonly object _updateLock = new();
		private static readonly object _watchHookLock = new();
		private static bool _watchHooksInstalled = false;
		private static DateTime _lastUpdate = DateTime.MinValue;
		private static List<WatchValueInfo> _previousValues = new();
		private static string _lastText = "";
		private static bool _cleared = true;

		public static void ProcessNotification(NotificationEventArgs e)
		{
			if(!ConfigManager.Config.Debug.Debugger.ShowWatchHud) {
				Clear();
				return;
			}
			EnsureWatchHooks();

			switch(e.NotificationType) {
				case ConsoleNotificationType.BeforeGameLoad:
				case ConsoleNotificationType.BeforeGameUnload:
				case ConsoleNotificationType.GameLoaded:
					_previousValues = new();
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
				ClearInternal();
			}
		}

		public static void Shutdown()
		{
			lock(_updateLock) {
				ClearInternal();
				_previousValues = new();
				_lastUpdate = DateTime.MinValue;
				_lastText = "";
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

		private static void ClearInternal()
		{
			if(_cleared) {
				return;
			}

			EmuApi.SetWatchHudText(string.Empty);
			_lastText = "";
			_cleared = true;
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
			if(!ConfigManager.Config.Debug.Debugger.ShowWatchHud) {
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

				DebuggerConfig cfg = ConfigManager.Config.Debug.Debugger;
				if(!cfg.ShowWatchHud) {
					ClearInternal();
					return;
				}

				if(!EmuApi.IsRunning()) {
					ClearInternal();
					return;
				}

				RomInfo romInfo = EmuApi.GetRomInfo();
				if(romInfo.Format == RomFormat.Unknown) {
					ClearInternal();
					return;
				}

				CpuType cpuType = romInfo.ConsoleType.GetMainCpuType();
				WatchManager manager = WatchManager.GetWatchManager(cpuType);

				if(manager.WatchEntries.Count == 0) {
					ClearInternal();
					return;
				}

				List<WatchValueInfo> values = manager.GetWatchContent(_previousValues);
				_previousValues = values;

				int maxEntries = cfg.WatchHudMaxEntries;
				int count = 0;
				StringBuilder sb = new StringBuilder();
				foreach(WatchValueInfo entry in values) {
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
	}
}
