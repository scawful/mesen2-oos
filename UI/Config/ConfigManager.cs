using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.IO;
using System.Text.RegularExpressions;
using System.Text.Json;
using System.Reflection;
using Mesen.Interop;
using System.Diagnostics;
using Mesen.Utilities;
using Avalonia.Controls;

namespace Mesen.Config
{
	public static class ConfigManager
	{
		private static Configuration? _config;
		private static string? _homeFolder = null;
		private static object _initLock = new object();

		public static string DefaultPortableFolder { get { return Path.GetDirectoryName(Program.ExePath) ?? "./"; } }
		public static string DefaultDocumentsFolder => ResolveDefaultDocumentsFolder();

		private static string ResolveDefaultDocumentsFolder()
		{
			Environment.SpecialFolder folder = OperatingSystem.IsWindows() ? Environment.SpecialFolder.MyDocuments : Environment.SpecialFolder.ApplicationData;
			string basePath = Environment.GetFolderPath(folder, Environment.SpecialFolderOption.Create);

			if(OperatingSystem.IsMacOS()) {
				// Some launchers strip HOME, which can make ApplicationData resolve to /var/root or empty.
				if(string.IsNullOrWhiteSpace(basePath) || basePath == "/" || basePath.StartsWith("/var/root") || basePath.StartsWith("/private/var/root")) {
					string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile, Environment.SpecialFolderOption.Create);
					if(!string.IsNullOrWhiteSpace(userProfile)) {
						basePath = Path.Combine(userProfile, "Library", "Application Support");
					} else {
						string? envHome = Environment.GetEnvironmentVariable("HOME");
						if(!string.IsNullOrWhiteSpace(envHome)) {
							basePath = Path.Combine(envHome, "Library", "Application Support");
						}
					}
				}
			}

			if(string.IsNullOrWhiteSpace(basePath)) {
				basePath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile, Environment.SpecialFolderOption.Create);
			}

			if(string.IsNullOrWhiteSpace(basePath)) {
				basePath = DefaultPortableFolder;
			}

