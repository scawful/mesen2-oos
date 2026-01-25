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
	}
}
