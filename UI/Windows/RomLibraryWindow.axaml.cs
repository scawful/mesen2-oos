using Avalonia;
using Avalonia.Markup.Xaml;
using Mesen.ViewModels;

namespace Mesen.Windows
{
	public class RomLibraryWindow : MesenWindow
	{
		public RomLibraryWindow()
		{
			RomLibraryViewModel model = new();
			model.AfterPlay = Close;
			DataContext = model;
			InitializeComponent();
#if DEBUG
			this.AttachDevTools();
#endif
			model.Refresh();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}
	}
}
