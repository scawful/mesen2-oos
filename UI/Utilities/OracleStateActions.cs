using System;
using System.Threading.Tasks;
using Avalonia.Controls;
using Mesen.Controls;
using Mesen.Interop;
using Mesen.Windows;

namespace Mesen.Utilities
{
	public static class OracleStateActions
	{
		public static async Task SaveLabeledStateAsync(Window parentWindow)
		{
			RomInfo romInfo = EmuApi.GetRomInfo();
			if(romInfo.Format == RomFormat.Unknown) {
				await MesenMsgBox.Show(parentWindow, "No ROM loaded.", MessageBoxButtons.OK, MessageBoxIcon.Info);
				return;
			}

			bool isPaused = EmuApi.IsPaused();
			if(!isPaused) {
				EmuApi.Pause();
			}

			string? label = await TextInputWindow.ShowDialog(parentWindow, "Enter a label for this save state:", "Quick Save");
			if(!string.IsNullOrWhiteSpace(label)) {
				try {
					string id = OracleStateLibrary.SaveLabeledState(label);
					await MesenMsgBox.Show(parentWindow, $"Saved state '{label}' (ID: {id})", MessageBoxButtons.OK, MessageBoxIcon.Info);
				} catch(Exception ex) {
					await MesenMsgBox.Show(parentWindow, "Failed to save state: " + ex.Message, MessageBoxButtons.OK, MessageBoxIcon.Error);
				}
			}

			if(!isPaused) {
				EmuApi.Resume();
			}
		}

		public static async Task LabelSaveStateSlotAsync(Window parentWindow)
		{
			RomInfo romInfo = EmuApi.GetRomInfo();
			if(romInfo.Format == RomFormat.Unknown) {
				await MesenMsgBox.Show(parentWindow, "No ROM loaded.", MessageBoxButtons.OK, MessageBoxIcon.Info);
				return;
			}

			int maxSlots = SaveStateSlotHelper.GetMaxSlots();
			int autoSlot = SaveStateSlotHelper.GetAutoSlot();
			string? slotText = await TextInputWindow.ShowDialog(
				parentWindow,
				$"Enter slot number to label (1-{maxSlots}, auto: {autoSlot}):",
				"Label Save State Slot"
			);
			if(string.IsNullOrWhiteSpace(slotText)) {
				return;
			}

			int slot;
			if(slotText.Trim().Equals("auto", StringComparison.OrdinalIgnoreCase)) {
				slot = autoSlot;
			} else if(!int.TryParse(slotText, out slot)) {
				await MesenMsgBox.Show(parentWindow, "Invalid slot number.", MessageBoxButtons.OK, MessageBoxIcon.Warning);
				return;
			}

			if(slot < 1 || slot > autoSlot) {
				await MesenMsgBox.Show(parentWindow, $"Slot must be between 1 and {autoSlot}.", MessageBoxButtons.OK, MessageBoxIcon.Warning);
				return;
			}

			string? label = await TextInputWindow.ShowDialog(
				parentWindow,
				"Enter a label for this save state (leave blank to clear):",
				$"Label Slot {slot}"
			);
			if(label == null) {
				return;
			}

			string statePath = SaveStateSlotHelper.GetSaveStatePath(slot);
			if(SaveStateLabelStore.TryWriteLabel(statePath, label)) {
				string resultLabel = string.IsNullOrWhiteSpace(label) ? "cleared" : $"set to '{label}'";
				await MesenMsgBox.Show(parentWindow, $"Label {resultLabel} for slot {slot}.", MessageBoxButtons.OK, MessageBoxIcon.Info);
			} else {
				await MesenMsgBox.Show(parentWindow, "Failed to update save state label.", MessageBoxButtons.OK, MessageBoxIcon.Error);
			}
		}
	}
}
