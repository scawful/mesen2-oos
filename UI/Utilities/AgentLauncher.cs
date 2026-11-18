using Avalonia.Threading;
using Mesen.Windows;
using System;
using System.Diagnostics;
using System.IO;

namespace Mesen.Utilities
{
	public static class AgentLauncher
	{
		public static void OpenMesen2Root()
		{
			if(!TryGetMesen2Root(out string root, out string? error)) {
				ShowError(error ?? "Mesen2 root not found.");
				return;
			}

			OpenPath(root);
		}

		public static void OpenProjectRoot()
		{
			if(!TryGetProjectRoot(out string root, out string? error)) {
				ShowError(error ?? "Project root not found.");
				return;
			}

			OpenPath(root);
		}

		public static void OpenOracleRoot() => OpenProjectRoot();

		public static void OpenProjectConfig(string subPath)
		{
			if(!TryGetProjectRoot(out string root, out string? error)) {
				ShowError(error ?? "Project root not found.");
				return;
			}
			OpenPath(Path.Combine(root, subPath));
		}

		public static void OpenOracleRoms() => OpenProjectConfig("Roms");
		public static void OpenOracleDocs() => OpenProjectConfig("Docs");
		public static void OpenOracleTests() => OpenProjectConfig("Tests");
		public static void OpenOracleDebugData() => OpenProjectConfig(Path.Combine("Data", "debug"));

		public static void OpenOracleSavestateLibrary()
		{
			string libraryRoot = OracleStateLibrary.GetLibraryRootPath();
			if(Directory.Exists(libraryRoot)) {
				OpenPath(libraryRoot);
				return;
			}

			string? parent = Path.GetDirectoryName(libraryRoot);
			if(!string.IsNullOrWhiteSpace(parent) && Directory.Exists(parent)) {
				OpenPath(parent);
				return;
			}

			OpenOracleRoms();
		}

		public static void OpenAfsScratchpad()
		{
			OpenPath("~/.context/projects/oracle-of-secrets/scratchpad");
		}

		public static void OpenMesen2Doc(string relativePath)
		{
			if(!TryGetMesen2Root(out string root, out string? error)) {
				ShowError(error ?? "Mesen2 root not found.");
				return;
			}

			string path = Path.Combine(root, relativePath);
			OpenPath(path);
		}

		public static void OpenPath(string path)
		{
			if(string.IsNullOrWhiteSpace(path)) {
				ShowError("Path not set.");
				return;
			}

			string expanded = ExpandHome(path);
			if(!File.Exists(expanded) && !Directory.Exists(expanded)) {
				ShowError($"Path not found: {expanded}");
				return;
			}

			try {
				Process.Start(new ProcessStartInfo() {
					FileName = expanded,
					UseShellExecute = true,
					Verb = "open"
				});
			} catch(Exception ex) {
				ShowError($"Failed to open path: {ex.Message}");
			}
		}

		public static string? ResolveProjectRoot()
		{
			string? envRoot = Environment.GetEnvironmentVariable("MESEN2_PROJECT_ROOT")
				?? Environment.GetEnvironmentVariable("ORACLE_OF_SECRETS_ROOT")
				?? Environment.GetEnvironmentVariable("OOS_ROOT")
				?? Environment.GetEnvironmentVariable("MESEN2_ORACLE_ROOT");

			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					return expanded;
				}
			}

			if(TryGetMesen2Root(out string mesenRoot, out _)) {
				string? hobbyRoot = Directory.GetParent(mesenRoot)?.FullName;
				if(!string.IsNullOrWhiteSpace(hobbyRoot)) {
					string sibling = Path.Combine(hobbyRoot, "oracle-of-secrets");
					if(Directory.Exists(sibling)) {
						return sibling;
					}
				}
			}

			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string defaultRoot = Path.Combine(home, "src", "hobby", "oracle-of-secrets");
			return Directory.Exists(defaultRoot) ? defaultRoot : null;
		}

		private static bool TryGetProjectRoot(out string root, out string? error)
		{
			string? resolved = ResolveProjectRoot();
			if(resolved != null) {
				root = resolved;
				error = null;
				return true;
			}
			root = string.Empty;
			error = "Project root not found. Set ORACLE_OF_SECRETS_ROOT or MESEN2_PROJECT_ROOT.";
			return false;
		}

		private static bool TryGetMesen2Root(out string root, out string? error)
		{
			root = string.Empty;
			error = null;

			string? envRoot = Environment.GetEnvironmentVariable("MESEN2_OOS_ROOT")
				?? Environment.GetEnvironmentVariable("MESEN2_ROOT");

			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					root = expanded;
					return true;
				}
			}

			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string defaultRoot = Path.Combine(home, "src", "hobby", "mesen2-oos");
			if(Directory.Exists(defaultRoot)) {
				root = defaultRoot;
				return true;
			}

			error = "Mesen2/OOS root not found. Set MESEN2_ROOT.";
			return false;
		}

		private static string ExpandHome(string path)
		{
			if(path.StartsWith("~", StringComparison.Ordinal)) {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return Path.Combine(home, path.TrimStart('~').TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
			}
			return path;
		}

		private static void ShowError(string message)
		{
			Dispatcher.UIThread.Post(() => {
				MessageBox.Show(ApplicationHelper.GetActiveOrMainWindow(), message, "Oracle Launcher", MessageBoxButtons.OK, MessageBoxIcon.Error);
			});
		}
	}
}
