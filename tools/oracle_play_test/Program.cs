using Mesen.Utilities;
using System;
using System.IO;
using System.Linq;

namespace Mesen.Interop
{
	public enum MemoryType { SnesMemory, SnesSaveRam }
	public enum RomFormat { Unknown, Sfc }
	public sealed class RomInfo { public RomFormat Format { get; set; } }
	public static class EmuApi
	{
		public static RomInfo GetRomInfo() => new RomInfo();
		public static bool IsPaused() => true;
		public static void Pause() {}
		public static void Resume() {}
		public static void LoadStateFile(string path) {}
	}
	public static class DebugApi
	{
		public static void InitializeDebugger() {}
		public static byte GetMemoryValue(MemoryType type, uint address) => 0;
		public static void SetMemoryValue(MemoryType type, uint address, byte value) {}
		public static byte[] GetMemoryValues(MemoryType type, uint start, uint end) => new byte[end - start + 1];
		public static void SetMemoryValues(MemoryType type, uint address, byte[] data, int length) {}
	}
}

namespace Mesen.Utilities
{
	public static class OracleStateLibrary
	{
		public static string GetLibraryRootPath() => Path.GetTempPath();
	}
}

internal static class Program
{
	private const string Checkpoints = """
	{
	  "schema": "oracle-debug-checkpoints/1",
	  "checkpoints": [
	    {"id": "intro-bed", "label": "Bed", "location": {"entrance": "0x01"},
	     "writes": [{"addr": "0x7EF3C5", "value": "0x00", "name": "GameState"}]},
	    {"id": "fortress-open", "label": "Fortress", "after": "intro-bed", "location": {"entrance": "0x37"},
	     "writes": [{"addr": "0x7EF3D4", "set_bits": "0x02", "name": "MakuTreeQuest"}]}
	  ],
	  "todo": [{"id": "abyss-owl", "reason": "not placed"}]
	}
	""";

	private const string Warps = """
	{
	  "schema": "oracle-debug-warps/1",
	  "entrances": [
	    {"id": "0x01", "label": "Link's House", "room": "0x0104", "area": "0x33"},
	    {"id": "0x37", "label": "Fortress", "room": "0x000C", "area": "0x4B"},
	    {"id": "0x10", "label": "Village Shop", "room": "0x0110", "area": "0x18"}
	  ],
	  "areas": [
	    {"area": "0x40", "name": "Temporal Gate", "via_room": "0x0020"},
	    {"area": "0xB0", "name": "Special", "via_room": "0x0001"}
	  ]
	}
	""";

	public static int Main()
	{
		Expect(OraclePlayCatalog.ApplyWrite(0x10, "set_bits", 0x02) == 0x12, "set_bits");
		Expect(OraclePlayCatalog.ApplyWrite(0xFF, "clear_bits", 0x0F) == 0xF0, "clear_bits");
		byte[] block = new byte[0x500];
		Expect(OraclePlayCatalog.StampSaveChecksum(block) == 0x5A5A, "checksum");

		OraclePlayCatalog.Catalog catalog = OraclePlayCatalog.Parse(Checkpoints, Warps);
		Expect(catalog.Error.Length == 0, catalog.Error);
		OraclePlayCatalog.Jump? fortress = OraclePlayCatalog.Find(catalog, "checkpoint", "fortress-open");
		Expect(fortress != null && fortress.Chain.Count == 2 && fortress.Chain[0] == "intro-bed", "chain");
		Expect(fortress!.StoryWrites.Count == 2 && fortress.StoryWrites[1].Op == "set_bits", "writes");
		Expect(fortress.Method == "reload", "method " + fortress.Method);
		Expect(Poke(fortress, 0x7E010E) == 0x37 && Poke(fortress, 0x7E0010) == 0x05 && Poke(fortress, 0x7EF3CA) == 0x40, "pokes");
		OraclePlayCatalog.Jump? shop = OraclePlayCatalog.Find(catalog, "warp-entrance", "0x10");
		Expect(shop != null && shop.Method == "unsupported", "shop");
		OraclePlayCatalog.Jump? gate = OraclePlayCatalog.Find(catalog, "warp-area", "0x40");
		Expect(gate != null && gate.Method == "area" && Poke(gate, 0x7E0010) == 0x08, "area");
		OraclePlayCatalog.Jump? special = OraclePlayCatalog.Find(catalog, "warp-area", "0xB0");
		Expect(special != null && special.Unsupported, "special");
		OraclePlayCatalog.Jump? todo = OraclePlayCatalog.Find(catalog, "checkpoint", "abyss-owl");
		Expect(todo != null && todo.Todo, "todo");
		Expect(OraclePlayCatalog.Parse("{\"schema\":\"other\"}", Warps).Error.Length > 0, "bad schema");
		string noRom = OraclePlayJump.Execute(fortress);
		Expect(noRom == "No ROM loaded.", noRom);
		Console.WriteLine("OraclePlayCatalogTest ok");
		return 0;
	}

	private static int Poke(OraclePlayCatalog.Jump jump, uint addr)
	{
		OraclePlayCatalog.Write? write = jump.WarpPokes.FirstOrDefault(item => item.Addr == addr);
		return write == null ? -1 : write.Operand;
	}

	private static void Expect(bool ok, string message)
	{
		if(!ok) throw new InvalidOperationException(message);
	}
}
