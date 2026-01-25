using Mesen.Config;
using Mesen.Config.Shortcuts;
using Mesen.Debugger.Utilities;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;

namespace Mesen.Utilities
{
	internal static class HeadlessRunner
	{
		internal static int Run(string[] args)
		{
			CommandLineHelper cmdLine = new(args, true);

			EmuApi.InitDll();
			ConfigManager.LoadConfig();
			ConfigManager.Config.ApplyConfig();

			EmuApi.InitializeEmu(
				ConfigManager.HomeFolder,
				IntPtr.Zero,
				IntPtr.Zero,
				true,
				cmdLine.NoAudio,
				true,
				cmdLine.NoInput
			);

			ConfigManager.Config.RemoveObsoleteConfig();
			ConfigManager.Config.InitializeDefaults();
			ConfigManager.Config.UpgradeConfig();

			DebugWorkspaceManager.Load();
			EmuApi.Pause();

			Console.CancelKeyPress += (sender, e) => {
				e.Cancel = true;
				EmuApi.Stop();
			};

			if(!LoadFiles(cmdLine)) {
				EmuApi.Release();
				return -1;
			}

			foreach(string luaScript in cmdLine.LuaScriptsToLoad) {
				LoadScriptHeadless(luaScript);
			}

			EmuApi.Resume();

			while(EmuApi.IsRunning()) {
				Thread.Sleep(250);
			}

			int stopCode = EmuApi.GetStopCode();
			EmuApi.Release();
			return stopCode;
		}

		private static bool LoadFiles(CommandLineHelper cmdLine)
		{
			string? romFile = null;
			string? patchFile = null;
			List<string> saveStates = new();
			List<string> movieFiles = new();

			foreach(string file in cmdLine.FilesToLoad) {
				string ext = Path.GetExtension(file).ToLowerInvariant();
				if(FolderHelper.IsRomFile(file)) {
					romFile ??= file;
					continue;
				}
				if(ext == ".ips" || ext == ".bps" || ext == ".ups") {
					patchFile ??= file;
					continue;
				}
				if(ext == "." + FileDialogHelper.MesenSaveStateExt) {
					saveStates.Add(file);
					continue;
				}
				if(ext == "." + FileDialogHelper.MesenMovieExt) {
					movieFiles.Add(file);
					continue;
				}
				if(FolderHelper.IsArchiveFile(file)) {
					Console.WriteLine($"Headless mode: archive files require UI selection, skipping '{file}'.");
					continue;
				}

				Console.WriteLine($"Headless mode: unsupported file '{file}'.");
			}

			if(cmdLine.LoadLastSessionRequested && romFile == null) {
				EmuApi.ExecuteShortcut(new ExecuteShortcutParams() { Shortcut = EmulatorShortcut.LoadLastSession });
			} else if(romFile != null) {
				EmuApi.LoadRom(romFile, patchFile ?? string.Empty);
			} else {
				Console.WriteLine("Headless mode: no ROM specified.");
				return false;
			}

			foreach(string stateFile in saveStates) {
				EmuApi.LoadStateFile(stateFile);
			}

			foreach(string movieFile in movieFiles) {
				RecordApi.MoviePlay(movieFile);
			}

			return true;
		}

		private static void LoadScriptHeadless(string luaScript)
		{
			string? content = FileHelper.ReadAllText(luaScript);
			if(string.IsNullOrEmpty(content)) {
				return;
			}

			string name = Path.GetFileName(luaScript);
			string path = (Path.GetDirectoryName(luaScript) ?? Program.OriginalFolder) + Path.DirectorySeparatorChar;
			DebugApi.LoadScript(name.Length == 0 ? "DefaultName" : name, path, content, -1);
		}
	}
}
