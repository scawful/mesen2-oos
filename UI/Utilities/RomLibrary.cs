using Mesen.Config;
using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Mesen.Utilities
{
	public sealed class LibraryRom
	{
		public string Title { get; init; } = "";
		public string Group { get; init; } = "";
		public string RomPath { get; init; } = "";
		public string Folder { get; init; } = "";
		public string Subtitle { get; init; } = "";
		public string Notes { get; init; } = "";
		public string Badge { get; init; } = "";
		public bool IsOracle { get; init; }
		public string? ShowcaseSave { get; init; }
		public DateTime Date { get; init; }
		public int BuildNumber { get; init; } = -1;
		public string Display => string.IsNullOrEmpty(Badge) ? Title : $"{Title}  ·  {Badge}";

		private Avalonia.Media.Imaging.Bitmap? _preview;
		private bool _previewLoaded;
		public Avalonia.Media.Imaging.Bitmap? Preview
		{
			get
			{
				if(!_previewLoaded) {
					_previewLoaded = true;
					_preview = LibraryPreviews.Load(LibraryPreviews.PathFor(RomPath));
				}
				return _preview;
			}
		}
		public bool HasPreview => Preview != null;
	}

	public sealed class LibrarySave
	{
		public string Label { get; init; } = "";
		public string Detail { get; init; } = "";
		/// <summary>File copied over the game's save before loading. Null = keep the current save.</summary>
		public string? SourcePath { get; init; }
		public bool NewGame { get; init; }
		public string? PreviewPath { get; init; }

		private Avalonia.Media.Imaging.Bitmap? _preview;
		private bool _previewLoaded;
		public Avalonia.Media.Imaging.Bitmap? Preview
		{
			get
			{
				if(!_previewLoaded) {
					_previewLoaded = true;
					_preview = LibraryPreviews.Load(PreviewPath);
				}
				return _preview;
			}
		}
	}

	/// <summary>
	/// ROM library for the start screen: Oracle DevBuild folders (version, commit, change notes,
	/// showcase save), loose ROMs in the library folders, and recent files. Loading a ROM with a
	/// chosen save backs up the current battery file to Saves/SrmBackups first.
	/// </summary>
	public static class RomLibrary
	{
		private static readonly string[] RomExtensions = { ".sfc", ".smc" };
		private static readonly Regex DatedFolder = new(@"(\d{8})(?!.*\d{8})");
		private static readonly Regex BuildTag = new(@"-b(\d+)(?:\.(\d+))?");

		private static string Home => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

		public static List<string> Folders
		{
			get
			{
				List<string> configured = ConfigManager.Config.Oracle.LibraryFolders
					.Where(f => !string.IsNullOrWhiteSpace(f)).Select(ExpandHome).ToList();
				if(configured.Count > 0) {
					return configured;
				}
				return new List<string> { Path.Combine(Home, "Zelda", "DevBuilds"), Path.Combine(Home, "Zelda"), Path.Combine(Home, "src", "docs", "zelda") };
			}
		}

		public static string StopSavesFolder => ExpandHome(string.IsNullOrWhiteSpace(ConfigManager.Config.Oracle.StopSavesFolder)
			? "~/.context/projects/oracle-of-secrets/scratchpad/handoffs/ai_playtester_design_2026-11-24/stop-saves-b72"
			: ConfigManager.Config.Oracle.StopSavesFolder);

		private static string ExpandHome(string path) => path.StartsWith("~/") ? Path.Combine(Home, path.Substring(2)) : path;

		public static List<LibraryRom> Scan()
		{
			Dictionary<string, LibraryRom> byPath = new(StringComparer.Ordinal);
			string? mainTest = ReadMainTestRom();

			foreach(string folder in Folders) {
				if(!Directory.Exists(folder)) {
					continue;
				}
				foreach(string file in SafeFiles(folder)) {
					Add(byPath, LooseRom(file, mainTest));
				}
				foreach(string dir in SafeDirs(folder)) {
					string? rom = FindBuildRom(dir);
					if(rom != null) {
						Add(byPath, BuildRom(dir, rom, mainTest));
					}
				}
			}

			foreach(RecentItem item in ConfigManager.Config.RecentFiles.Items) {
				string path = item.RomFile.Path;
				if(item.RomFile.Compressed || !File.Exists(path) || byPath.ContainsKey(path)) {
					continue;
				}
				Add(byPath, LooseRom(path, mainTest) is LibraryRom r ? new LibraryRom {
					Title = r.Title, Group = "Recent", RomPath = r.RomPath, Folder = r.Folder,
					Subtitle = r.Subtitle, Badge = r.Badge, IsOracle = r.IsOracle, Date = r.Date
				} : null);
			}

			string[] groupOrder = { "Oracle builds", "Other builds", "ROMs", "Recent" };
			return byPath.Values
				.OrderBy(r => Array.IndexOf(groupOrder, r.Group))
				.ThenByDescending(r => r.Badge.Contains("Main test"))
				.ThenByDescending(r => r.BuildNumber)
				.ThenByDescending(r => r.Date)
				.ToList();
		}

		private static void Add(Dictionary<string, LibraryRom> map, LibraryRom? rom)
		{
			if(rom != null && !map.ContainsKey(rom.RomPath)) {
				map[rom.RomPath] = rom;
			}
		}

		private static IEnumerable<string> SafeFiles(string folder)
		{
			try {
				return Directory.EnumerateFiles(folder).Where(f => RomExtensions.Contains(Path.GetExtension(f).ToLowerInvariant())).ToList();
			} catch {
				return Array.Empty<string>();
			}
		}

		private static IEnumerable<string> SafeDirs(string folder)
		{
			try {
				return Directory.EnumerateDirectories(folder).Where(d => !Path.GetFileName(d).StartsWith(".")).ToList();
			} catch {
				return Array.Empty<string>();
			}
		}

		private static string? FindBuildRom(string dir)
		{
			foreach(string candidate in new[] { dir, Path.Combine(dir, "Roms") }) {
				string preferred = Path.Combine(candidate, "oos168x.sfc");
				if(File.Exists(preferred)) {
					return preferred;
				}
			}
			//A folder holding exactly one ROM is a build folder too
			List<string> roms = SafeFiles(dir).ToList();
			return roms.Count == 1 ? roms[0] : null;
		}

		private static LibraryRom? LooseRom(string file, string? mainTest)
		{
			FileInfo info = new(file);
			return new LibraryRom {
				Title = Path.GetFileNameWithoutExtension(file),
				Group = "ROMs",
				RomPath = file,
				Folder = info.DirectoryName ?? "",
				Subtitle = $"{info.LastWriteTime:MMM d, yyyy}  ·  {info.Length / (1024 * 1024.0):0.#} MB  ·  {Path.GetFileName(info.DirectoryName)}",
				Badge = file == mainTest ? "Main test" : "",
				Date = info.LastWriteTime
			};
		}

		private static LibraryRom BuildRom(string dir, string rom, string? mainTest)
		{
			string name = Path.GetFileName(dir);
			string version = "";
			int build = -1;
			try {
				string versionFile = Path.Combine(dir, "version.json");
				if(File.Exists(versionFile) && JsonNode.Parse(File.ReadAllText(versionFile)) is JsonObject v) {
					build = v["build"]?.GetValue<int>() ?? -1;
					version = $"v{v["version"]}-b{build}";
				}
			} catch {
				version = "";
			}
			Match tag = BuildTag.Match(name);
			if(build < 0 && tag.Success) {
				build = int.Parse(tag.Groups[1].Value, CultureInfo.InvariantCulture);
			}

			DateTime date = new FileInfo(rom).LastWriteTime;
			Match dated = DatedFolder.Match(name);
			if(dated.Success && DateTime.TryParseExact(dated.Value, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out DateTime parsed)) {
				date = parsed;
			}

			string commit = "";
			try {
				string commitFile = Path.Combine(dir, "SOURCE_COMMIT.txt");
				if(File.Exists(commitFile)) {
					commit = File.ReadAllText(commitFile).Trim();
					commit = commit.Length > 8 ? commit.Substring(0, 8) : commit;
				}
			} catch {
				commit = "";
			}

			bool oracle = name.Contains("oracle", StringComparison.OrdinalIgnoreCase) || Path.GetFileName(rom) == "oos168x.sfc";
			string title = !string.IsNullOrEmpty(version) ? version : name;
			List<string> sub = new() { date.ToString("MMM d, yyyy") };
			if(!string.IsNullOrEmpty(commit)) {
				sub.Add("commit " + commit);
			}
			if(!string.IsNullOrEmpty(version)) {
				sub.Add(name);
			}

			return new LibraryRom {
				Title = title,
				Group = oracle ? "Oracle builds" : "Other builds",
				RomPath = rom,
				Folder = dir,
				Subtitle = string.Join("  ·  ", sub),
				Notes = ReadNotes(dir),
				Badge = rom == mainTest ? "Main test" : "",
				IsOracle = oracle,
				ShowcaseSave = Directory.EnumerateFiles(dir, "*showcase*.srm").OrderBy(f => f.Length).FirstOrDefault(),
				Date = date,
				BuildNumber = build
			};
		}

		/// <summary>Headings of the README's "What Changed" section, else its first paragraph.</summary>
		private static string ReadNotes(string dir)
		{
			string readme = Path.Combine(dir, "README.md");
			if(!File.Exists(readme)) {
				return "";
			}
			try {
				string[] lines = File.ReadAllLines(readme);
				int start = Array.FindIndex(lines, l => l.StartsWith("## What Changed", StringComparison.OrdinalIgnoreCase));
				List<string> notes = new();
				if(start >= 0) {
					for(int i = start + 1; i < lines.Length && notes.Count < 12; i++) {
						string l = lines[i];
						if(l.StartsWith("## ")) {
							break;
						}
						if(l.StartsWith("### ")) {
							notes.Add(StripMarkdown(l.Substring(4)));
						} else if(l.StartsWith("- **")) {
							int end = l.IndexOf("**", 4);
							if(end > 4) {
								notes.Add("   " + StripMarkdown(l.Substring(4, end - 4)).TrimEnd(':'));
							}
						}
					}
				}
				if(notes.Count == 0) {
					notes.AddRange(lines.SkipWhile(l => l.StartsWith("#") || string.IsNullOrWhiteSpace(l))
						.TakeWhile(l => !string.IsNullOrWhiteSpace(l)).Take(6).Select(StripMarkdown));
				}
				return string.Join("\n", notes);
			} catch {
				return "";
			}
		}

		private static string StripMarkdown(string s) => Regex.Replace(s, @"\*\*|`", "").Trim();

		private static string? ReadMainTestRom()
		{
			try {
				string barista = Path.Combine(Home, ".config", "barista", "oracle.json");
				if(File.Exists(barista) && JsonNode.Parse(File.ReadAllText(barista)) is JsonObject o) {
					return o["main_test_rom"]?.GetValue<string>();
				}
			} catch {
			}
			return null;
		}

		public static List<LibrarySave> SavesFor(LibraryRom rom)
		{
			List<LibrarySave> saves = new() {
				new LibrarySave { Label = "Continue", Detail = "Keep the current save for this ROM" }
			};
			if(rom.ShowcaseSave != null) {
				saves.Add(new LibrarySave { Label = "Showcase save", Detail = Path.GetFileName(rom.ShowcaseSave), SourcePath = rom.ShowcaseSave });
			}
			if(rom.IsOracle) {
				foreach(LibrarySave stop in StopSaves()) {
					saves.Add(stop);
				}
			}
			saves.Add(new LibrarySave { Label = "New game", Detail = "Start with no save (the current one is backed up)", NewGame = true });
			return saves;
		}

		private static IEnumerable<LibrarySave> StopSaves()
		{
			string folder = StopSavesFolder;
			string manifest = Path.Combine(folder, "manifest.json");
			if(!File.Exists(manifest)) {
				yield break;
			}
			JsonArray? stops = null;
			try {
				stops = (JsonNode.Parse(File.ReadAllText(manifest)) as JsonObject)?["stops"] as JsonArray;
			} catch {
				stops = null;
			}
			if(stops == null) {
				yield break;
			}
			foreach(JsonNode? node in stops) {
				if(node is not JsonObject s) {
					continue;
				}
				string file = Path.Combine(folder, s["file"]?.ToString() ?? "");
				if(!File.Exists(file)) {
					continue;
				}
				string hearts = s["hearts"] != null ? $"{s["hearts"]} hearts" : "";
				yield return new LibrarySave {
					PreviewPath = Path.Combine(folder, $"stop_{s["stop"]?.ToString().ToLowerInvariant()}_entry.png"),
					Label = $"Start at {s["stop"]} {s["label"]}",
					Detail = string.Join("  ·  ", new[] { hearts, "starts at Link's House with the items for this stop" }.Where(x => x.Length > 0)),
					SourcePath = file
				};
			}
		}

		/// <summary>Battery file the core will read for this ROM (honors Oracle's persistent save name).</summary>
		public static string BatteryPathFor(LibraryRom rom)
		{
			string name = Path.GetFileNameWithoutExtension(rom.RomPath);
			string persistent = ConfigManager.Config.Oracle.PersistentSaveName.Trim();
			if(rom.IsOracle && persistent.Length > 0) {
				name = persistent;
			}
			return Path.Combine(ConfigManager.SaveFolder, name + ".srm");
		}

		/// <summary>Writes the chosen save into place. Returns the backup path, or null when nothing was replaced.</summary>
		public static string? ApplySave(LibraryRom rom, LibrarySave save)
		{
			if(save.SourcePath == null && !save.NewGame) {
				return null;
			}
			string battery = BatteryPathFor(rom);
			string? backup = null;
			if(File.Exists(battery)) {
				string folder = Path.Combine(ConfigManager.SaveFolder, "SrmBackups");
				Directory.CreateDirectory(folder);
				backup = Path.Combine(folder, $"{Path.GetFileNameWithoutExtension(battery)}_{DateTime.Now:yyyyMMdd-HHmmss}_library.srm");
				for(int i = 2; File.Exists(backup); i++) {
					backup = Path.Combine(folder, $"{Path.GetFileNameWithoutExtension(battery)}_{DateTime.Now:yyyyMMdd-HHmmss}_library-{i}.srm");
				}
				File.Copy(battery, backup);
			}
			if(save.NewGame) {
				File.Delete(battery);
			} else {
				Directory.CreateDirectory(Path.GetDirectoryName(battery)!);
				File.Copy(save.SourcePath!, battery, true);
			}
			return backup;
		}
	}
}
