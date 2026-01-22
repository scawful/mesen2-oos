using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Mesen.Config;
using Mesen.Debugger.Utilities;
using Mesen.Debugger.ViewModels;
using Mesen.Interop;
using System;

namespace Mesen.Debugger.Windows
{
	public class StateInspectorWindow : MesenWindow, INotificationHandler
	{
		private StateInspectorWindowViewModel _model;

		[Obsolete("For designer only")]
		public StateInspectorWindow() : this(new()) { }

		public StateInspectorWindow(StateInspectorWindowViewModel model)
		{
			InitializeComponent();
#if DEBUG
			this.AttachDevTools();
#endif

			_model = model;
			DataContext = model;

			if(Design.IsDesignMode) {
				return;
			}

			_model.InitMenu(this);
			_model.Config.LoadWindowSettings(this);
		}

		protected override void OnClosing(WindowClosingEventArgs e)
		{
			base.OnClosing(e);
			_model.Config.SaveWindowSettings(this);
			ConfigManager.Config.Debug.StateInspector = _model.Config;
		}

		public void ProcessNotification(NotificationEventArgs e)
		{
			ToolRefreshHelper.ProcessNotification(this, e, _model.RefreshTiming, _model, _model.RefreshData);
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}
	}
}
