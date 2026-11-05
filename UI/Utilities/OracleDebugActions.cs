using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;

namespace Mesen.Utilities
{
	public sealed record OracleActionResult(bool Success, string Message)
	{
		public static OracleActionResult Ok(string message) => new(true, message);
		public static OracleActionResult Fail(string message) => new(false, message);
	}

	public enum OracleWarpKind
	{
		Entrance,
		FallToRoom
	}

	public sealed class OracleWarpTarget
	{
		public OracleWarpKind Kind { get; init; }
		public int Value { get; init; }
		public string Label { get; init; } = string.Empty;
	}

	public sealed class OracleSaveProfile
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
		/// <summary>Null for built-in presets; the JSON path for save-data profile files.</summary>
		public string? FilePath { get; init; }
	}

	/// <summary>
	/// In-process replacements for the Python agent-gateway actions used by the Oracle panel.
	/// Every action reads and writes emulator memory directly through <see cref="OraclePanelBridge"/>.
	/// </summary>
	public static class OracleDebugActions
	{
		private const uint ModuleAddr = 0x7E0010;
		private const uint SubmoduleAddr = 0x7E0011;
		private const uint IndoorsAddr = 0x7E001B;
		private const uint FallStateAddr = 0x7E005C;
		private const uint LinkStateAddr = 0x7E005D;
		private const uint LinkDirectionAddr = 0x7E002F;
		private const uint LinkFormAddr = 0x7E02B2;
		private const uint RoomAddr = 0x7E00A0;
		private const uint RoomHighAddr = 0x7E00A1;
		private const uint PreviousRoomAddr = 0x7E00A2;
		private const uint SubsubmoduleAddr = 0x7E00B0;
		private const uint EntranceAddr = 0x7E010E;
		private const uint EntranceHighAddr = 0x7E010F;
		private const int ModuleDungeon = 0x07;
		private const int ModuleOverworld = 0x09;
		private const int ModuleSpecialOverworld = 0x0B;
		private const int ModuleFallIntoDungeon = 0x11;
		private const int SubmoduleFallToRoom = 0x15;
		private const int MaxRoomId = 0x127;
		private const uint LinkYAddr = 0x7E0020;
		private const uint LinkXAddr = 0x7E0022;
		private const uint LinkFloorAddr = 0x7E00EE;
		private const uint SprYLowAddr = 0x7E0D00;
		private const uint SprXLowAddr = 0x7E0D10;
		private const uint SprYHighAddr = 0x7E0D20;
		private const uint SprXHighAddr = 0x7E0D30;
		private const uint SprStateAddr = 0x7E0DD0;
		private const uint SprTypeAddr = 0x7E0E20;
		private const uint SprFloorAddr = 0x7E0F20;
		private const uint SprHeightAddr = 0x7E0F70;
		private const int SprStateInitialize = 0x08;
		private const int SpriteSlotCount = 16;

		// Verified in Roms/TestBuilds/rc-intro-origins-goron-2026-09-25/playtest.md.
		public static IReadOnlyList<OracleWarpTarget> WarpTargets { get; } = new List<OracleWarpTarget>() {
			new() { Kind = OracleWarpKind.Entrance, Value = 0x76, Label = "Origins (room $05)" },
			new() { Kind = OracleWarpKind.Entrance, Value = 0x27, Label = "Goron Mines (room $98)" },
			new() { Kind = OracleWarpKind.FallToRoom, Value = 0x77, Label = "Goron Mines room $77" },
			new() { Kind = OracleWarpKind.FallToRoom, Value = 0x87, Label = "Goron Mines room $87" },
			new() { Kind = OracleWarpKind.FallToRoom, Value = 0xB8, Label = "Goron Mines room $B8" },
			new() { Kind = OracleWarpKind.FallToRoom, Value = 0xC8, Label = "Goron Mines room $C8 (boss)" },
		};

		public static OracleActionResult Warp(OracleWarpTarget target)
		{
			return target.Kind == OracleWarpKind.Entrance ? WarpToEntrance(target.Value) : FallToRoom(target.Value);
		}

		/// <summary>
		/// Enter an entrance ID: the vanilla door entry from overworld play, otherwise the
		/// death-continue reload (see <see cref="OracleCheats"/>). Waits until the room is loaded.
		/// </summary>
		public static OracleActionResult WarpToEntrance(int entrance)
		{
			if(entrance < 0 || entrance > 0xFF) {
				return OracleActionResult.Fail($"Entrance ${entrance:X} is out of range ($00-$FF).");
			}
			return OracleCheats.WarpToEntrance(entrance);
		}

		/// <summary>Drop Link into another room of the current dungeon. Link keeps his X/Y.</summary>
		public static OracleActionResult FallToRoom(int room)
		{
			if(room < 0 || room > MaxRoomId) {
				return OracleActionResult.Fail($"Room ${room:X} is out of range ($00-${MaxRoomId:X3}).");
			}

			return WithPausedEmulation(() => {
				int module = OraclePanelBridge.ReadByte(ModuleAddr);
				int submodule = OraclePanelBridge.ReadByte(SubmoduleAddr);
				if(module != ModuleDungeon || submodule != 0) {
					return OracleActionResult.Fail($"Fall to room needs idle dungeon play (module $07, submodule $00); now ${module:X2}/${submodule:X2}.");
				}

				int currentRoom = OraclePanelBridge.ReadByte(RoomAddr);
				OraclePanelBridge.WriteByte(FallStateAddr, 0x20);
				OraclePanelBridge.WriteByte(LinkStateAddr, 0x01);
				OraclePanelBridge.WriteByte(PreviousRoomAddr, currentRoom);
				OraclePanelBridge.WriteByte(RoomHighAddr, room >> 8);
				OraclePanelBridge.WriteByte(RoomAddr, room & 0xFF);
				OraclePanelBridge.WriteByte(SubmoduleAddr, SubmoduleFallToRoom);
				return OracleActionResult.Ok($"Falling from room ${currentRoom:X2} to room ${room:X2}.");
			});
		}

		/// <summary>
		/// Spawn a sprite through SpriteModule_Initialize ($0DD0 = $08), the same writes the
		/// D8 boss native tests use. Without a room point the sprite appears 16 px right of Link;
		/// a room point is relative to the current dungeon room's origin.
		/// </summary>
		public static OracleActionResult SpawnSprite(int spriteId, int? roomX = null, int? roomY = null)
		{
			if(spriteId < 0 || spriteId > 0xFF) {
				return OracleActionResult.Fail($"Sprite ${spriteId:X} is out of range ($00-$FF).");
			}

			return WithPausedEmulation(() => {
				int module = OraclePanelBridge.ReadByte(ModuleAddr);
				int submodule = OraclePanelBridge.ReadByte(SubmoduleAddr);
				if((module != ModuleDungeon && module != ModuleOverworld && module != ModuleSpecialOverworld) || submodule != 0) {
					return OracleActionResult.Fail($"Spawning needs normal play (module $07/$09/$0B, submodule $00); now ${module:X2}/${submodule:X2}.");
				}

				int slot = -1;
				for(int i = SpriteSlotCount - 1; i >= 0; i--) {
					if(OraclePanelBridge.ReadByte(SprStateAddr + (uint)i) == 0) {
						slot = i;
						break;
					}
				}
				if(slot < 0) {
					return OracleActionResult.Fail("All 16 sprite slots are in use.");
				}

				int x, y;
				if(roomX.HasValue && roomY.HasValue) {
					if(module != ModuleDungeon) {
						return OracleActionResult.Fail("A room point needs dungeon play; omit it to spawn next to Link.");
					}
					int room = OraclePanelBridge.ReadWord(RoomAddr);
					x = (room & 0x0F) * 0x200 + roomX.Value;
					y = ((room >> 4) & 0x0F) * 0x200 + roomY.Value;
				} else {
					x = OraclePanelBridge.ReadWord(LinkXAddr) + 16;
					y = OraclePanelBridge.ReadWord(LinkYAddr);
				}

				uint s = (uint)slot;
				OraclePanelBridge.WriteByte(SprTypeAddr + s, spriteId);
				OraclePanelBridge.WriteByte(SprXLowAddr + s, x & 0xFF);
				OraclePanelBridge.WriteByte(SprXHighAddr + s, (x >> 8) & 0xFF);
				OraclePanelBridge.WriteByte(SprYLowAddr + s, y & 0xFF);
				OraclePanelBridge.WriteByte(SprYHighAddr + s, (y >> 8) & 0xFF);
				OraclePanelBridge.WriteByte(SprFloorAddr + s, OraclePanelBridge.ReadByte(LinkFloorAddr));
				OraclePanelBridge.WriteByte(SprHeightAddr + s, 0);
				OraclePanelBridge.WriteByte(SprStateAddr + s, SprStateInitialize);
				return OracleActionResult.Ok($"Spawned sprite ${spriteId:X2} in slot {slot} at ({x:X4}, {y:X4}).");
			});
		}

		/// <summary>Write one byte of WRAM ($7E0000-$7FFFFF). Macros use this for state the presets do not cover.</summary>
		public static OracleActionResult WriteMemory(uint address, int value)
		{
			if(address < 0x7E0000 || address > 0x7FFFFF) {
				return OracleActionResult.Fail($"${address:X6} is outside WRAM ($7E0000-$7FFFFF).");
			}
			if(value < 0 || value > 0xFF) {
				return OracleActionResult.Fail($"Value {value} is not a byte.");
			}
			return WithPausedEmulation(() => {
				OraclePanelBridge.WriteByte(address, value);
				return OracleActionResult.Ok($"${address:X6} = ${value:X2}");
			});
		}

		/// <summary>
		/// Feature flags the loaded ROM was built with: feature_flags.asm beside the ROM, else the
		/// pre-assembly snapshot in &lt;rom&gt;.build.json (its top-level profile is the restored default).
		/// </summary>
		public static (string Summary, string Detail) DescribeBuildFlags(string romPath)
		{
			if(string.IsNullOrWhiteSpace(romPath) || !File.Exists(romPath)) {
				return ("-", "");
			}
			string dir = Path.GetDirectoryName(romPath) ?? "";
			Dictionary<string, int> flags = new(StringComparer.Ordinal);
			string source;
			try {
				string asm = Path.Combine(dir, "feature_flags.asm");
				string receipt = Path.Combine(dir, Path.GetFileNameWithoutExtension(romPath) + ".build.json");
				if(File.Exists(asm)) {
					source = "feature_flags.asm";
					foreach(string line in File.ReadLines(asm)) {
						string[] parts = line.Split('=', 2, StringSplitOptions.TrimEntries);
						if(parts.Length == 2 && parts[0].StartsWith('!') && int.TryParse(parts[1].Split(';')[0].Trim(), out int v)) {
							flags[parts[0].Substring(1)] = v;
						}
					}
				} else if(File.Exists(receipt)) {
					source = Path.GetFileName(receipt);
					using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(receipt));
					if(doc.RootElement.TryGetProperty("snapshots", out JsonElement snaps) &&
						snaps.TryGetProperty("pre_assembly", out JsonElement pre) &&
						pre.TryGetProperty("profile", out JsonElement profile) &&
						profile.TryGetProperty("effective_flags", out JsonElement effective) &&
						effective.ValueKind == JsonValueKind.Object) {
						foreach(JsonProperty p in effective.EnumerateObject()) {
							if(p.Value.ValueKind == JsonValueKind.Number) {
								flags[p.Name] = p.Value.GetInt32();
							}
						}
					}
				} else {
					return ("No build receipt beside this ROM", "");
				}
			} catch(Exception ex) {
				return ("Unreadable build receipt", ex.Message);
			}

			List<string> enableOn = flags.Where(f => f.Key.StartsWith("ENABLE_") && f.Value != 0).Select(f => f.Key.Substring(7)).OrderBy(n => n).ToList();
			List<string> enableOff = flags.Where(f => f.Key.StartsWith("ENABLE_") && f.Value == 0).Select(f => f.Key.Substring(7)).OrderBy(n => n).ToList();
			List<string> modulesOff = flags.Where(f => f.Key.StartsWith("DISABLE_") && f.Value != 0).Select(f => f.Key).OrderBy(n => n).ToList();
			if(enableOn.Count + enableOff.Count == 0) {
				return ($"No flags in {source}", "");
			}
			string summary = $"{enableOn.Count} on · off: {(enableOff.Count == 0 ? "none" : string.Join(", ", enableOff))}";
			if(modulesOff.Count > 0) {
				summary += " · " + string.Join(", ", modulesOff);
			}
			return (summary, $"From {source}. On: {string.Join(", ", enableOn)}");
		}

		public static IReadOnlyList<OracleSaveProfile> GetSaveProfiles()
		{
			List<OracleSaveProfile> profiles = OraclePanelBridge.GetPresets().Select(preset => new OracleSaveProfile() {
				Id = preset.Id,
				Label = preset.Label,
				Description = preset.Description
			}).ToList();

			string? folder = GetProfileFolder();
			if(folder != null && Directory.Exists(folder)) {
				foreach(string path in Directory.GetFiles(folder, "*.json").OrderBy(p => p, StringComparer.OrdinalIgnoreCase)) {
					try {
						using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path));
						JsonElement root = doc.RootElement;
						string id = GetString(root, "id") ?? Path.GetFileNameWithoutExtension(path);
						profiles.Add(new OracleSaveProfile() {
							Id = id,
							Label = (GetString(root, "label") ?? id) + " (file)",
							Description = GetString(root, "description") ?? path,
							FilePath = path
						});
					} catch {
						//Unreadable profile files are skipped; ApplySaveProfile reports the error if one is chosen by id.
					}
				}
			}
			return profiles;
		}

		/// <summary>Apply a built-in preset or a save-data profile file, then persist it to SRAM.</summary>
		public static OracleActionResult ApplySaveProfile(string id)
		{
			OracleSaveProfile? profile = GetSaveProfiles().FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase));
			if(profile == null) {
				return OracleActionResult.Fail($"Unknown profile '{id}'.");
			}

			return WithPausedEmulation(() => {
				int items;
				int flags;
				int writes = 0;
				if(profile.FilePath == null) {
					OraclePanelApplyResult? result = OraclePanelBridge.ApplySaveDataChangesAsync(
						Array.Empty<OraclePanelValueUpdate>(), Array.Empty<OraclePanelValueUpdate>(), false, profile.Id
					).Result;
					if(result == null) {
						return OracleActionResult.Fail("No game running.");
					}
					items = result.ChangedItems;
					flags = result.ChangedFlags;
				} else {
					using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(profile.FilePath));
					JsonElement root = doc.RootElement;
					items = OraclePanelBridge.ApplyItemValues(ReadValueMap(root, "items"));
					flags = OraclePanelBridge.ApplyFlagValues(ReadValueMap(root, "flags"));
					writes = ApplyRawWrites(root);
				}

				int? slot = OraclePanelBridge.SyncWramSaveToSram();
				string written = writes > 0 ? $", {writes} raw writes" : "";
				return OracleActionResult.Ok($"Applied '{profile.Label}': {items} items, {flags} flags{written}; saved to SRAM slot {slot}.");
			});
		}

		public static IReadOnlyList<OraclePanelToggleOption> GetFlags()
		{
			return OraclePanelBridge.GetFlagDefinitions();
		}

		/// <summary>Set a story flag in WRAM only, like the old setflag action.</summary>
		public static OracleActionResult SetFlag(string key, int value)
		{
			return WithPausedEmulation(() => {
				int changed = OraclePanelBridge.ApplyFlagValues(new[] { new OraclePanelValueUpdate() { Key = key, Value = value } });
				return changed > 0
					? OracleActionResult.Ok($"Flag '{key}' = {value} (WRAM; sync to SRAM to keep it).")
					: OracleActionResult.Fail($"Unknown flag '{key}'.");
			});
		}

		public static OracleActionResult SyncSram()
		{
			return WithPausedEmulation(() => {
				int? slot = OraclePanelBridge.SyncWramSaveToSram();
				return OracleActionResult.Ok($"Saved WRAM $7EF000-$7EF4FF to SRAM slot {slot} (main + mirror).");
			});
		}

		/// <summary>Run exactly <paramref name="frames"/> frames, then stay paused.</summary>
		public static OracleActionResult StepFrames(int frames)
		{
			if(frames < 1 || frames > 3600) {
				return OracleActionResult.Fail("Frame count must be 1-3600.");
			}
			if(!OraclePanelBridge.CanReadState()) {
				return OracleActionResult.Fail("No game running.");
			}
			DebugApi.Step(CpuType.Snes, frames, StepType.PpuFrame);
			return OracleActionResult.Ok($"Stepped {frames} frame{(frames == 1 ? "" : "s")}; paused.");
		}

		/// <summary>
		/// Save a state into the Oracle state library (same layout as `mesen2_client.py lib-save`),
		/// plus a PNG of the saved frame. The new entry has status "draft".
		/// </summary>
		public static OracleActionResult CaptureState(string label, IReadOnlyList<string> tags, string notes)
		{
			string? projectRoot = AgentLauncher.ResolveProjectRoot();
			if(projectRoot == null) {
				return OracleActionResult.Fail("Oracle project root not found. Set ORACLE_OF_SECRETS_ROOT.");
			}
			OraclePanelRuntimeSummary? state = OraclePanelBridge.FetchRuntimeSummaryAsync().Result;
			if(state == null) {
				return OracleActionResult.Fail("No game running.");
			}

			string cleanLabel = new string(label.Trim().Select(c => char.IsLetterOrDigit(c) ? c : '_').ToArray()).Trim('_');
			if(cleanLabel.Length == 0) {
				cleanLabel = "state";
			}
			long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			string id = $"{timestamp}_{cleanLabel}";
			string fileName = $"oos_{id}.mss";
			string libraryRoot = Path.Combine(projectRoot, "Roms", "SaveStates", "library");
			string statePath = Path.Combine(libraryRoot, fileName);
			string pngPath = Path.ChangeExtension(statePath, ".png");
			Directory.CreateDirectory(libraryRoot);

			EmuApi.SaveStateFile(statePath);
			if(!File.Exists(statePath)) {
				return OracleActionResult.Fail("Mesen did not write the state file.");
			}
			bool hasPng = false;
			try {
				using Avalonia.Media.Imaging.Bitmap? preview = EmuApi.GetSaveStatePreview(statePath);
				if(preview != null) {
					preview.Save(pngPath);
					hasPng = true;
				}
			} catch {
				hasPng = false;
			}

			RomInfo rom = EmuApi.GetRomInfo();
			JsonObject metadata = new() {
				["module"] = OraclePanelBridge.ReadByte(ModuleAddr),
				["room"] = state.RoomLayout,
				["area"] = state.AreaId,
				["indoors"] = state.Indoors,
				["link_x"] = state.LinkX,
				["link_y"] = state.LinkY,
				["link_state"] = state.LinkState,
				["link_form"] = OraclePanelBridge.ReadByte(LinkFormAddr),
				["link_form_name"] = state.LinkFormName,
				["link_dir"] = OraclePanelBridge.ReadByte(LinkDirectionAddr),
				["health"] = state.Health,
				["max_health"] = state.MaxHealth,
				["magic"] = state.Magic,
				["rupees"] = state.Rupees,
				["game_state"] = state.GameState,
				["crystals"] = state.Crystals,
				["pendants"] = state.Pendants,
				["location"] = state.Indoors ? $"Inside: room 0x{state.DungeonRoom:X4}" : state.AreaName,
				["summary"] = $"Mode: {state.ModeName} | GameState: {state.GameState}",
				["rom"] = rom.GetRomName(),
				["rom_path"] = (string)rom.RomPath,
				["screenshot"] = hasPng ? Path.GetFileName(pngPath) : null,
				["notes"] = string.IsNullOrWhiteSpace(notes) ? null : notes.Trim(),
				["captured_with"] = "mesen2-oos oracle panel"
			};
			JsonObject entry = new() {
				["id"] = id,
				["path"] = fileName,
				["label"] = label.Trim().Length > 0 ? label.Trim() : cleanLabel,
				["created_at"] = timestamp,
				["tags"] = new JsonArray(tags.Select(t => (JsonNode?)JsonValue.Create(t)).ToArray()),
				["metadata"] = metadata,
				["status"] = "draft",
				["captured_by"] = "human",
				["verified_by"] = null,
				["verified_at"] = null,
				["md5"] = ComputeMd5(statePath)
			};

			string libraryJson = Path.Combine(projectRoot, "Docs", "Debugging", "Testing", "save_state_library.json");
			string savedTo = Path.GetRelativePath(projectRoot, statePath);
			if(TryPrependLibraryEntry(libraryJson, entry, out string? error)) {
				return OracleActionResult.Ok($"Saved {savedTo}{(hasPng ? " + .png" : "")} (library id {id}, draft).");
			}

			//Keep the capture usable even when the shared library file cannot be updated.
			File.WriteAllText(statePath + ".meta.json", entry.ToJsonString(JsonOptions));
			return OracleActionResult.Fail($"Saved {savedTo}, but the library was not updated ({error}); wrote .meta.json instead.");
		}

		private static readonly JsonSerializerOptions JsonOptions = new() {
			WriteIndented = true,
			Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
		};

		/// <summary>
		/// Insert the entry at the top of "entries" without re-serializing the rest of the file,
		/// so the tracked library JSON only gains the new lines.
		/// </summary>
		private static bool TryPrependLibraryEntry(string libraryJson, JsonObject entry, out string? error)
		{
			error = null;
			try {
				if(!File.Exists(libraryJson)) {
					error = "library JSON not found";
					return false;
				}
				string text = File.ReadAllText(libraryJson);
				const string marker = "\"entries\": [\n";
				int index = text.IndexOf(marker, StringComparison.Ordinal);
				if(index < 0) {
					error = "unexpected library JSON layout";
					return false;
				}
				int insertAt = index + marker.Length;
				bool hasEntries = text.AsSpan(insertAt).TrimStart().Length > 0 && text.AsSpan(insertAt).TrimStart()[0] != ']';

				string[] lines = entry.ToJsonString(JsonOptions).Replace("\r\n", "\n").Split('\n');
				StringBuilder block = new StringBuilder();
				for(int i = 0; i < lines.Length; i++) {
					block.Append("    ").Append(lines[i]);
					if(i < lines.Length - 1) {
						block.Append('\n');
					}
				}
				block.Append(hasEntries ? ",\n" : "\n");

				string updated = text.Insert(insertAt, block.ToString());
				using(JsonDocument.Parse(updated)) {
					//Refuse to write anything that no longer parses.
				}
				File.WriteAllText(libraryJson, updated);
				return true;
			} catch(Exception ex) {
				error = ex.Message;
				return false;
			}
		}

		private static OracleActionResult WithPausedEmulation(Func<OracleActionResult> action)
		{
			if(!OraclePanelBridge.CanReadState()) {
				return OracleActionResult.Fail("No game running.");
			}

			bool wasPaused = EmuApi.IsPaused();
			if(!wasPaused) {
				EmuApi.Pause();
				//With the debugger attached, Pause breaks after the next instruction.
				for(int i = 0; i < 50 && !EmuApi.IsPaused(); i++) {
					Thread.Sleep(5);
				}
			}
			try {
				return action();
			} catch(Exception ex) {
				return OracleActionResult.Fail(ex.Message);
			} finally {
				if(!wasPaused) {
					EmuApi.Resume();
				}
			}
		}

		private static string? GetProfileFolder()
		{
			string? root = AgentLauncher.ResolveProjectRoot();
			return root == null ? null : Path.Combine(root, "Docs", "Debugging", "Testing", "save_data_profiles");
		}

		private static string? GetString(JsonElement element, string property)
		{
			return element.TryGetProperty(property, out JsonElement value) && value.ValueKind == JsonValueKind.String
				? value.GetString()
				: null;
		}

		private static List<OraclePanelValueUpdate> ReadValueMap(JsonElement root, string property)
		{
			List<OraclePanelValueUpdate> updates = new();
			if(!root.TryGetProperty(property, out JsonElement map) || map.ValueKind != JsonValueKind.Object) {
				return updates;
			}
			foreach(JsonProperty entry in map.EnumerateObject()) {
				int value = entry.Value.ValueKind switch {
					JsonValueKind.True => 1,
					JsonValueKind.False => 0,
					JsonValueKind.Number => entry.Value.GetInt32(),
					_ => throw new InvalidDataException($"'{property}.{entry.Name}' must be a number or boolean.")
				};
				updates.Add(new OraclePanelValueUpdate() { Key = entry.Name, Value = value });
			}
			return updates;
		}

		private static int ApplyRawWrites(JsonElement root)
		{
			if(!root.TryGetProperty("writes", out JsonElement writes) || writes.ValueKind != JsonValueKind.Array) {
				return 0;
			}
			int count = 0;
			foreach(JsonElement write in writes.EnumerateArray()) {
				uint address = ParseAddress(write.GetProperty("addr"));
				int value = write.GetProperty("value").GetInt32();
				bool isWord = string.Equals(GetString(write, "type"), "u16", StringComparison.OrdinalIgnoreCase);
				string memType = GetString(write, "memtype") ?? "";
				if(memType.Equals("sram", StringComparison.OrdinalIgnoreCase)) {
					DebugApi.SetMemoryValue(MemoryType.SnesSaveRam, address, (byte)(value & 0xFF));
					if(isWord) {
						DebugApi.SetMemoryValue(MemoryType.SnesSaveRam, address + 1, (byte)((value >> 8) & 0xFF));
					}
				} else if(isWord) {
					OraclePanelBridge.WriteWord(address, value);
				} else {
					OraclePanelBridge.WriteByte(address, value);
				}
				count++;
			}
			return count;
		}

		private static uint ParseAddress(JsonElement element)
		{
			if(element.ValueKind == JsonValueKind.Number) {
				return element.GetUInt32();
			}
			string text = (element.GetString() ?? "").Trim().Replace("$", "").Replace("0x", "").Replace("0X", "");
			return uint.Parse(text, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
		}

		private static string ComputeMd5(string path)
		{
			using FileStream stream = File.OpenRead(path);
			return Convert.ToHexString(MD5.HashData(stream)).ToLowerInvariant();
		}
	}
}
