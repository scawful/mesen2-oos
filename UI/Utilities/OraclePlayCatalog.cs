using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.Json;

namespace Mesen.Utilities
{
	/// <summary>
	/// Story checkpoints and warps from Oracle Data/debug/{checkpoints,warps}.json.
	/// The same rules are implemented in yaze play_catalog and Android CheckpointData.
	/// House-cache entrances and special areas ($80+) are planned as unsupported.
	/// </summary>
	public static class OraclePlayCatalog
	{
		public const string CheckpointsSchema = "oracle-debug-checkpoints/1";
		public const string WarpsSchema = "oracle-debug-warps/1";
		public const string SourceDesktop = "mesen-desktop";

		public sealed class Write
		{
			public uint Addr { get; init; }
			public string Op { get; init; } = "value";
			public int Operand { get; init; }
			public string Name { get; init; } = "";
		}

		public sealed class Jump
		{
			public string Kind { get; set; } = "";
			public string Id { get; set; } = "";
			public string Label { get; set; } = "";
			public string Path { get; set; } = "";
			public List<string> Chain { get; set; } = new();
			public List<Write> StoryWrites { get; set; } = new();
			public List<Write> WarpPokes { get; set; } = new();
			public string Method { get; set; } = "";
			public string Reason { get; set; } = "";
			public bool Todo { get; set; }
			public bool Unsupported => Method == "unsupported" || Todo;
			public string Display => string.IsNullOrWhiteSpace(Label) ? Id : Label;
		}

		public sealed class Catalog
		{
			public List<Jump> Checkpoints { get; set; } = new();
			public List<Jump> Entrances { get; set; } = new();
			public List<Jump> Areas { get; set; } = new();
			public string Error { get; set; } = "";
			public List<string> Warnings { get; set; } = new();
		}

		public static int ApplyWrite(int current, string op, int operand)
		{
			int bits = operand & 0xFF;
			int value = current & 0xFF;
			if(op == "value") return bits;
			if(op == "set_bits") return value | bits;
			if(op == "clear_bits") return value & ~bits & 0xFF;
			return -1;
		}

		public static ushort StampSaveChecksum(byte[] block)
		{
			if(block == null || block.Length < 0x500) return 0;
			int total = 0;
			for(int i = 0; i < 0x4FE; i += 2) {
				total = (total + block[i] + (block[i + 1] << 8)) & 0xFFFF;
			}
			ushort inverse = (ushort)((0x5A5A - total) & 0xFFFF);
			block[0x4FE] = (byte)(inverse & 0xFF);
			block[0x4FF] = (byte)(inverse >> 8);
			return inverse;
		}

		public static Catalog Parse(string checkpointsJson, string warpsJson)
		{
			try {
				using JsonDocument checkpoints = JsonDocument.Parse(checkpointsJson);
				using JsonDocument warps = JsonDocument.Parse(warpsJson);
				if(checkpoints.RootElement.ValueKind != JsonValueKind.Object || warps.RootElement.ValueKind != JsonValueKind.Object) {
					return Failed("checkpoints.json or warps.json is not a JSON object");
				}
				string checkpointSchema = StringOf(checkpoints.RootElement, "schema");
				if(checkpointSchema.Length > 0 && checkpointSchema != CheckpointsSchema) {
					return Failed("unexpected checkpoints schema " + checkpointSchema);
				}
				string warpsSchema = StringOf(warps.RootElement, "schema");
				if(warpsSchema.Length > 0 && warpsSchema != WarpsSchema) {
					return Failed("unexpected warps schema " + warpsSchema);
				}

				Catalog catalog = new();
				JsonElement entrances = ArrayOf(warps.RootElement, "entrances");
				JsonElement areas = ArrayOf(warps.RootElement, "areas");
				if(entrances.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement row in entrances.EnumerateArray()) {
						Jump jump = EntranceJump(row);
						if(jump.Id.Length > 0) catalog.Entrances.Add(jump);
					}
				}
				if(areas.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement row in areas.EnumerateArray()) {
						Jump jump = AreaJump(row);
						if(jump.Id.Length > 0) catalog.Areas.Add(jump);
					}
				}

