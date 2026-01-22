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

			switch(e.NotificationType) {
				case ConsoleNotificationType.GameLoaded:
					_previousValues = new();
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
			if(_cleared) {
				return;
			}

			EmuApi.SetWatchHudText(string.Empty);
			_lastText = "";
			_cleared = true;
		}

		private static void UpdateHud(bool force)
		{
			DateTime now = DateTime.UtcNow;
			if(!force && (now - _lastUpdate).TotalMilliseconds < 50) {
				return;
			}

			_lastUpdate = now;

			DebuggerConfig cfg = ConfigManager.Config.Debug.Debugger;
			if(!cfg.ShowWatchHud) {
				Clear();
				return;
			}

			RomInfo romInfo = EmuApi.GetRomInfo();
			CpuType cpuType = romInfo.ConsoleType.GetMainCpuType();
			WatchManager manager = WatchManager.GetWatchManager(cpuType);

			if(manager.WatchEntries.Count == 0) {
				Clear();
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
