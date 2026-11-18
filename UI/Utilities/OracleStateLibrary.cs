using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using Mesen.Config;

namespace Mesen.Utilities
{
	public class OracleStateEntry
	{
		public string Id { get; set; } = "";
		public string Path { get; set; } = "";
		public string Label { get; set; } = "";
		public long CreatedAt { get; set; }
		public List<string> Tags { get; set; } = new();

		public string CreatedAtDisplay
		{
			get
			{
				if(CreatedAt <= 0) {
					return "Unknown";
				}
				return DateTimeOffset.FromUnixTimeSeconds(CreatedAt)
					.ToLocalTime()
					.ToString("yyyy-MM-dd HH:mm:ss");
			}
		}
	}

	public class OracleStateManifest
	{
		public int Version { get; set; } = 1;
		public List<OracleStateEntry> Entries { get; set; } = new();
	}

	/// <summary>
	/// Reflection-free reader/writer for Oracle's save-state library
	/// (Data/debug/save_state_library.json + Roms/SaveStates/library).
	/// </summary>
	public static class OracleStateLibrary
	{
		private static readonly JsonSerializerOptions JsonOptions = new() {
			WriteIndented = true,
			Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
		};

		private static string GetLibraryRoot()
		{
			string? envRoot = Environment.GetEnvironmentVariable("MESEN2_STATE_LIBRARY_ROOT")
				?? Environment.GetEnvironmentVariable("OOS_STATE_LIBRARY_ROOT");
			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					return expanded;
				}
			}

			string? projectRoot = AgentLauncher.ResolveProjectRoot();
			if(!string.IsNullOrWhiteSpace(projectRoot) && Directory.Exists(projectRoot)) {
				string[] candidates = {
					System.IO.Path.Combine(projectRoot, "Roms", "SaveStates", "library"),
					System.IO.Path.Combine(projectRoot, "Roms", "savestates"),
					System.IO.Path.Combine(projectRoot, "Savestates")
				};
				foreach(string candidate in candidates) {
					if(Directory.Exists(candidate)) {
						return candidate;
					}
				}
				return candidates[0];
			}

			return ConfigManager.SaveStateFolder;
		}

		private static string ExpandHome(string path)
		{
			if(path.StartsWith("~", StringComparison.Ordinal)) {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return System.IO.Path.Combine(home, path.TrimStart('~').TrimStart(System.IO.Path.DirectorySeparatorChar, System.IO.Path.AltDirectorySeparatorChar));
			}
			return path;
		}

		public static string GetLibraryRootPath()
		{
			return GetLibraryRoot();
		}

		public static string GetManifestPath()
		{
			string? projectRoot = AgentLauncher.ResolveProjectRoot();
			if(!string.IsNullOrWhiteSpace(projectRoot) && Directory.Exists(projectRoot)) {
				string dataDebug = System.IO.Path.Combine(projectRoot, "Data", "debug", "save_state_library.json");
				if(File.Exists(dataDebug)) {
					return dataDebug;
				}
				string legacyDocs = System.IO.Path.Combine(projectRoot, "Docs", "Debugging", "Testing", "save_state_library.json");
				if(File.Exists(legacyDocs)) {
					return legacyDocs;
				}
				string dataDebugDir = System.IO.Path.Combine(projectRoot, "Data", "debug");
				if(Directory.Exists(dataDebugDir)) {
					return dataDebug;
				}
			}
			return System.IO.Path.Combine(GetLibraryRoot(), "manifest.json");
		}

		public static OracleStateManifest GetManifest()
		{
			OracleStateManifest manifest = new OracleStateManifest();
			string path = GetManifestPath();
			if(!File.Exists(path)) {
				return manifest;
			}

			try {
				using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path));
				JsonElement root = doc.RootElement;
				if(root.TryGetProperty("version", out JsonElement verElem) && verElem.TryGetInt32(out int ver)) {
					manifest.Version = ver;
				}
				if(root.TryGetProperty("entries", out JsonElement entriesElem) && entriesElem.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement item in entriesElem.EnumerateArray()) {
						if(item.ValueKind != JsonValueKind.Object) {
							continue;
						}
						OracleStateEntry entry = new OracleStateEntry() {
							Id = GetString(item, "id") ?? "",
							Path = GetString(item, "path") ?? "",
							Label = GetString(item, "label") ?? "",
							CreatedAt = item.TryGetProperty("created_at", out JsonElement ts) && ts.TryGetInt64(out long created) ? created : 0
						};
						if(item.TryGetProperty("tags", out JsonElement tagsElem) && tagsElem.ValueKind == JsonValueKind.Array) {
							foreach(JsonElement tag in tagsElem.EnumerateArray()) {
								if(tag.ValueKind == JsonValueKind.String && tag.GetString() is string tagStr) {
									entry.Tags.Add(tagStr);
								}
							}
						}
						manifest.Entries.Add(entry);
					}
				}
			} catch {
				// Ignore parse errors and return empty manifest
			}
			return manifest;
		}

		public static string GetFullPath(OracleStateEntry entry)
		{
			if(System.IO.Path.IsPathRooted(entry.Path)) {
				return entry.Path;
			}
			return System.IO.Path.Combine(GetLibraryRoot(), entry.Path);
		}

		public static void DeleteState(string stateId)
		{
			string manifestPath = GetManifestPath();
			if(!File.Exists(manifestPath)) {
				return;
			}

			try {
				JsonNode? rootNode = JsonNode.Parse(File.ReadAllText(manifestPath));
				if(rootNode is not JsonObject rootObj || rootObj["entries"] is not JsonArray entries) {
					return;
				}

				for(int i = 0; i < entries.Count; i++) {
					if(entries[i] is JsonObject entryObj
						&& string.Equals(entryObj["id"]?.GetValue<string>(), stateId, StringComparison.Ordinal)) {
						string? relPath = entryObj["path"]?.GetValue<string>();
						if(!string.IsNullOrWhiteSpace(relPath)) {
							string fullPath = System.IO.Path.IsPathRooted(relPath)
								? relPath
								: System.IO.Path.Combine(GetLibraryRoot(), relPath);
							TryDeleteFile(fullPath);
							TryDeleteFile(System.IO.Path.ChangeExtension(fullPath, ".png"));
							TryDeleteFile(fullPath + ".meta.json");
						}
						entries.RemoveAt(i);
						File.WriteAllText(manifestPath, rootObj.ToJsonString(JsonOptions) + "\n");
						return;
					}
				}
			} catch {
				// Ignore deletion failures
			}
		}

		public static string SaveLabeledState(string label, List<string>? tags = null)
		{
			IReadOnlyList<string> tagList = tags ?? (IReadOnlyList<string>)Array.Empty<string>();
			OracleActionResult result = OracleDebugActions.CaptureState(label, tagList, "", out string capturedId);
			if(!result.Success && string.IsNullOrEmpty(capturedId)) {
				throw new IOException(result.Message);
			}
			return capturedId;
		}

		private static string? GetString(JsonElement obj, string prop)
		{
			return obj.TryGetProperty(prop, out JsonElement val) && val.ValueKind == JsonValueKind.String
				? val.GetString()
				: null;
		}

		private static void TryDeleteFile(string path)
		{
			try {
				if(File.Exists(path)) {
					File.Delete(path);
				}
			} catch {
				// Ignore companion file delete errors
			}
		}
	}
}
