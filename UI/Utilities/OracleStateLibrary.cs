using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using Mesen.Config;
using Mesen.Interop;

namespace Mesen.Utilities
{
	public class OracleStateEntry
	{
		[JsonPropertyName("id")]
		public string Id { get; set; } = "";

		[JsonPropertyName("path")]
		public string Path { get; set; } = "";

		[JsonPropertyName("label")]
		public string Label { get; set; } = "";

		[JsonPropertyName("created_at")]
		public long CreatedAt { get; set; }

		[JsonPropertyName("tags")]
		public List<string> Tags { get; set; } = new();

		[JsonPropertyName("metadata")]
		public Dictionary<string, object> Metadata { get; set; } = new();
	}

	public class OracleStateManifest
	{
		[JsonPropertyName("version")]
		public int Version { get; set; } = 1;

		[JsonPropertyName("entries")]
		public List<OracleStateEntry> Entries { get; set; } = new();

		[JsonPropertyName("sets")]
		public List<object> Sets { get; set; } = new();
	}

	public static class OracleStateLibrary
	{
		private static string GetLibraryRoot()
		{
			// Prefer the Oracle-of-Secrets save state library if it exists.
			// Fallback to the standard Mesen SaveStates folder otherwise.
			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string path = Path.Combine(home, "src", "hobby", "oracle-of-secrets", "Roms", "savestates");
			
			if (!Directory.Exists(path)) {
				// Fallback to standard Mesen SaveStates folder if library doesn't exist
				path = ConfigManager.SaveStateFolder;
			}
			
			return path;
		}

		public static string GetLibraryRootPath()
		{
			return GetLibraryRoot();
		}

		private static string GetManifestPath()
		{
			return Path.Combine(GetLibraryRoot(), "manifest.json");
		}

		public static OracleStateManifest GetManifest()
		{
			string path = GetManifestPath();
			if (File.Exists(path)) {
				try {
					string json = File.ReadAllText(path);
					return JsonSerializer.Deserialize<OracleStateManifest>(json) ?? new OracleStateManifest();
				} catch {
					// Ignore parse errors
				}
			}
			return new OracleStateManifest();
		}

		public static void SaveManifest(OracleStateManifest manifest)
		{
			string path = GetManifestPath();
			string dir = Path.GetDirectoryName(path)!;
			if (!Directory.Exists(dir)) {
				Directory.CreateDirectory(dir);
			}
			
			string json = JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true });
			File.WriteAllText(path, json);
		}

		public static string GetFullPath(OracleStateEntry entry)
		{
			string root = GetLibraryRoot();
			return Path.Combine(root, entry.Path);
		}

		public static void DeleteState(string stateId)
		{
			var manifest = GetManifest();
			var entry = manifest.Entries.FirstOrDefault(e => e.Id == stateId);
			if (entry != null) {
				string fullPath = GetFullPath(entry);
				if (File.Exists(fullPath)) {
					File.Delete(fullPath);
				}
				manifest.Entries.Remove(entry);
				SaveManifest(manifest);
			}
		}

		public static string SaveLabeledState(string label, List<string>? tags = null)
		{
			long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			string cleanLabel = new string(label.Select(c => char.IsLetterOrDigit(c) ? c : '_').ToArray());
			string filename = $"oos_{timestamp}_{cleanLabel}.mss";
			string libraryRoot = GetLibraryRoot();
			string fullPath = Path.Combine(libraryRoot, filename);

			// Save via EmuApi
			EmuApi.SaveStateFile(fullPath);

			// Capture metadata (simplified for now, ideally read from RAM directly or via interop)
			// For now we'll just add basic info
			var entry = new OracleStateEntry {
				Id = $"{timestamp}_{cleanLabel}",
				Path = filename,
				Label = label,
				CreatedAt = timestamp,
				Tags = tags ?? new List<string>(),
				Metadata = new Dictionary<string, object> {
					{ "rom", EmuApi.GetRomInfo().GetRomName() }
				}
			};

			var manifest = GetManifest();
			manifest.Entries.Insert(0, entry);
			SaveManifest(manifest);

			return entry.Id;
		}
	}
}
