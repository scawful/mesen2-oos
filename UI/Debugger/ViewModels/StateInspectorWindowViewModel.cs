using Avalonia.Controls;
using Avalonia.Threading;
using Mesen.Config;
using Mesen.Debugger;
using Mesen.Debugger.Utilities;
using Mesen.Interop;
using Mesen.ViewModels;
using ReactiveUI.Fody.Helpers;
using System;
using System.Collections.Generic;
using static Mesen.Debugger.ViewModels.RegEntry;

namespace Mesen.Debugger.ViewModels
{
	public class StateInspectorWindowViewModel : DisposableViewModel, ICpuTypeModel
	{
		[Reactive] public List<RegEntry> Entries { get; private set; } = new();

		public StateInspectorConfig Config { get; }
		public RefreshTimingViewModel RefreshTiming { get; }
		public List<int> ColumnWidths => Config.ColumnWidths;

		[Reactive] public List<object> FileMenuActions { get; private set; } = new();
		[Reactive] public List<object> ViewMenuActions { get; private set; } = new();

		private RomInfo _romInfo = new();
		private List<WatchValueInfo> _previousWatchValues = new();

		public CpuType CpuType { get; set; } = CpuType.Snes;

		public StateInspectorWindowViewModel()
		{
			Config = ConfigManager.Config.Debug.StateInspector.Clone();

			if(Design.IsDesignMode) {
				CpuType = CpuType.Snes;
				RefreshTiming = new RefreshTimingViewModel(Config.RefreshTiming, CpuType);
				return;
			}

			DebugApi.InitializeDebugger();

			TryUpdateRomInfo();
			RefreshTiming = new RefreshTimingViewModel(Config.RefreshTiming, CpuType);
			RefreshTiming.UpdateMinMaxValues(CpuType);
			RefreshData();
		}

		public void InitMenu(Window wnd)
		{
			FileMenuActions = AddDisposables(new List<object>() {
				new ContextMenuAction() {
					ActionType = ActionType.Exit,
					OnClick = () => wnd?.Close()
				}
			});

			ViewMenuActions = AddDisposables(new List<object>() {
				new ContextMenuAction() {
					ActionType = ActionType.Refresh,
					Shortcut = () => ConfigManager.Config.Debug.Shortcuts.Get(DebuggerShortcut.Refresh),
					OnClick = () => RefreshData()
				},
				new ContextMenuSeparator(),
				new ContextMenuAction() {
					ActionType = ActionType.EnableAutoRefresh,
					IsSelected = () => Config.RefreshTiming.AutoRefresh,
					OnClick = () => Config.RefreshTiming.AutoRefresh = !Config.RefreshTiming.AutoRefresh
				},
				new ContextMenuAction() {
					ActionType = ActionType.RefreshOnBreakPause,
					IsSelected = () => Config.RefreshTiming.RefreshOnBreakPause,
					OnClick = () => Config.RefreshTiming.RefreshOnBreakPause = !Config.RefreshTiming.RefreshOnBreakPause
				}
			});

			DebugShortcutManager.RegisterActions(wnd, FileMenuActions);
			DebugShortcutManager.RegisterActions(wnd, ViewMenuActions);
		}

		private bool TryUpdateRomInfo()
		{
			if(!EmuApi.IsRunning()) {
				return false;
			}

			RomInfo romInfo = EmuApi.GetRomInfo();
			if(romInfo.Format == RomFormat.Unknown) {
				return false;
			}

			_romInfo = romInfo;
			CpuType = _romInfo.ConsoleType.GetMainCpuType();
			return true;
		}

