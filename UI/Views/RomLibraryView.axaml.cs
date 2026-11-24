using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.Config;
using Mesen.Utilities;
using Mesen.ViewModels;
using System;
using System.Diagnostics;

namespace Mesen.Views
{
	public class RomLibraryView : UserControl
	{
		public RomLibraryView()
		{
			InitializeComponent();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private RomLibraryViewModel? Model => DataContext as RomLibraryViewModel;

		private void OnPlayClick(object? sender, RoutedEventArgs e) => Model?.Play();
		private void OnDoubleTapped(object? sender, TappedEventArgs e) => Model?.Play();
		private void OnRefreshClick(object? sender, RoutedEventArgs e) => Model?.Refresh();

		private async void OnOpenFileClick(object? sender, RoutedEventArgs e)
		{
			string? filename = await FileDialogHelper.OpenFile(null, VisualRoot, FileDialogHelper.RomExt);
			if(filename != null) {
				LoadRomHelper.LoadFile(filename);
			}
		}

		private void OnRevealClick(object? sender, RoutedEventArgs e)
		{
			string? path = Model?.Selected?.RomPath;
			if(path == null) {
				return;
			}
			try {
				if(OperatingSystem.IsMacOS()) {
					ProcessStartInfo info = new("open");
					info.ArgumentList.Add("-R");
					info.ArgumentList.Add(path);
					Process.Start(info);
				} else {
					Process.Start(new ProcessStartInfo(System.IO.Path.GetDirectoryName(path) ?? path) { UseShellExecute = true });
				}
			} catch(Exception) {
				//No file manager available
			}
		}
	}
}
