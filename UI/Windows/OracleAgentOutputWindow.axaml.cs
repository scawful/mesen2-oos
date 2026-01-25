using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Data;
using Avalonia.Input;

namespace Mesen.Windows
{
	public class OracleAgentOutputWindow : MesenWindow
	{
		public static readonly StyledProperty<string> OutputProperty = AvaloniaProperty.Register<OracleAgentOutputWindow, string>(nameof(Output), "", defaultBindingMode: BindingMode.OneWay);

		public string Output
		{
			get { return GetValue(OutputProperty); }
			set { SetValue(OutputProperty, value); }
		}

		public OracleAgentOutputWindow()
		{
			InitializeComponent();
			AddHandler(InputElement.KeyDownEvent, OnPreviewKeyDown, RoutingStrategies.Tunnel, true);
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void Close_OnClick(object sender, RoutedEventArgs e)
		{
			Close();
		}

		private void OnPreviewKeyDown(object? sender, KeyEventArgs e)
		{
			if(e.Key == Key.Escape) {
				Close();
				e.Handled = true;
			}
		}
	}
}
