using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.Utilities;
using Mesen.ViewModels;
using System;
using System.Diagnostics;
using System.IO;

namespace Mesen.Windows
{
	/// <summary>Capture window: one-click recording, framing presets and the clip list.</summary>
	public class VideoRecordWindow : MesenWindow
	{
		public VideoRecordWindow()
		{
			InitializeComponent();
#if DEBUG
			this.AttachDevTools();
#endif
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private VideoRecordConfigViewModel Model => (VideoRecordConfigViewModel)DataContext!;

		protected override void OnClosed(EventArgs e)
		{
			base.OnClosed(e);
			Model.Dispose();
		}

		private void OnRecordClick(object sender, RoutedEventArgs e) => Model.ToggleRecording();

		private async void OnChangeFolderClick(object sender, RoutedEventArgs e)
		{
			string? folder = await FileDialogHelper.OpenFolder(VisualRoot);
			if(!string.IsNullOrWhiteSpace(folder)) {
				Model.SetClipsFolder(folder);
			}
		}

		private void OnOpenFolderClick(object sender, RoutedEventArgs e) => ShellOpen(Model.ClipsFolder, reveal: false);
		private void OnRefreshClick(object sender, RoutedEventArgs e) => Model.RefreshClips();

		private void OnPlayClick(object sender, RoutedEventArgs e)
		{
			if(Model.SelectedClip != null) {
				ShellOpen(Model.SelectedClip.Path, reveal: false);
			}
		}

		private void OnClipDoubleTapped(object? sender, TappedEventArgs e) => OnPlayClick(this, new RoutedEventArgs());

		private void OnRevealClick(object sender, RoutedEventArgs e)
		{
			if(Model.SelectedClip != null) {
				ShellOpen(Model.SelectedClip.Path, reveal: true);
			}
		}

		private void OnCopyPathClick(object sender, RoutedEventArgs e)
		{
			if(Model.SelectedClip != null) {
				Clipboard?.SetTextAsync(Model.SelectedClip.Path);
			}
		}

		private static void ShellOpen(string path, bool reveal)
		{
			try {
				if(OperatingSystem.IsMacOS()) {
					ProcessStartInfo info = new("open");
					if(reveal) {
						info.ArgumentList.Add("-R");
					}
					info.ArgumentList.Add(path);
					Process.Start(info);
				} else {
					string target = reveal ? (Path.GetDirectoryName(path) ?? path) : path;
					Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
				}
			} catch(Exception) {
				//Nothing registered to open this file type
			}
		}
	}
}
