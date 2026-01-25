using Mesen.Config;
using Mesen.Debugger.Utilities;
using Mesen.Debugger.ViewModels;
using Mesen.Debugger.Windows;
using Mesen.Interop;
using Mesen.Localization;
using Mesen.Utilities;
using Mesen.ViewModels;
using Mesen.Windows;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;
using Avalonia.Threading;

namespace Mesen.Utilities;

public class CommandLineHelper
{
	public bool NoVideo { get; private set; }
	public bool NoAudio { get; private set; }
	public bool NoInput { get; private set; }
	public bool Fullscreen { get; private set; }
	public bool LoadLastSessionRequested { get; private set; }
	public string? MovieToRecord { get; private set; } = null;
	public int TestRunnerTimeout { get; private set; } = 100;
	public List<string> LuaScriptsToLoad { get; private set; } = new();
	public List<string> FilesToLoad { get; private set; } = new();
	public bool OpenDebuggerRequested { get; private set; }
	public bool OpenStateInspectorRequested { get; private set; }
	public bool EnableWatchHudRequested { get; private set; }
	public bool OpenScriptWindowRequested { get; private set; }
	public bool HeadlessRequested { get; private set; }

	private List<string> _errorMessages = new();
	private bool _debugToolsOpened = false;

	public CommandLineHelper(string[] args, bool forStartup)
	{
		ProcessCommandLineArgs(args, forStartup);
	}

	private void ProcessCommandLineArgs(string[] args, bool forStartup)
	{
		bool hasUserLua = false;
		foreach(string arg in args) {
			string absPath;
			if(Path.IsPathRooted(arg)) {
				absPath = arg;
			} else {
				absPath = Path.GetFullPath(arg, Program.OriginalFolder);
			}

			if(File.Exists(absPath)) {
				switch(Path.GetExtension(absPath).ToLowerInvariant()) {
					case ".lua":
						LuaScriptsToLoad.Add(absPath);
						if(!IsBridgeScriptArg(absPath)) {
							hasUserLua = true;
						}
						break;
					default: FilesToLoad.Add(absPath); break;
				}
			} else if(arg.StartsWith("-") || arg.StartsWith("/")) {
				string switchArg = ConvertArg(arg).ToLowerInvariant();
				switch(switchArg) {
					case "novideo": NoVideo = true; break;
					case "noaudio": NoAudio = true; break;
					case "noinput": NoInput = true; break;
					case "fullscreen": Fullscreen = true; break;
					case "donotsavesettings": ConfigManager.DisableSaveSettings = true; break;
					case "loadlastsession": LoadLastSessionRequested = true; break;
					case "opendebugger": OpenDebuggerRequested = true; break;
					case "openstateinspector": OpenStateInspectorRequested = true; break;
					case "enablewatchhud": EnableWatchHudRequested = true; break;
					case "openscriptwindow": OpenScriptWindowRequested = true; break;
					case "headless":
					case "nogui":
					case "noui":
						HeadlessRequested = true;
						break;
					case "autodebug":
						OpenDebuggerRequested = true;
						OpenStateInspectorRequested = true;
						EnableWatchHudRequested = true;
						break;
					default:
						if(switchArg.StartsWith("recordmovie=")) {
							string[] values = switchArg.Split('=');
							if(values.Length <= 1) {
								//invalid
								continue;
							}
							string moviePath = values[1];
							string? folder = Path.GetDirectoryName(moviePath);
							if(string.IsNullOrWhiteSpace(folder)) {
								moviePath = Path.Combine(ConfigManager.MovieFolder, moviePath);
							} else if(!Path.IsPathRooted(moviePath)) {
								moviePath = Path.Combine(Program.OriginalFolder, moviePath);
							}
							if(!moviePath.ToLower().EndsWith("." + FileDialogHelper.MesenMovieExt)) {
								moviePath += "." + FileDialogHelper.MesenMovieExt;
							}
							MovieToRecord = moviePath;
						} else if(switchArg.StartsWith("timeout=")) {
							string[] values = switchArg.Split('=');
							if(values.Length <= 1) {
								//invalid
								continue;
							}
							if(int.TryParse(values[1], out int timeout)) {
								TestRunnerTimeout = timeout;
							}
						} else if(switchArg.StartsWith("instanceguid=") || switchArg.StartsWith("instancename=") || switchArg == "multiinstance") {
							//Handled early in Program.Main; ignore here to avoid invalid switch errors.
						} else {
							if(!ConfigManager.ProcessSwitch(switchArg)) {
								_errorMessages.Add(ResourceHelper.GetMessage("InvalidArgument", arg));
							}
						}
						break;
				}
			} else {
				_errorMessages.Add(ResourceHelper.GetMessage("FileNotFound", arg));
			}
		}

		if(!HeadlessRequested && hasUserLua && !OpenScriptWindowRequested &&
			!OpenDebuggerRequested && !OpenStateInspectorRequested && !EnableWatchHudRequested) {
			HeadlessRequested = true;
		}
	}