				Dictionary<string, JsonElement> byId = new(StringComparer.Ordinal);
				foreach(JsonElement row in ArrayOf(checkpoints.RootElement, "checkpoints").EnumerateArray()) {
					string id = StringOf(row, "id");
					if(id.Length > 0) byId[id] = row.Clone();
				}
				foreach(KeyValuePair<string, JsonElement> item in byId) {
					List<string> chainIds = new();
					List<JsonElement> chain = new();
					string cursor = item.Key;
					bool failed = false;
					while(cursor.Length > 0) {
						if(chainIds.Contains(cursor)) {
							catalog.Warnings.Add("checkpoint chain loops at " + cursor);
							failed = true;
							break;
						}
						if(!byId.TryGetValue(cursor, out JsonElement link)) {
							catalog.Warnings.Add("checkpoint " + item.Key + " is after unknown " + cursor);
							failed = true;
							break;
						}
						chainIds.Add(cursor);
						chain.Add(link);
						cursor = StringOf(link, "after");
					}
					if(failed || chain.Count == 0) continue;

					Jump jump = new() {
						Kind = "checkpoint",
						Id = item.Key,
						Label = FirstNonEmpty(StringOf(chain[0], "label"), item.Key)
					};
					for(int i = chain.Count - 1; i >= 0; i--) {
						jump.Chain.Add(StringOf(chain[i], "id"));
						if(!chain[i].TryGetProperty("writes", out JsonElement writes) || writes.ValueKind != JsonValueKind.Array) continue;
						foreach(JsonElement write in writes.EnumerateArray()) {
							if(TryParseWrite(write, out Write parsed, out string error)) jump.StoryWrites.Add(parsed);
							else catalog.Warnings.Add(item.Key + ": " + error);
						}
					}
					JsonElement self = chain[0];
					if(self.TryGetProperty("location", out JsonElement location) && location.ValueKind == JsonValueKind.Object && location.TryGetProperty("entrance", out JsonElement entranceValue)) {
						int? eid = Hex(entranceValue);
						JsonElement? entrance = eid.HasValue ? FindHex(entrances, "id", eid.Value) : null;
						Jump warp = entrance.HasValue ? EntranceJump(entrance.Value) : Unsupported("warp-entrance", "entrance is not in warps.json");
						jump.Method = warp.Method;
						jump.Reason = warp.Reason;
						jump.WarpPokes = warp.WarpPokes;
					} else if(self.TryGetProperty("location", out location) && location.ValueKind == JsonValueKind.Object && location.TryGetProperty("area", out JsonElement areaValue)) {
						int? areaId = Hex(areaValue);
						JsonElement? area = areaId.HasValue ? FindHex(areas, "area", areaId.Value) : null;
						Jump warp = area.HasValue ? AreaJump(area.Value) : Unsupported("warp-area", "area is not in warps.json");
						jump.Method = warp.Method;
						jump.Reason = warp.Reason;
						jump.WarpPokes = warp.WarpPokes;
					} else {
						jump.Method = "unsupported";
						jump.Reason = "checkpoint has no entrance or area";
					}
					catalog.Checkpoints.Add(jump);
				}
				if(checkpoints.RootElement.TryGetProperty("todo", out JsonElement todo) && todo.ValueKind == JsonValueKind.Array) {
					foreach(JsonElement row in todo.EnumerateArray()) {
						string id = StringOf(row, "id");
						if(id.Length == 0 || Find(catalog, "checkpoint", id) != null) continue;
						string reason = FirstNonEmpty(StringOf(row, "reason"), id);
						catalog.Checkpoints.Add(new Jump {
							Kind = "checkpoint", Id = id, Label = reason, Todo = true,
							Method = "unsupported", Reason = reason
						});
					}
				}
				return catalog;
			} catch(JsonException ex) {
				return Failed(ex.Message);
			}
		}

		public static Jump? Find(Catalog catalog, string kind, string id)
		{
			if(catalog == null || string.IsNullOrWhiteSpace(kind) || string.IsNullOrWhiteSpace(id)) return null;
			string want = id.Trim().ToLowerInvariant();
			List<Jump> rows = kind switch {
				"checkpoint" => catalog.Checkpoints,
				"warp-entrance" => catalog.Entrances,
				"warp-area" => catalog.Areas,
				_ => null!
			};
			if(rows == null) return null;
			foreach(Jump jump in rows) {
				if(jump.Kind == kind && jump.Id.Trim().ToLowerInvariant() == want) return jump;
			}
			return null;
		}

		public static string ResolveDebugDataDirectory()
		{
			string? debug = Environment.GetEnvironmentVariable("OOS_DEBUG_DATA");
			if(!string.IsNullOrWhiteSpace(debug) && Directory.Exists(debug)) return debug;
			foreach(string name in new[] { "OOS_ROOT", "ORACLE_OF_SECRETS_ROOT" }) {
				string? root = Environment.GetEnvironmentVariable(name);
				if(string.IsNullOrWhiteSpace(root)) continue;
				string candidate = Path.Combine(root, "Data", "debug");
				if(Directory.Exists(candidate)) return candidate;
			}
			string home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "src", "hobby", "oracle-of-secrets", "Data", "debug");
			return Directory.Exists(home) ? home : "";
		}

		public static Catalog LoadFromDirectory(string directory)
		{
			if(string.IsNullOrWhiteSpace(directory)) return Failed("Shared checkpoints were not found. Set OOS_DEBUG_DATA to Data/debug.");
			string checkpoints = Path.Combine(directory, "checkpoints.json");
			string warps = Path.Combine(directory, "warps.json");
			if(!File.Exists(checkpoints) || !File.Exists(warps)) {
				return Failed("Shared checkpoints were not found in " + directory);
			}
			return Parse(File.ReadAllText(checkpoints), File.ReadAllText(warps));
		}

		private static Catalog Failed(string error) => new() { Error = error };

		private static Jump Unsupported(string kind, string reason) => new() { Kind = kind, Method = "unsupported", Reason = reason };

		private static Jump EntranceJump(JsonElement row)
		{
			Jump jump = new() { Kind = "warp-entrance", Id = StringOf(row, "id"), Label = StringOf(row, "label") };
			if(!row.TryGetProperty("room", out JsonElement roomValue) || Hex(roomValue) is not int room) {
				jump.Method = "unsupported";
				jump.Reason = "entrance has no room in warps.json";
				return jump;
			}
			if(room >= 0x100 && room < 0x180 && room != 0x104) {
				jump.Method = "unsupported";
				jump.Reason = "house exit uses the overworld cache; use a savestate or a table exit";
				return jump;
			}
			if(Hex(row, "id") is not int eid) {
				jump.Method = "unsupported";
				jump.Reason = "entrance id is not hex";
				return jump;
			}
			jump.Method = "reload";
			if(Hex(row, "area") is int area) AddWorld(jump.WarpPokes, area);
			AddPoke(jump.WarpPokes, 0x7E010E, eid);
			AddPoke(jump.WarpPokes, 0x7E010F, 0);
			AddPoke(jump.WarpPokes, 0x7E010A, 1);
			AddPoke(jump.WarpPokes, 0x7E04AA, 0);
			AddPoke(jump.WarpPokes, 0x7E001B, 1);
			AddPoke(jump.WarpPokes, 0x7E0011, 0);
			AddPoke(jump.WarpPokes, 0x7E00B0, 0);
			AddPoke(jump.WarpPokes, 0x7E0010, 0x05);
			return jump;
		}

		private static Jump AreaJump(JsonElement row)
		{
			Jump jump = new() { Kind = "warp-area", Id = StringOf(row, "area"), Label = StringOf(row, "name") };
			if(Hex(row, "area") is not int destination || Hex(row, "via_room") is not int via) {
				jump.Method = "unsupported";
				jump.Reason = "area is missing area or via_room";
				return jump;
			}
			if(destination < 0 || destination >= 0x80) {
				jump.Method = "unsupported";
				jump.Reason = "special overworld areas are not loaded through the exit table";
				return jump;
			}
			if(via >= 0x100 && via < 0x180 && via != 0x104) {
				jump.Method = "unsupported";
				jump.Reason = "area exit room uses the overworld cache";
				return jump;
			}
			jump.Method = "area";
			AddWorld(jump.WarpPokes, destination);
			AddPoke(jump.WarpPokes, 0x7E00A0, via);
			AddPoke(jump.WarpPokes, 0x7E00A1, via >> 8);
			AddPoke(jump.WarpPokes, 0x7E010A, 0);
			AddPoke(jump.WarpPokes, 0x7E04AA, 0);
			AddPoke(jump.WarpPokes, 0x7E0013, 0x80);
			AddPoke(jump.WarpPokes, 0x7E009B, 0);
			AddPoke(jump.WarpPokes, 0x7E0011, 0);
			AddPoke(jump.WarpPokes, 0x7E00B0, 0);
			AddPoke(jump.WarpPokes, 0x7E0010, 0x08);
			return jump;
		}

		private static void AddWorld(List<Write> pokes, int area)
		{
			if(area < 0 || area >= 0x80) return;
			int world = area & 0x40;
			AddPoke(pokes, 0x7EF3CA, world);
			AddPoke(pokes, 0x7E0FFF, world != 0 ? 1 : 0);
		}

		private static void AddPoke(List<Write> pokes, uint addr, int value)
		{
			pokes.Add(new Write { Addr = addr, Op = "value", Operand = value & 0xFF });
		}

		private static bool TryParseWrite(JsonElement write, out Write parsed, out string error)
		{
			parsed = new Write();
			error = "";
			if(Hex(write, "addr") is not int addr) {
				error = "write is missing a hex addr";
				return false;
			}
			string name = StringOf(write, "name");
			if(write.TryGetProperty("value", out JsonElement value) && Hex(value) is int stored) {
				parsed = new Write { Addr = (uint)addr, Op = "value", Operand = stored & 0xFF, Name = name };
				return true;
			}
			if(write.TryGetProperty("set_bits", out JsonElement setBits) && Hex(setBits) is int set) {
				parsed = new Write { Addr = (uint)addr, Op = "set_bits", Operand = set & 0xFF, Name = name };
				return true;
			}
			if(write.TryGetProperty("clear_bits", out JsonElement clearBits) && Hex(clearBits) is int clear) {
				parsed = new Write { Addr = (uint)addr, Op = "clear_bits", Operand = clear & 0xFF, Name = name };
				return true;
			}
			error = "write has no value, set_bits, or clear_bits";
			return false;
		}

		private static JsonElement? FindHex(JsonElement rows, string key, int want)
		{
			if(rows.ValueKind != JsonValueKind.Array) return null;
			foreach(JsonElement row in rows.EnumerateArray()) {
				if(Hex(row, key) == want) return row;
			}
			return null;
		}

		private static JsonElement ArrayOf(JsonElement obj, string name)
		{
			return obj.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Array
				? value : default;
		}

		private static string StringOf(JsonElement obj, string name)
		{
			if(obj.ValueKind != JsonValueKind.Object || !obj.TryGetProperty(name, out JsonElement value) || value.ValueKind == JsonValueKind.Null) return "";
			if(value.ValueKind == JsonValueKind.String) return value.GetString() ?? "";
			if(value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int number)) return number.ToString(CultureInfo.InvariantCulture);
			return "";
		}

		private static string FirstNonEmpty(string value, string fallback) => string.IsNullOrWhiteSpace(value) ? fallback : value;

		private static int? Hex(JsonElement obj, string name)
		{
			if(obj.ValueKind != JsonValueKind.Object || !obj.TryGetProperty(name, out JsonElement value)) return null;
			return Hex(value);
		}

		private static int? Hex(JsonElement value)
		{
			if(value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int number)) return number;
			if(value.ValueKind != JsonValueKind.String) return null;
			string text = (value.GetString() ?? "").Trim().ToLowerInvariant();
			if(text.Length == 0) return null;
			if(text[0] == '$') text = "0x" + text.Substring(1);
			if(text.StartsWith("0x", StringComparison.Ordinal)) text = text.Substring(2);
			return int.TryParse(text, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out int parsed) ? parsed : null;
		}
	}
}
