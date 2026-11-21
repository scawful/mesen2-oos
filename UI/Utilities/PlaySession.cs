using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Mesen.Utilities
{
	/// <summary>
	/// Shared jump catalog and in-play feedback file. Matches yaze
	/// docs/internal/play_session_contract.md and Android PlaySession.java.
	/// </summary>
	public sealed class PlayJump
	{
		public string CatalogId { get; set; } = "";
		public string SourceId { get; set; } = "";
		public string Kind { get; set; } = "";
		public string Label { get; set; } = "";
		public string Status { get; set; } = "";
		public string StatePath { get; set; } = "";
		public string CheckpointId { get; set; } = "";
		public string Location { get; set; } = "";

		public string Display => string.IsNullOrEmpty(Kind) ? Label : Kind + "  " + Label;

		public override string ToString() => Display;
	}

	public static class PlaySession
	{
		public const string FeedbackSchema = "yaze.review_board_feedback.v2";
		public const string PlaySchema = "oos.play_feedback.v1";
		public const string BoardId = "in-play";
		public const string SourceDesktop = "mesen-desktop";
		public const string SourceAndroid = "mesen-android";
		public const string SourceYaze = "yaze";

		public static IReadOnlyList<PlayJump> LoadJumps(string? oracleRoot)
		{
			List<PlayJump> jumps = new();
			if(string.IsNullOrWhiteSpace(oracleRoot) || !Directory.Exists(oracleRoot)) {
				return jumps;
			}
			string debug = Path.Combine(oracleRoot, "Data", "debug");
			string libraryRoot = "Roms/SaveStates/library";
			string statesPath = Path.Combine(debug, "save_state_library.json");
			if(File.Exists(statesPath)) {
				using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(statesPath));
				JsonElement root = doc.RootElement;
				string declared = Text(root, "library_root");
				if(SafeRelative(declared)) {
					libraryRoot = declared;
				}
				if(root.TryGetProperty("entries", out JsonElement entries) && entries.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement item in entries.EnumerateArray()) {
						AddSavestate(item, libraryRoot, jumps);
					}
				}
			}
			string checkpointsPath = Path.Combine(debug, "checkpoints.json");
			if(File.Exists(checkpointsPath)) {
				using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(checkpointsPath));
				if(doc.RootElement.TryGetProperty("checkpoints", out JsonElement rows) && rows.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement item in rows.EnumerateArray()) {
						AddCheckpoint(item, jumps);
					}
				}
			}
			string warpsPath = Path.Combine(debug, "warps.json");
			if(File.Exists(warpsPath)) {
				using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(warpsPath));
				JsonElement root = doc.RootElement;
				if(root.TryGetProperty("entrances", out JsonElement entrances) && entrances.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement item in entrances.EnumerateArray()) {
						AddWarp(item, "entrance", "id", jumps);
					}
				}
				if(root.TryGetProperty("areas", out JsonElement areas) && areas.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement item in areas.EnumerateArray()) {
						AddWarp(item, "area", "area", jumps);
					}
				}
			}
			return jumps;
		}

		public static string? ResolveStateFile(string? oracleRoot, PlayJump jump)
		{
			if(jump.Kind != "savestate" || string.IsNullOrWhiteSpace(jump.StatePath)) {
				return null;
			}
			if(Path.IsPathRooted(jump.StatePath)) {
				return Path.GetFullPath(jump.StatePath);
			}
			if(string.IsNullOrWhiteSpace(oracleRoot) || !SafeRelative(jump.StatePath.Replace('\\', '/'))) {
				return null;
			}
			return Path.GetFullPath(Path.Combine(oracleRoot, jump.StatePath.Replace('/', Path.DirectorySeparatorChar)));
		}

		public static string ReviewBoardsDirectory(string? oracleRoot)
		{
			string? env = Environment.GetEnvironmentVariable("OOS_REVIEW_BOARDS_DIR")
				?? Environment.GetEnvironmentVariable("YAZE_REVIEW_BOARDS_DIR");
			if(!string.IsNullOrWhiteSpace(env)) {
				return ExpandHome(env);
			}
			if(!string.IsNullOrWhiteSpace(oracleRoot)) {
				return Path.Combine(oracleRoot, ".workspace", "yaze", "review_boards");
			}
			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			return Path.Combine(home, ".yaze", "review_boards");
		}

		public static void AppendFeedback(string reviewBoardsDir, string decision, string category, string comment, string source, PlayJump? jump)
		{
			if(decision != "comment" && decision != "needs_changes" && decision != "looks_good") {
				throw new ArgumentException("Unknown decision");
			}
			if(category != "bug" && category != "review" && category != "general" && category != "layout") {
				throw new ArgumentException("Unknown category");
			}
			if(source != SourceDesktop && source != SourceAndroid && source != SourceYaze) {
				throw new ArgumentException("Unknown feedback source");
			}
			if(comment.Length > 4000) {
				throw new ArgumentException("Comment is too long");
			}
			if(decision != "looks_good" && string.IsNullOrWhiteSpace(comment)) {
				throw new ArgumentException("Write a comment first");
			}
			string folder = Path.Combine(reviewBoardsDir, BoardId);
			Directory.CreateDirectory(folder);
			string target = Path.Combine(folder, "feedback-" + BoardId + ".json");
			if(File.Exists(target) && new FileInfo(target).Attributes.HasFlag(FileAttributes.ReparsePoint)) {
				throw new IOException("Feedback file is a symlink");
			}

			JsonObject root;
			JsonArray history;
			if(File.Exists(target)) {
				JsonNode? parsed = JsonNode.Parse(File.ReadAllText(target));
				root = parsed as JsonObject ?? throw new IOException("Existing in-play feedback is not an object");
				string? schema = root["schema"]?.GetValue<string>();
				if(!string.IsNullOrEmpty(schema) && schema != "yaze.review_board_feedback.v1" && schema != FeedbackSchema) {
					throw new IOException("Existing feedback schema is unknown");
				}
				if(root["id"]?.GetValue<string>() != BoardId) {
					throw new IOException("Existing feedback id is not in-play");
				}
				history = root["history"] as JsonArray ?? throw new IOException("Existing feedback has no history");
			} else {
				root = new JsonObject();
				history = new JsonArray();
				root["history"] = history;
			}

			JsonObject play = new() { ["source"] = source };
			if(jump != null) {
				play["jump"] = new JsonObject {
					["kind"] = jump.Kind,
					["id"] = jump.SourceId,
					["label"] = jump.Label
				};
				if(jump.Kind == "savestate" && !string.IsNullOrEmpty(jump.StatePath)) {
					play["savestate"] = jump.StatePath;
				}
			}
			JsonObject entry = new() {
				["at"] = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ"),
				["decision"] = decision,
				["category"] = category,
				["comment"] = comment,
				["answers"] = new JsonObject(),
				["play"] = play
			};
			history.Add(entry);

			string status = "commented";
			for(int i = history.Count - 1; i >= 0; i--) {
				string? prior = history[i]?["decision"]?.GetValue<string>();
				if(prior == "looks_good" || prior == "needs_changes") {
					status = prior;
					break;
				}
			}
			root["schema"] = FeedbackSchema;
			root["play_schema"] = PlaySchema;
			root["id"] = BoardId;
			root["status"] = status;
			if(root["answers"] is not JsonObject) {
				root["answers"] = new JsonObject();
			}

			string temp = target + ".tmp-" + Guid.NewGuid().ToString("N");
			File.WriteAllText(temp, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }) + "\n");
			File.Move(temp, target, true);
		}

		private static void AddSavestate(JsonElement item, string libraryRoot, List<PlayJump> jumps)
		{
			if(item.ValueKind != JsonValueKind.Object) return;
			string sourceId = Text(item, "id");
			if(string.IsNullOrEmpty(sourceId)) return;
			string label = Text(item, "label");
			string location = "";
			if(item.TryGetProperty("meta", out JsonElement meta) && meta.ValueKind == JsonValueKind.Object) {
				if(string.IsNullOrEmpty(label)) label = Text(meta, "label");
				location = Text(meta, "location");
			}
			if(item.TryGetProperty("metadata", out JsonElement metadata) && metadata.ValueKind == JsonValueKind.Object && string.IsNullOrEmpty(location)) {
				location = Text(metadata, "location");
			}
			if(string.IsNullOrEmpty(label)) label = Text(item, "description");
			if(string.IsNullOrEmpty(label)) label = sourceId;

			string raw = Text(item, "state_path").Replace('\\', '/');
			if(string.IsNullOrEmpty(raw)) {
				raw = Text(item, "path").Replace('\\', '/');
				if(!string.IsNullOrEmpty(raw) && !raw.Contains('/') && !(raw.Length > 1 && raw[1] == ':')) {
					raw = libraryRoot.TrimEnd('/') + "/" + raw;
				}
			}
			bool absolute = !string.IsNullOrEmpty(raw) && (raw[0] == '/' || (raw.Length > 2 && raw[1] == ':'));
			if(!string.IsNullOrEmpty(raw) && !absolute && !SafeRelative(raw)) {
				raw = "";
			}

			jumps.Add(new PlayJump {
				CatalogId = "savestate:" + sourceId,
				SourceId = sourceId,
				Kind = "savestate",
				Label = label,
				Status = string.IsNullOrEmpty(Text(item, "status")) ? "draft" : Text(item, "status"),
				StatePath = raw,
				Location = location
			});
		}

		private static void AddCheckpoint(JsonElement item, List<PlayJump> jumps)
		{
			if(item.ValueKind != JsonValueKind.Object) return;
			string sourceId = Text(item, "id");
			if(string.IsNullOrEmpty(sourceId)) return;
			string location = "";
			if(item.TryGetProperty("location", out JsonElement loc) && loc.ValueKind == JsonValueKind.Object) {
				string entrance = Text(loc, "entrance");
				string area = Text(loc, "area");
				if(!string.IsNullOrEmpty(entrance)) location = "entrance " + entrance;
				else if(!string.IsNullOrEmpty(area)) location = "area " + area;
			}
			string label = Text(item, "label");
			jumps.Add(new PlayJump {
				CatalogId = "checkpoint:" + sourceId,
				SourceId = sourceId,
				Kind = "checkpoint",
				Label = string.IsNullOrEmpty(label) ? sourceId : label,
				Status = "story",
				CheckpointId = sourceId,
				Location = location
			});
		}

		private static void AddWarp(JsonElement item, string group, string idKey, List<PlayJump> jumps)
		{
			if(item.ValueKind != JsonValueKind.Object) return;
			string sourceId = Text(item, idKey);
			if(string.IsNullOrEmpty(sourceId)) return;
			string label = Text(item, "label");
			if(string.IsNullOrEmpty(label)) label = Text(item, "name");
			if(string.IsNullOrEmpty(label)) label = sourceId;
			string location = Text(item, "landing");
			if(string.IsNullOrEmpty(location)) location = Text(item, "area_name");
			jumps.Add(new PlayJump {
				CatalogId = "warp-" + group + ":" + sourceId,
				SourceId = sourceId,
				Kind = "warp-" + group,
				Label = label,
				Status = "warp",
				Location = location
			});
		}

		private static string Text(JsonElement obj, string name)
		{
			if(!obj.TryGetProperty(name, out JsonElement value)) return "";
			return value.ValueKind switch {
				JsonValueKind.String => value.GetString() ?? "",
				JsonValueKind.Number => value.GetRawText(),
				_ => ""
			};
		}

		private static bool SafeRelative(string text)
		{
			if(string.IsNullOrEmpty(text) || text.Length > 512 || text.Contains('\\')) return false;
			if(text[0] == '/' || (text.Length > 1 && text[1] == ':')) return false;
			foreach(string part in text.Split('/')) {
				if(part.Length == 0 || part == "." || part == "..") return false;
			}
			return true;
		}

		private static string ExpandHome(string path)
		{
			if(path.StartsWith("~", StringComparison.Ordinal)) {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return Path.Combine(home, path.TrimStart('~').TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
			}
			return path;
		}
	}
}
