using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using System.Threading.Tasks;

namespace Mesen.Windows
{
	public partial class TextInputWindow : MesenWindow
	{
		public string Result { get; private set; } = "";

		public TextInputWindow()
		{
			InitializeComponent();
		}

		public TextInputWindow(string prompt, string defaultText = "") : this()
		{
			this.FindControl<TextBlock>("PromptText")!.Text = prompt;
			this.FindControl<TextBox>("InputText")!.Text = defaultText;
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		private void OnOkClick(object? sender, RoutedEventArgs e)
		{
			Result = this.FindControl<TextBox>("InputText")!.Text ?? "";
			Close(true);
		}

		private void OnCancelClick(object? sender, RoutedEventArgs e)
		{
			Close(false);
		}

		public static async Task<string?> ShowDialog(Window owner, string prompt, string defaultText = "")
		{
			var window = new TextInputWindow(prompt, defaultText);
			bool? result = await window.ShowDialog<bool>(owner);
			return result == true ? window.Result : null;
		}
	}
}
