using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.ViewModels;

namespace Mesen.Windows
{
	public class OracleSaveDataEditorWindow : MesenWindow
	{
		private OracleSaveDataEditorViewModel ViewModel => (OracleSaveDataEditorViewModel)DataContext!;

		public OracleSaveDataEditorWindow()
		{
			InitializeComponent();
			DataContext = new OracleSaveDataEditorViewModel();
			_ = ViewModel.LoadAsync();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private async void OnRefreshClick(object? sender, RoutedEventArgs e)
		{
			await ViewModel.LoadAsync();
		}

		private async void OnApplyClick(object? sender, RoutedEventArgs e)
		{
			await ViewModel.ApplyAsync();
		}

		private async void OnPresetClick(object? sender, RoutedEventArgs e)
		{
			if(sender is Button button && button.Tag is string presetId) {
				await ViewModel.ApplyPresetAsync(presetId);
			}
		}

		private void OnCloseClick(object? sender, RoutedEventArgs e)
		{
			Close();
		}
	}
}