	public void OnAfterInit(MainWindow wnd, CancellationToken shutdownToken)
	{
		if(Fullscreen && FilesToLoad.Count == 0) {
			wnd.ToggleFullscreen();
			Fullscreen = false;
		}

		if(OpenDebuggerRequested || OpenStateInspectorRequested || EnableWatchHudRequested) {
			if(shutdownToken.IsCancellationRequested) {
				return;
			}

			Task.Run(async () => {
				const int timeoutMs = 10000;
				const int pollMs = 100;
				int waitedMs = 0;
				RomInfo romInfo = EmuApi.GetRomInfo();
				while(!shutdownToken.IsCancellationRequested && romInfo.Format == RomFormat.Unknown && waitedMs < timeoutMs) {
					await Task.Delay(pollMs).ConfigureAwait(false);
					waitedMs += pollMs;
					romInfo = EmuApi.GetRomInfo();
				}

				if(shutdownToken.IsCancellationRequested) {
					return;
				}

				Dispatcher.UIThread.Post(() => {
					if(shutdownToken.IsCancellationRequested) {
						return;
					}

					ProcessDebugAutomation(wnd);
				});
			});
		}
	}

	private static string ConvertArg(string arg)
	{
		arg = arg.Trim();
		if(arg.StartsWith("--")) {
			arg = arg.Substring(2);
		} else if(arg.StartsWith("-") || arg.StartsWith("/")) {
			arg = arg.Substring(1);
		}
		return arg;
	}

	private static bool IsBridgeScriptArg(string arg)
	{
		if(string.IsNullOrWhiteSpace(arg)) {
			return false;
		}

		string fileName = Path.GetFileName(arg);
		if(string.IsNullOrWhiteSpace(fileName)) {
			return false;
		}

		string baseName = Path.GetFileNameWithoutExtension(fileName);
		return baseName.StartsWith("mesen_live_bridge", StringComparison.OrdinalIgnoreCase)
			|| baseName.StartsWith("mesen_socket_bridge", StringComparison.OrdinalIgnoreCase);
	}

	public static bool ShouldHideMainWindow(string[] args)
	{
		bool headless = false;
		bool hasUserLua = false;
		bool openScriptWindow = false;
		bool openDebugger = false;
		bool openStateInspector = false;
		bool enableWatchHud = false;

		foreach(string arg in args) {
			if(arg.StartsWith("-") || arg.StartsWith("/")) {
				string switchArg = ConvertArg(arg).ToLowerInvariant();
				switch(switchArg) {
					case "headless":
					case "nogui":
					case "noui":
						headless = true;
						break;
					case "openscriptwindow":
						openScriptWindow = true;
						break;
					case "opendebugger":
						openDebugger = true;
						break;
					case "openstateinspector":
						openStateInspector = true;
						break;
					case "enablewatchhud":
						enableWatchHud = true;
						break;
				}
			} else if(Path.GetExtension(arg).Equals(".lua", StringComparison.OrdinalIgnoreCase)) {
				if(!IsBridgeScriptArg(arg)) {
					hasUserLua = true;
				}
			}
		}

		if(headless) {
			return true;
		}

		if(hasUserLua && !openScriptWindow && !openDebugger && !openStateInspector && !enableWatchHud) {
			return true;
		}

		return false;
	}

