using Mesen.Config;
using Mesen.Interop;
using System;
using System.IO;

namespace Mesen.Utilities
{
	public static class SaveStateSlotHelper
	{
		public static string GetSaveStateBaseName()
		{
			RomInfo info = EmuApi.GetRomInfo();
			ResourcePath romPath = info.RomPath;
			string romName = Path.GetFileNameWithoutExtension(romPath.FileName);
			if(string.IsNullOrWhiteSpace(romName)) {
				romName = "rom";
			}
			if(!ConfigManager.Config.Preferences.SeparateSaveStatesByPatch || string.IsNullOrWhiteSpace(info.PatchPath)) {
				return romName;
			}

			ResourcePath patchPath = info.PatchPath;
			string patchName = Path.GetFileNameWithoutExtension(patchPath.FileName);
			if(string.IsNullOrWhiteSpace(patchName)) {
				return romName;
			}
			if(string.Equals(patchName, romName, StringComparison.OrdinalIgnoreCase)) {
				return romName;
			}

			return romName + "_" + patchName;
		}

		public static string GetSaveStatePath(int slot)
		{
			string baseName = GetSaveStateBaseName();
			return Path.Combine(ConfigManager.SaveStateFolder, baseName + "_" + slot + "." + FileDialogHelper.MesenSaveStateExt);
		}

		public static string GetRecentGamePath()
		{
			RomInfo info = EmuApi.GetRomInfo();
			ResourcePath romPath = info.RomPath;
			string romName = Path.GetFileNameWithoutExtension(romPath.FileName);
			if(string.IsNullOrWhiteSpace(romName)) {
				romName = "rom";
			}

			string baseName = GetSaveStateBaseName();
			string primaryPath = Path.Combine(ConfigManager.RecentGamesFolder, baseName + ".rgd");
			if(!ConfigManager.Config.Preferences.SeparateSaveStatesByPatch || string.IsNullOrWhiteSpace(info.PatchPath)) {
				return primaryPath;
			}

			if(File.Exists(primaryPath)) {
				return primaryPath;
			}

			return Path.Combine(ConfigManager.RecentGamesFolder, romName + ".rgd");
		}

		public static int GetMaxSlots()
		{
			uint count = EmuApi.GetSaveStateSlotCount();
			return count > 0 ? (int)count : 20;
		}

		public static int GetAutoSlot()
		{
			return GetMaxSlots() + 1;
		}
	}
}
