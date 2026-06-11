using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Platform.Storage;
using Mesen.Utilities;
using Mesen.ViewModels;
using Mesen.Windows;
using System.IO;

namespace Mesen.Views
{
	public class OracleDebugPanelView : UserControl
	{
		private OracleDebugPanelViewModel? ViewModel => DataContext as OracleDebugPanelViewModel;

		public OracleDebugPanelView()
		{
			InitializeComponent();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void OnToggleVisibilityClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.ToggleHiddenCollapsed();
		}

		private void OnToggleExpandedClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.ToggleCollapsedExpanded();
		}

		private void OnRefreshClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.RequestRefresh(true);
		}

		private async void OnCaptureStateClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteCaptureStateAsync();
			}
		}

		private async void OnApplyProfileClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteApplyProfileAsync();
			}
		}

		private async void OnSetFlagClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteSetFlagAsync();
			}
		}

		private async void OnFlyClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteFlyAsync();
			}
		}

		private async void OnFrameAdvanceClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel != null) {
				await ViewModel.ExecuteFrameAdvanceAsync();
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
			if(ViewModel == null) {
				return;
			}
			TopLevel? topLevel = TopLevel.GetTopLevel(this);
			if(topLevel?.Clipboard != null) {
				await topLevel.Clipboard.SetTextAsync(ViewModel.GetSessionLogText());
			}
		}

		private async void OnSaveLogClick(object? sender, RoutedEventArgs e)
		{
			if(ViewModel == null) {
				return;
			}
			TopLevel? topLevel = TopLevel.GetTopLevel(this);
			if(topLevel?.StorageProvider == null) {
				return;
			}

			IStorageFile? file = await topLevel.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions() {
				Title = "Save Oracle panel session log",
				DefaultExtension = "txt",
				SuggestedFileName = "oracle_panel_session_log.txt"
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