	public static bool IsTestRunner(string[] args)
	{
		return args.Any(arg => CommandLineHelper.ConvertArg(arg).ToLowerInvariant() == "testrunner");
	}

	public void ProcessPostLoadCommandSwitches(MainWindow wnd)
	{
		if(LuaScriptsToLoad.Count > 0) {
			foreach(string luaScript in LuaScriptsToLoad) {
				if(OpenScriptWindowRequested) {
					ScriptWindowViewModel model = new();
					model.LoadScript(luaScript);
					if(IsBridgeScriptArg(luaScript)) {
						model.RunScript();
					}
					DebugWindowManager.OpenDebugWindow(() => new ScriptWindow(model));
				} else {
					LoadScriptHeadless(luaScript);
				}
			}
		}

		if(MovieToRecord != null) {
			if(RecordApi.MovieRecording()) {
				RecordApi.MovieStop();
			}
			RecordMovieOptions options = new RecordMovieOptions(MovieToRecord, "", "", RecordMovieFrom.StartWithSaveData);
			RecordApi.MovieRecord(options);
		}

		if(Fullscreen) {
			wnd.ToggleFullscreen();
		}

		if(LoadLastSessionRequested) {
			Task.Run(() => {
				EmuApi.ExecuteShortcut(new ExecuteShortcutParams() { Shortcut = Config.Shortcuts.EmulatorShortcut.LoadLastSession });
			});
		}

		ProcessDebugAutomation(wnd);
	}

	private void LoadScriptHeadless(string luaScript)
	{
		string? content = FileHelper.ReadAllText(luaScript);
		if(string.IsNullOrEmpty(content)) {
			return;
		}

		string name = Path.GetFileName(luaScript);
		string path = (Path.GetDirectoryName(luaScript) ?? Program.OriginalFolder) + Path.DirectorySeparatorChar;
		DebugApi.LoadScript(name, path, content, -1);
	}

	private void ProcessDebugAutomation(MainWindow wnd)
	{
		if(!(OpenDebuggerRequested || OpenStateInspectorRequested || EnableWatchHudRequested)) {
			return;
		}

		if(EnableWatchHudRequested) {
			ConfigManager.Config.Debug.Debugger.ShowWatchHud = true;
			ConfigManager.Config.Debug.ApplyConfig();
		}

		RomInfo activeRom = EmuApi.GetRomInfo();
		if(activeRom.Format == RomFormat.Unknown) {
			return;
		}

		if(_debugToolsOpened) {
			return;
		}
		_debugToolsOpened = true;

		CpuType cpuType = activeRom.ConsoleType.GetMainCpuType();
		if(OpenDebuggerRequested) {
			DebuggerWindow.GetOrOpenWindow(cpuType);
		}
		if(OpenStateInspectorRequested) {
			DebugWindowManager.GetOrOpenDebugWindow(() => new StateInspectorWindow(new StateInspectorWindowViewModel()));
		}
	}

	public void LoadFiles()
	{
		foreach(string file in FilesToLoad) {
			LoadRomHelper.LoadFile(file);
		}

		foreach(string msg in _errorMessages) {
			DisplayMessageHelper.DisplayMessage("Error", msg);
		}
	}