		public void RefreshData()
		{
			if(!TryUpdateRomInfo()) {
				SetNoRomEntries();
				return;
			}
			TimingInfo timing = EmuApi.GetTimingInfo(CpuType);

			List<RegEntry> rows = new();
			rows.Add(new RegEntry("", "System"));
			rows.Add(new RegEntry("", "ROM", _romInfo.GetRomName(), null));
			rows.Add(BuildNumericEntry("Frame Count", timing.FrameCount, 8));
			rows.Add(BuildNumericEntry("Master Clock", timing.MasterClock, 16));
			rows.Add(BuildNumericEntry("Master Clock Rate", timing.MasterClockRate, 8));
			rows.Add(BuildNumericEntry("Cycle Count", timing.CycleCount, 8));

			if(_romInfo.ConsoleType == ConsoleType.Snes) {
				SnesCpuState cpu = DebugApi.GetCpuState<SnesCpuState>(CpuType);
				SnesPpuState ppu = DebugApi.GetPpuState<SnesPpuState>(CpuType.Snes);

				rows.Add(new RegEntry("", "CPU"));
				rows.Add(new RegEntry("", "A", cpu.A, Format.X16));
				rows.Add(new RegEntry("", "X", cpu.X, Format.X16));
				rows.Add(new RegEntry("", "Y", cpu.Y, Format.X16));
				rows.Add(new RegEntry("", "SP", cpu.SP, Format.X16));
				rows.Add(new RegEntry("", "D", cpu.D, Format.X16));
				rows.Add(new RegEntry("", "PC", (uint)((cpu.K << 16) | cpu.PC), Format.X24));
				rows.Add(new RegEntry("", "K (Program Bank)", cpu.K, Format.X8));
				rows.Add(new RegEntry("", "DBR", cpu.DBR, Format.X8));
				rows.Add(new RegEntry("", "P (Flags)", FormatSnesFlags(cpu), (byte)cpu.PS));
				rows.Add(new RegEntry("", "Emulation Mode", cpu.EmulationMode));

				rows.Add(new RegEntry("", "PPU"));
				rows.Add(new RegEntry("", "Scanline", ppu.Scanline, Format.X16));
				rows.Add(new RegEntry("", "Cycle", ppu.Cycle, Format.X16));
				rows.Add(new RegEntry("", "Frame", ppu.FrameCount, Format.X32));
				rows.Add(new RegEntry("", "Forced Blank", ppu.ForcedBlank));
				rows.Add(new RegEntry("", "Brightness", ppu.ScreenBrightness, Format.X8));
			}

			AppendWatchEntries(rows);

			if(Dispatcher.UIThread.CheckAccess()) {
				Entries = rows;
			} else {
				Dispatcher.UIThread.Post(() => {
					Entries = rows;
				});
			}
		}

		private RegEntry BuildNumericEntry(string name, ulong value, int hexDigits)
		{
			RegEntry entry = new RegEntry("", name, value);
			entry.ValueHex = "$" + value.ToString("X" + hexDigits);
			return entry;
		}

		private void AppendWatchEntries(List<RegEntry> rows)
		{
			WatchManager manager = WatchManager.GetWatchManager(CpuType);
			if(manager.WatchEntries.Count == 0) {
				return;
			}

			rows.Add(new RegEntry("", "Watch"));
			List<WatchValueInfo> values = manager.GetWatchContent(_previousWatchValues);
			_previousWatchValues = values;
			foreach(WatchValueInfo entry in values) {
				if(string.IsNullOrWhiteSpace(entry.Expression)) {
					continue;
				}
				rows.Add(new RegEntry("", entry.Expression, entry.Value, null));
			}
		}

		private string FormatSnesFlags(SnesCpuState cpu)
		{
			bool flagN = (cpu.PS & SnesCpuFlags.Negative) != 0;
			bool flagV = (cpu.PS & SnesCpuFlags.Overflow) != 0;
			bool flagM = (cpu.PS & SnesCpuFlags.MemoryMode8) != 0;
			bool flagX = (cpu.PS & SnesCpuFlags.IndexMode8) != 0;
			bool flagD = (cpu.PS & SnesCpuFlags.Decimal) != 0;
			bool flagI = (cpu.PS & SnesCpuFlags.IrqDisable) != 0;
			bool flagZ = (cpu.PS & SnesCpuFlags.Zero) != 0;
			bool flagC = (cpu.PS & SnesCpuFlags.Carry) != 0;
			char flagE = cpu.EmulationMode ? 'E' : 'e';

			return string.Format(
				"{0}{1}{2}{3}{4}{5}{6}{7} {8}",
				flagN ? 'N' : 'n',
				flagV ? 'V' : 'v',
				flagM ? 'M' : 'm',
				flagX ? 'X' : 'x',
				flagD ? 'D' : 'd',
				flagI ? 'I' : 'i',
				flagZ ? 'Z' : 'z',
				flagC ? 'C' : 'c',
				flagE
			);
		}

		public void OnGameLoaded()
		{
			_previousWatchValues = new();
			RefreshData();
		}

		private void SetNoRomEntries()
		{
			List<RegEntry> rows = new() {
				new RegEntry("", "System"),
				new RegEntry("", "ROM", "No ROM loaded", null)
			};

			if(Dispatcher.UIThread.CheckAccess()) {
				Entries = rows;
			} else {
				Dispatcher.UIThread.Post(() => {
					Entries = rows;
				});
			}
		}
	}
}
