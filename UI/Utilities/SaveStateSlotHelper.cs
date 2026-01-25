using Mesen.Interop;

namespace Mesen.Utilities
{
	public static class SaveStateSlotHelper
	{
		public static int GetMaxSlots()
		{
			uint count = EmuApi.GetSaveStateSlotCount();
			return count > 0 ? (int)count : 10;
		}

		public static int GetAutoSlot()
		{
			return GetMaxSlots() + 1;
		}
	}
}
