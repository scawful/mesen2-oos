using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.ViewModels;

namespace Mesen.Views
{
	/// <summary>One-line Oracle status strip docked above the game.</summary>
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
	}
}
