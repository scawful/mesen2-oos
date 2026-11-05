using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.ViewModels;

namespace Mesen.Views
{
	/// <summary>Non-modal "new ROM available" banner docked above the game.</summary>
	public class RomOfferBannerView : UserControl
	{
		private RomOfferViewModel? ViewModel => DataContext as RomOfferViewModel;

		public RomOfferBannerView()
		{
			InitializeComponent();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void OnLoadNowClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.LoadNow();
		}

		private void OnLaterClick(object? sender, RoutedEventArgs e)
		{
			ViewModel?.Later();
		}
	}
}
