using Mesen.Interop;
using System;
using System.IO;

namespace Mesen.Utilities
{
	/// <summary>
	/// Applies a shared checkpoint or warp plan, or loads a labeled savestate.
	/// Story bytes are written to WRAM, then the warp poke, then the active SRAM
	/// slot. The game must already be in normal play.
	/// </summary>
	public static class OraclePlayJump
	{
		public static string Execute(OraclePlayCatalog.Jump jump)
		{
			if(jump == null) return "Choose a checkpoint, warp, or savestate.";
			if(jump.Unsupported) return string.IsNullOrWhiteSpace(jump.Reason) ? "This jump is not available." : jump.Reason;
			RomInfo rom = EmuApi.GetRomInfo();
			if(rom.Format == RomFormat.Unknown) return "No ROM loaded.";

			if(jump.Kind == "savestate") {
				if(string.IsNullOrWhiteSpace(jump.Path) || !File.Exists(jump.Path)) return "Savestate file was not found.";
				EmuApi.LoadStateFile(jump.Path);
				return "Loaded savestate " + jump.Label;
			}

			bool paused = EmuApi.IsPaused();
			if(!paused) EmuApi.Pause();
			try {
				DebugApi.InitializeDebugger();
				int module = Read(0x7E0010);
				int submodule = Read(0x7E0011);
				if(module != 0x07 && module != 0x09 && module != 0x0B || submodule != 0) {
					return "Needs normal play (module $07/$09/$0B, submodule 0).";
				}
				if(jump.Method == "reload" && Read(0x7EF3CC) == 0x02) {
					return "Follower $02 makes the entrance load the spawn point. Clear it first.";
				}
				foreach(OraclePlayCatalog.Write write in jump.StoryWrites) {
					int next = OraclePlayCatalog.ApplyWrite(Read(write.Addr), write.Op, write.Operand);
					if(next < 0) return "Unsupported story write at $" + write.Addr.ToString("X6");
					Write(write.Addr, next);
				}
				foreach(OraclePlayCatalog.Write poke in jump.WarpPokes) {
					Write(poke.Addr, poke.Operand);
				}
				int slot = SyncSram();
				return "Jumped to " + jump.Label + " (SRAM slot " + slot + ").";
			} catch(Exception ex) {
				return "Jump failed: " + ex.Message;
			} finally {
				if(!paused) EmuApi.Resume();
			}
		}

		private static int SyncSram()
		{
			byte[] block = DebugApi.GetMemoryValues(MemoryType.SnesMemory, 0x7EF000, 0x7EF4FF);
			if(block.Length != 0x500) throw new InvalidOperationException("Could not read the save block.");
			OraclePlayCatalog.StampSaveChecksum(block);
			Write(0x7EF4FE, block[0x4FE]);
			Write(0x7EF4FF, block[0x4FF]);
			int raw = DebugApi.GetMemoryValue(MemoryType.SnesSaveRam, 0x1FFE)
				| (DebugApi.GetMemoryValue(MemoryType.SnesSaveRam, 0x1FFF) << 8);
			int slot = raw is 2 or 4 or 6 ? raw / 2 : 1;
			int baseAddr = (slot - 1) * 0x500;
			DebugApi.SetMemoryValues(MemoryType.SnesSaveRam, (uint)baseAddr, block, block.Length);
			DebugApi.SetMemoryValues(MemoryType.SnesSaveRam, (uint)(baseAddr + 0xF00), block, block.Length);
			return slot;
		}

		private static int Read(uint address) => DebugApi.GetMemoryValue(MemoryType.SnesMemory, address);

		private static void Write(uint address, int value)
		{
			DebugApi.SetMemoryValue(MemoryType.SnesMemory, address, (byte)(value & 0xFF));
		}
	}
}