	public static Dictionary<string, string> GetAvailableSwitches()
	{
		Dictionary<string, string> result = new();

		string general = @"--fullscreen - Start in fullscreen mode
--doNotSaveSettings - Prevent settings from being saved to the disk (useful to prevent command line options from becoming the default settings)
--recordMovie=""filename.mmo"" - Start recording a movie after the specified game is loaded.
--loadLastSession - Resumes the game in the state it was left in when it was last played.
--openDebugger - Open the main debugger window after a ROM loads.
--openStateInspector - Open the State Inspector window after a ROM loads.
--enableWatchHud - Enable the watch HUD overlay.
--openScriptWindow - Open a Script Window for any .lua file passed on the command line.
--headless - Hide the main window (default when non-bridge .lua scripts are passed without UI flags).
--instanceGuid=<guid> - Override the single-instance identifier for this process.
--instanceName=<name> - Derive a stable instance GUID from a name.
--multiInstance - Always use a new instance GUID (allows multiple simultaneous instances).
--autoDebug - Enable watch HUD and open Debugger + State Inspector after a ROM loads.";

		result["General"] = general;
		result["Audio"] = GetSwichesForObject("audio.", typeof(AudioConfig));
		result["Emulation"] = GetSwichesForObject("emulation.", typeof(EmulationConfig));
		result["Input"] = GetSwichesForObject("input.", typeof(InputConfig));
		result["Video"] = GetSwichesForObject("video.", typeof(VideoConfig));
		result["Preferences"] = GetSwichesForObject("preferences.", typeof(PreferencesConfig));
		result["Nes"] = GetSwichesForObject("nes.", typeof(NesConfig));
		result["Snes"] = GetSwichesForObject("snes.", typeof(SnesConfig));
		result["Game Boy"] = GetSwichesForObject("gameBoy.", typeof(GameboyConfig));
		result["GBA"] = GetSwichesForObject("gba.", typeof(GbaConfig));
		result["PC Engine"] = GetSwichesForObject("pcEngine.", typeof(PcEngineConfig));
		result["SMS"] = GetSwichesForObject("sms.", typeof(SmsConfig));

		return result;
	}

	private static string GetSwichesForObject(string prefix, Type type)
	{
		StringBuilder sb = new();

#pragma warning disable IL2070 // 'this' argument does not satisfy 'DynamicallyAccessedMembersAttribute' in call to target method. The parameter of method does not have matching annotations.
		foreach(PropertyInfo info in type.GetProperties()) {
			if(!info.CanWrite) {
				continue;
			}

			string name = char.ToLowerInvariant(info.Name[0]) + info.Name.Substring(1);
			if(info.PropertyType == typeof(int) || info.PropertyType == typeof(uint) || info.PropertyType == typeof(double)) {
				MinMaxAttribute? minMaxAttribute = info.GetCustomAttribute(typeof(MinMaxAttribute)) as MinMaxAttribute;
				if(minMaxAttribute != null) {
					sb.AppendLine("--" + prefix + name + "=[" + minMaxAttribute.Min.ToString() + " - " + minMaxAttribute.Max.ToString() + "]");
				}
			} else if(info.PropertyType == typeof(bool)) {
				sb.AppendLine("--" + prefix + name + "=[true | false]");
			} else if(info.PropertyType.IsEnum) {
				if(info.PropertyType != typeof(ControllerType)) {
					ValidValuesAttribute? validValuesAttribute = info.GetCustomAttribute(typeof(ValidValuesAttribute)) as ValidValuesAttribute;
					if(validValuesAttribute != null) {
						sb.AppendLine("--" + prefix + name + "=[" + string.Join(" | ", validValuesAttribute.ValidValues.Select(v => Enum.GetName(info.PropertyType, v))) + "]");
					} else {
						sb.AppendLine("--" + prefix + name + "=[" + string.Join(" | ", Enum.GetNames(info.PropertyType)) + "]");
					}
				}
			} else if(info.PropertyType.IsClass && !info.PropertyType.IsGenericType) {
				string content = GetSwichesForObject(prefix + name + ".", info.PropertyType);
				if(content.Length > 0) {
					sb.Append(content);
				}
			}
		}
#pragma warning restore IL2070 // 'this' argument does not satisfy 'DynamicallyAccessedMembersAttribute' in call to target method. The parameter of method does not have matching annotations.

		return sb.ToString();
	}
}
