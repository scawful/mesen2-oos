using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Platform.Storage;
using Mesen.Utilities;
using Mesen.ViewModels;
using Mesen.Windows;
using System;
using System.IO;

namespace Mesen.Views
{
	/// <summary>Oracle tools pane docked to the right of the game (expanded mode).</summary>
	public class OracleToolsPaneView : UserControl
	{
		private OracleDebugPanelViewModel? ViewModel => DataContext as OracleDebugPanelViewModel;

		public OracleToolsPaneView()
		{
			InitializeComponent();
			AddHandler(KeyDownEvent, OnPaneKeyDown, RoutingStrategies.Bubble, true);
		}

		private void OnPaneKeyDown(object? sender, KeyEventArgs e)
		{
			//Enter/Escape hand the keyboard back to the game (the window treats a focused text box as background).
			IFocusManager? focusManager = TopLevel.GetTopLevel(this)?.FocusManager;
			if(focusManager?.GetFocusedElement() is not TextBox box) {
				return;
			}
			if(e.Key == Key.Escape || (e.Key == Key.Enter && !box.AcceptsReturn)) {
				focusManager.ClearFocus();
				e.Handled = true;
			}
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void OnToolPageClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null && sender is Button { Tag: string pageName }
				&& Enum.TryParse(pageName, out OracleToolPage page)) {
				ViewModel.SelectedToolPage = page;
			}
		}

		private async void OnWarpChoiceClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.Cheats.ExecuteWarpAsync();
			}
		}

		private async void OnWarpChoiceDoubleTapped(object? sender, TappedEventArgs e)
		{
			if(ViewModel != null && ViewModel.Cheats.SelectedWarp != null && !ViewModel.IsCommandInFlight) {
				await ViewModel.Cheats.ExecuteWarpAsync();
			}
		}

		private async void OnJumpCheckpointClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel?.Cheats.SelectedCheckpoint == null) {
				return;
			}
			if(!ViewModel.Cheats.SelectedCheckpoint.IsTodo) {
				Window? window = TopLevel.GetTopLevel(this) as Window;
				DialogResult answer = await MessageBox.Show(window, ViewModel.Cheats.ConfirmCheckpointText(), "Jump to checkpoint", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
				if(answer != DialogResult.OK) {
					return;
				}
			}
			await ViewModel.Cheats.ExecuteCheckpointAsync();
		}

		private async void OnRefillClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.Cheats.ExecuteRefillAsync();
			}
		}

		private async void OnWarpEntranceClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteWarpEntranceAsync();
			}
		}

		private async void OnFallToRoomClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteFallToRoomAsync();
			}
		}

		private async void OnCaptureStateClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteCaptureStateAsync();
			}
		}

		private async void OnApplyPresetClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteApplyPresetAsync();
			}
		}

		private async void OnSetFlagOnClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteSetFlagAsync(true);
			}
		}

		private async void OnSetFlagOffClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteSetFlagAsync(false);
			}
		}

		private async void OnSyncSramClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteSyncSramAsync();
			}
		}

		private void OnOpenSaveDataEditorClick(object? sender, RoutedEventArgs e)
		{
			ApplicationHelper.GetOrCreateUniqueWindow(this, () => new OracleSaveDataEditorWindow());
		}

		private void OnPauseClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.TogglePause();
		}

		private async void OnFrameAdvanceClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteFrameAdvanceAsync();
			}
		}

		private async void OnReloadMacrosClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ReloadMacrosAsync();
			}
		}

		private async void OnRunMacroClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel == null || sender is not Button button || button.Tag is not string macroId) {
				return;
			}
			await ViewModel.RunMacroAsync(macroId);
		}

		private async void OnCopyLogClick(object? sender, RoutedEventArgs e)
		{
			TopLevel? topLevel = TopLevel.GetTopLevel(this);
			if(ViewModel != null && topLevel?.Clipboard != null) {
				await topLevel.Clipboard.SetTextAsync(ViewModel.GetSessionLogText());
			}
		}

		private async void OnSaveLogClick(object? sender, RoutedEventArgs e)
		{
			TopLevel? topLevel = TopLevel.GetTopLevel(this);
			if(ViewModel == null || topLevel?.StorageProvider == null) {
				return;
			}

			IStorageFile? file = await topLevel.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions() {
				Title = "Save Oracle panel log",
				DefaultExtension = "txt",
				SuggestedFileName = "oracle_panel_log.txt"
			});
			if(file == null) {
				return;
			}

			await using Stream stream = await file.OpenWriteAsync();
			using StreamWriter writer = new StreamWriter(stream);
			await writer.WriteAsync(ViewModel.GetSessionLogText());
		}

		private void OnClearLogClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.ClearSessionLog();
		}
	}
}