			return Path.Combine(basePath, "Mesen2");
		}

		public static string DefaultAviFolder { get { return Path.Combine(HomeFolder, "Avi"); } }
		public static string DefaultMovieFolder { get { return Path.Combine(HomeFolder, "Movies"); } }
		public static string DefaultSaveDataFolder { get { return Path.Combine(HomeFolder, "Saves"); } }
		public static string DefaultSaveStateFolder { get { return Path.Combine(HomeFolder, "SaveStates"); } }
		public static string DefaultScreenshotFolder { get { return Path.Combine(HomeFolder, "Screenshots"); } }
		public static string DefaultWaveFolder { get { return Path.Combine(HomeFolder, "Wave"); } }

		public static bool DisableSaveSettings { get; internal set; }

		private struct ConfigCandidate
		{
			public string Folder;
			public DateTime LastWriteUtc;
			public bool IsValid;
			public bool IsFresh;
			public int RecentCount;
			public int BoundShortcutCount;
			public int SaveStateCount;
			public int SaveFileCount;
		}

		private static List<string> GetLegacyConfigFolders(string defaultFolder, string portableFolder)
		{
			List<string> candidates = new();
			try {
				string documentsPath = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments, Environment.SpecialFolderOption.Create);
				if(!string.IsNullOrWhiteSpace(documentsPath)) {
					candidates.Add(Path.Combine(documentsPath, "Mesen2"));
				}
			} catch {
			}

			try {
				string homePath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile, Environment.SpecialFolderOption.Create);
				if(!string.IsNullOrWhiteSpace(homePath)) {
					candidates.Add(Path.Combine(homePath, ".config", "Mesen2"));
				}
			} catch {
			}

			List<string> legacyFolders = new();
			foreach(string candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase)) {
				if(string.Equals(candidate, defaultFolder, StringComparison.OrdinalIgnoreCase) || string.Equals(candidate, portableFolder, StringComparison.OrdinalIgnoreCase)) {
					continue;
				}

				string settingsPath = Path.Combine(candidate, "settings.json");
				if(File.Exists(settingsPath)) {
					legacyFolders.Add(candidate);
				}
			}

			return legacyFolders;
		}

		private static List<string> GetLegacyMesenFolders(string defaultFolder, string portableFolder)
		{
			List<string> candidates = new();
			try {
				string documentsPath = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments, Environment.SpecialFolderOption.Create);
				if(!string.IsNullOrWhiteSpace(documentsPath)) {
					candidates.Add(Path.Combine(documentsPath, "Mesen"));
				}
			} catch {
			}

			try {
				string appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData, Environment.SpecialFolderOption.Create);
				if(!string.IsNullOrWhiteSpace(appDataPath)) {
					candidates.Add(Path.Combine(appDataPath, "Mesen"));
				}
			} catch {
			}

			try {
				string homePath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile, Environment.SpecialFolderOption.Create);
				if(!string.IsNullOrWhiteSpace(homePath)) {
					candidates.Add(Path.Combine(homePath, ".config", "Mesen"));
				}
			} catch {
			}

			List<string> legacyFolders = new();
			foreach(string candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase)) {
				if(string.Equals(candidate, defaultFolder, StringComparison.OrdinalIgnoreCase) || string.Equals(candidate, portableFolder, StringComparison.OrdinalIgnoreCase)) {
					continue;
				}

				string settingsPath = Path.Combine(candidate, "settings.json");
				if(File.Exists(settingsPath) || Directory.Exists(Path.Combine(candidate, "SaveStates")) || Directory.Exists(Path.Combine(candidate, "Saves"))) {
					legacyFolders.Add(candidate);
				}
			}

			return legacyFolders;
		}

		private static void CopyDirectory(string sourceDir, string destDir)
		{
			try {
				foreach(string dirPath in Directory.EnumerateDirectories(sourceDir, "*", SearchOption.AllDirectories)) {
					string relative = Path.GetRelativePath(sourceDir, dirPath);
					string target = Path.Combine(destDir, relative);
					Directory.CreateDirectory(target);
				}

				foreach(string filePath in Directory.EnumerateFiles(sourceDir, "*", SearchOption.AllDirectories)) {
					string relative = Path.GetRelativePath(sourceDir, filePath);
					string target = Path.Combine(destDir, relative);
					if(File.Exists(target)) {
						continue;
					}
					Directory.CreateDirectory(Path.GetDirectoryName(target) ?? destDir);
					File.Copy(filePath, target, false);
				}
			} catch {
			}
		}

		private static void TryMigrateLegacyMesenFolder(string destinationFolder, string portableFolder, string documentsFolder)
		{
			try {
				string markerPath = Path.Combine(destinationFolder, ".migrated-from-mesen");
				if(File.Exists(markerPath)) {
					return;
				}

				string destConfigPath = Path.Combine(destinationFolder, "settings.json");
				if(File.Exists(destConfigPath)) {
					return;
				}

				List<string> legacyFolders = GetLegacyMesenFolders(documentsFolder, portableFolder);
				if(legacyFolders.Count == 0) {
					return;
				}

				string? legacyFolder = SelectBestConfigFolder(legacyFolders);
				if(string.IsNullOrWhiteSpace(legacyFolder)) {
					return;
				}

				EmuApi.WriteLogEntry("[UI] Migrating legacy Mesen data from: " + legacyFolder);
				CopyDirectory(legacyFolder, destinationFolder);
				File.WriteAllText(markerPath, DateTime.UtcNow.ToString("O"));
			} catch {
			}
		}

		private static bool TryLoadConfigMetadata(string configPath, out bool isFresh, out int recentCount, out int boundShortcutCount)
		{
			isFresh = false;
			recentCount = 0;
			boundShortcutCount = 0;
			try {
				string fileData = File.ReadAllText(configPath).TrimStart('\uFEFF');
				Configuration? config = (Configuration?)JsonSerializer.Deserialize(fileData, typeof(Configuration), MesenSerializerContext.Default);
				if(config == null) {
					return false;
				}

				bool isFirstRun = config.ConfigUpgrade == (int)ConfigUpgradeHint.FirstRun;
				bool isUninitialized = config.ConfigUpgrade == (int)ConfigUpgradeHint.Uninitialized;
				if(config.Preferences?.ShortcutKeys != null) {
					boundShortcutCount = config.Preferences.ShortcutKeys.Count(info =>
						(info.KeyCombination != null && !info.KeyCombination.IsEmpty) ||
						(info.KeyCombination2 != null && !info.KeyCombination2.IsEmpty)
					);
				}
				if(config.RecentFiles?.Items != null) {
					recentCount = config.RecentFiles.Items.Count;
				}
				isFresh = isFirstRun || (isUninitialized && boundShortcutCount == 0 && recentCount == 0);
				return true;
			} catch {
				return false;
			}
		}

		private static int CountFiles(string folder)
		{
			try {
				if(!Directory.Exists(folder)) {
					return 0;
				}
				return Directory.EnumerateFiles(folder, "*", SearchOption.AllDirectories).Count();
			} catch {
				return 0;
			}
		}

		private static string? SelectBestConfigFolder(List<string> folders)
		{
			List<ConfigCandidate> candidates = new();
			foreach(string folder in folders.Distinct(StringComparer.OrdinalIgnoreCase)) {
				string configPath = Path.Combine(folder, "settings.json");
				if(!File.Exists(configPath)) {
					continue;
				}

				bool isValid = TryLoadConfigMetadata(configPath, out bool isFresh, out int recentCount, out int boundShortcutCount);
				DateTime lastWrite = File.GetLastWriteTimeUtc(configPath);
				int saveStateCount = CountFiles(Path.Combine(folder, "SaveStates"));
				int saveFileCount = CountFiles(Path.Combine(folder, "Saves"));
				candidates.Add(new ConfigCandidate() {
					Folder = folder,
					LastWriteUtc = lastWrite,
					IsValid = isValid,
					IsFresh = isFresh,
					RecentCount = recentCount,
					BoundShortcutCount = boundShortcutCount,
					SaveStateCount = saveStateCount,
					SaveFileCount = saveFileCount
				});
			}

			if(candidates.Count == 0) {
				return null;
			}

			IEnumerable<ConfigCandidate> selection = candidates;
			List<ConfigCandidate> validCandidates = candidates.Where(c => c.IsValid).ToList();
			if(validCandidates.Count > 0) {
				selection = validCandidates;
			}

			return selection
				.OrderByDescending(c => c.SaveStateCount > 0)
				.ThenByDescending(c => c.SaveStateCount)
				.ThenByDescending(c => c.SaveFileCount > 0)
				.ThenByDescending(c => c.SaveFileCount)
				.ThenByDescending(c => c.RecentCount > 0)
				.ThenByDescending(c => c.RecentCount)
				.ThenByDescending(c => c.BoundShortcutCount > 0)
				.ThenByDescending(c => c.BoundShortcutCount)
				.ThenByDescending(c => !c.IsFresh)
				.ThenByDescending(c => c.LastWriteUtc)
				.First().Folder;
		}

		public static string GetConfigFile()
		{
			return Path.Combine(HomeFolder, "settings.json");
		}

		public static void CreateConfig(bool portable)
		{
			string homeFolder;
			if(portable) {
				homeFolder = DefaultPortableFolder;
			} else {
				homeFolder = DefaultDocumentsFolder;
			}
			Program.ExtractNativeDependencies(homeFolder);
			_homeFolder = homeFolder;
			Config.Save();
		}
		
		public static void LoadConfig()
		{
			if(_config == null) {
				lock(_initLock) {
					if(_config == null) {
						if(File.Exists(ConfigFile) && !Design.IsDesignMode) {
							_config = Configuration.Deserialize(ConfigFile);
						} else {
							_config = Configuration.CreateConfig();
						}
						ConfigManager.ActiveTheme = _config.Preferences.Theme;
					}
				}
			}
		}

		public static MesenTheme ActiveTheme { get; private set; }

		private static bool ApplySetting(object instance, PropertyInfo property, string value)
		{
			Type t = property.PropertyType;
			try {
				if(!property.CanWrite) {
					return false;
				}

				if(t == typeof(int) || t == typeof(uint) || t == typeof(double)) {
					if(property.GetCustomAttribute<MinMaxAttribute>() is MinMaxAttribute minMaxAttribute) {
						if(t == typeof(int)) {
							if(int.TryParse(value, out int result)) {
								if(result >= (int)minMaxAttribute.Min && result <= (int)minMaxAttribute.Max) {
									property.SetValue(instance, result);
								} else {
									return false;
								}
							}
						} else if(t == typeof(uint)) {
							if(uint.TryParse(value, out uint result)) {
								if(result >= (uint)(int)minMaxAttribute.Min && result <= (uint)(int)minMaxAttribute.Max) {
									property.SetValue(instance, result);
								} else {
									return false;
								}
							}
						} else if(t == typeof(double)) {
							if(double.TryParse(value, out double result)) {
								if(result >= (double)minMaxAttribute.Min && result <= (double)minMaxAttribute.Max) {
									property.SetValue(instance, result);
								} else {
									return false;
								}
							}
						}
					}
				} else if(t == typeof(bool)) {
					if(bool.TryParse(value, out bool boolValue)) {
						property.SetValue(instance, boolValue);
					} else {
						return false;
					}
				} else if(t.IsEnum) {
					if(Enum.TryParse(t, value, true, out object? enumValue)) {
						if(property.GetCustomAttribute<ValidValuesAttribute>() is ValidValuesAttribute validValuesAttribute) {
							if(validValuesAttribute.ValidValues.Contains(enumValue)) {
								property.SetValue(instance, enumValue);
							} else {
								return false;
							}
						} else {
							property.SetValue(instance, enumValue);
						}
					} else {
						return false;
					}
				}
			} catch {
				return false;
			}
			return true;
		}

		public static bool ProcessSwitch(string switchArg)
		{
			Regex regex = new Regex("([a-z0-9_A-Z.]+)=([a-z0-9_A-Z.\\-]+)");
			Match match = regex.Match(switchArg);
			if(match.Success) {
				string[] switchPath = match.Groups[1].Value.Split(".");
				string switchValue = match.Groups[2].Value;

				object? cfg = ConfigManager.Config;
				PropertyInfo? property;
				for(int i = 0; i < switchPath.Length; i++) {
					property = cfg.GetType().GetProperty(switchPath[i], BindingFlags.Public | BindingFlags.Instance | BindingFlags.IgnoreCase);
					if(property == null) {
						//Invalid switch name
						return false;
					}

					if(i < switchPath.Length - 1) {
						cfg = property.GetValue(cfg);
						if(cfg == null) {
							//Invalid
							return false;
						}
					} else {
						return ApplySetting(cfg, property, switchValue);
					}
				}
			}

			return false;
		}

		public static void ResetHomeFolder()
		{
			_homeFolder = null;
		}

		public static string HomeFolder
		{
			get
			{
				if(_homeFolder == null) {
					string? overrideFolder = Environment.GetEnvironmentVariable("MESEN2_HOME");
					if(!string.IsNullOrWhiteSpace(overrideFolder)) {
						_homeFolder = overrideFolder;
						Directory.CreateDirectory(_homeFolder);
						EmuApi.WriteLogEntry("[UI] Using MESEN2_HOME override: " + _homeFolder);
						return _homeFolder;
					}

					string portableFolder = DefaultPortableFolder;
					string documentsFolder = DefaultDocumentsFolder;

					List<string> candidates = new();
					string portableConfig = Path.Combine(portableFolder, "settings.json");
					string defaultConfig = Path.Combine(documentsFolder, "settings.json");
					bool ignorePortableConfig = OperatingSystem.IsMacOS() && IsMacAppBundlePath(portableFolder);
					if(ignorePortableConfig && File.Exists(portableConfig)) {
						EmuApi.WriteLogEntry("[UI] Ignoring portable config inside app bundle: " + portableFolder);
					}
					if(File.Exists(portableConfig) && !ignorePortableConfig) {
						candidates.Add(portableFolder);
					}
					if(File.Exists(defaultConfig)) {
						candidates.Add(documentsFolder);
					}

					candidates.AddRange(GetLegacyConfigFolders(documentsFolder, portableFolder));

					string? selectedFolder = SelectBestConfigFolder(candidates);
					if(!string.IsNullOrWhiteSpace(selectedFolder)) {
						_homeFolder = selectedFolder;
						if(!string.Equals(selectedFolder, documentsFolder, StringComparison.OrdinalIgnoreCase) && !string.Equals(selectedFolder, portableFolder, StringComparison.OrdinalIgnoreCase)) {
							EmuApi.WriteLogEntry("[UI] Using legacy config folder: " + _homeFolder);
						} else {
							EmuApi.WriteLogEntry("[UI] Using config folder: " + _homeFolder);
						}
					} else if(File.Exists(portableConfig) && !ignorePortableConfig) {
						_homeFolder = portableFolder;
					} else if(File.Exists(defaultConfig)) {
						_homeFolder = documentsFolder;
					} else {
						_homeFolder = documentsFolder;
					}

					Directory.CreateDirectory(_homeFolder);
					TryMigrateLegacyMesenFolder(_homeFolder, portableFolder, documentsFolder);
				}

				return _homeFolder;
			}
		}

		public static string GetFolder(string defaultFolderName, string? overrideFolder, bool useOverride)
		{
			string folder;
			if(useOverride && overrideFolder != null) {
				folder = overrideFolder;
			} else {
				folder = defaultFolderName;
			}

			try {
				if(!Directory.Exists(folder)) {
					Directory.CreateDirectory(folder);
				}
			} catch {
				//If the folder doesn't exist and we couldn't create it, use the default folder
				EmuApi.WriteLogEntry("[UI] Folder could not be created: " + folder);
				folder = defaultFolderName;
			}
			return folder;
		}

		public static string AviFolder { get { return GetFolder(DefaultAviFolder, Config.Preferences.AviFolder, Config.Preferences.OverrideAviFolder); } }
		public static string MovieFolder { get { return GetFolder(DefaultMovieFolder, Config.Preferences.MovieFolder, Config.Preferences.OverrideMovieFolder); } }
		public static string SaveFolder { get { return GetFolder(DefaultSaveDataFolder, Config.Preferences.SaveDataFolder, Config.Preferences.OverrideSaveDataFolder); } }
		public static string SaveStateFolder { get { return GetFolder(DefaultSaveStateFolder, Config.Preferences.SaveStateFolder, Config.Preferences.OverrideSaveStateFolder); } }
		public static string ScreenshotFolder { get { return GetFolder(DefaultScreenshotFolder, Config.Preferences.ScreenshotFolder, Config.Preferences.OverrideScreenshotFolder); } }
		public static string WaveFolder { get { return GetFolder(DefaultWaveFolder, Config.Preferences.WaveFolder, Config.Preferences.OverrideWaveFolder); } }

		public static string CheatFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Cheats"), null, false); } }
		public static string GameConfigFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "GameConfig"), null, false); } }
		public static string SatellaviewFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Satellaview"), null, false); } }

		public static string DebuggerFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Debugger"), null, false); } }
		public static string FirmwareFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Firmware"), null, false); } }
		public static string BackupFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Backups"), null, false); } }
		public static string TestFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "Tests"), null, false); } }
		public static string HdPackFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "HdPacks"), null, false); } }
		public static string RecentGamesFolder { get { return GetFolder(Path.Combine(ConfigManager.HomeFolder, "RecentGames"), null, false); } }

		public static string ConfigFile
		{
			get
			{
				if(!Directory.Exists(HomeFolder)) {
					Directory.CreateDirectory(HomeFolder);
				}

				return Path.Combine(HomeFolder, "settings.json");
			}
		}

		public static Configuration Config
		{
			get 
			{
				LoadConfig();
				return _config!;
			}
		}

		public static void ResetSettings()
		{
			DefaultKeyMappingType defaultMappings = Config.DefaultKeyMappings;
			_config = Configuration.CreateConfig();
			Config.DefaultKeyMappings = defaultMappings;
			Config.InitializeDefaults();
			Config.ConfigUpgrade = (int)ConfigUpgradeHint.NextValue - 1;
			Config.Save();
			Config.ApplyConfig();
		}

		public static void RestartMesen()
		{
			ProcessModule? mainModule = Process.GetCurrentProcess().MainModule;
			if(mainModule?.FileName == null) {
				return;
			}
			SingleInstance.Instance.Dispose();
			Process.Start(mainModule.FileName);
		}

		private static bool IsMacAppBundlePath(string folder)
		{
			if(!OperatingSystem.IsMacOS() || string.IsNullOrWhiteSpace(folder)) {
				return false;
			}
			string normalized = folder.Replace('\\', '/');
			return normalized.Contains(".app/Contents/", StringComparison.OrdinalIgnoreCase);
		}
	}
}
