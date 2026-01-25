using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.ViewModels;

namespace Mesen.Windows
{
	public partial class StateLibraryWindow : MesenWindow
	{
		public StateLibraryWindow()
		{
			InitializeComponent();
			DataContext = new StateLibraryViewModel();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void OnCloseClick(object? sender, RoutedEventArgs e)
		{
			Close();
		}

		private void OnListBoxDoubleTapped(object? sender, TappedEventArgs e)
		{
			var vm = DataContext as StateLibraryViewModel;
			if (vm?.SelectedState != null) {
				vm.LoadSelected();
				Close();
			}
		}
	}
}
