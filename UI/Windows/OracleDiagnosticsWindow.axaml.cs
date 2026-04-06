using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Platform.Storage;
using Mesen.ViewModels;

namespace Mesen.Windows
{
	public class OracleDiagnosticsWindow : MesenWindow
	{
		private OracleDiagnosticsViewModel ViewModel => (OracleDiagnosticsViewModel)DataContext!;

		public OracleDiagnosticsWindow()
		{
			InitializeComponent();
			DataContext = new OracleDiagnosticsViewModel();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private async void OnRunZsowClick(object? sender, RoutedEventArgs e)
		{
			await ViewModel.RunZsowStatusAsync();
		}

		private async void OnRunDayNightClick(object? sender, RoutedEventArgs e)
		{
			await ViewModel.RunDayNightStatusAsync();
		}

		private async void OnCopyClick(object? sender, RoutedEventArgs e)
		{
			var topLevel = TopLevel.GetTopLevel(this);
			if(topLevel?.Clipboard != null && !string.IsNullOrEmpty(ViewModel.DiagnosticsOutput)) {
				await topLevel.Clipboard.SetTextAsync(ViewModel.DiagnosticsOutput);
			}
		}

		private async void OnSaveLogClick(object? sender, RoutedEventArgs e)
		{
			var topLevel = TopLevel.GetTopLevel(this);
			if(topLevel?.StorageProvider == null) return;

			var file = await topLevel.StorageProvider.SaveFilePickerAsync(new Avalonia.Platform.Storage.FilePickerSaveOptions() {
				Title = "Save diagnostics log",
				DefaultExtension = "txt",
				SuggestedFileName = "oracle_diagnostics.txt"
			});
			if(file != null && !string.IsNullOrEmpty(ViewModel.DiagnosticsOutput)) {
				await using var stream = await file.OpenWriteAsync();
				using var writer = new System.IO.StreamWriter(stream);
				await writer.WriteAsync(ViewModel.DiagnosticsOutput);
			}
		}

		private void OnClearClick(object? sender, RoutedEventArgs e)
		{
			ViewModel.ClearOutput();
		}
	}
}
