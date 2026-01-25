using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Mesen.Config;
using Mesen.Utilities;
using Mesen.ViewModels;

namespace Mesen.Windows
{
	public enum OracleControlCenterTab
	{
		Status,
		SaveStates,
		Overlays,
		Actions,
		Paths,
		Diagnostics
	}

	public class OracleControlCenterWindow : MesenWindow
	{
		private TabControl? _tabs;

		private OracleControlCenterViewModel ViewModel => (OracleControlCenterViewModel)DataContext!;

		public OracleControlCenterWindow()
		{
			InitializeComponent();
			_tabs = this.FindControl<TabControl>("Tabs");
			DataContext = new OracleControlCenterViewModel();
			ViewModel.RefreshStateLibrarySummary();
			_ = ViewModel.RefreshStatusAsync();
		}

		private void InitializeComponent()
		{
			AvaloniaXamlLoader.Load(this);
		}

		public void SelectTab(OracleControlCenterTab tab)
		{
			if(_tabs == null) {
				return;
			}

			_tabs.SelectedIndex = tab switch {
				OracleControlCenterTab.Status => 0,
				OracleControlCenterTab.SaveStates => 1,
				OracleControlCenterTab.Overlays => 2,
				OracleControlCenterTab.Actions => 3,
				OracleControlCenterTab.Paths => 4,
				OracleControlCenterTab.Diagnostics => 5,
				_ => 0
			};
		}

		private async void OnRefreshStatusClick(object? sender, RoutedEventArgs e)
		{
			await ViewModel.RefreshStatusAsync();
		}

		private async void OnSaveLabeledStateClick(object? sender, RoutedEventArgs e)
		{
			await OracleStateActions.SaveLabeledStateAsync(this);
			ViewModel.RefreshStateLibrarySummary();
		}

		private void OnOpenStateLibraryClick(object? sender, RoutedEventArgs e)
		{
			ApplicationHelper.GetOrCreateUniqueWindow(this, () => new StateLibraryWindow());
		}

		private void OnOpenStateLibraryFolderClick(object? sender, RoutedEventArgs e)
		{
			OracleAgentLauncher.OpenOracleSavestateLibrary();
		}

		private void OnToggleOverlayClick(object? sender, RoutedEventArgs e)
		{
			if(sender is Button button && button.Tag is string property) {
				OracleOverlayController.Toggle(property);
			}
		}

		private void OnGatewayActionClick(object? sender, RoutedEventArgs e)
		{
			if(sender is Button button && button.Tag is string action) {
				OracleAgentLauncher.RunGatewayAction(action);
			}
		}

		private void OnGatewayActionWithOutputClick(object? sender, RoutedEventArgs e)
		{
			if(sender is Button button && button.Tag is string action) {
				string title = button.Content?.ToString() ?? "Oracle Output";
				OracleAgentLauncher.RunGatewayActionWithOutput(action, title);
			}
		}

		private void OnGatewayStartClick(object? sender, RoutedEventArgs e)
		{
			OracleAgentLauncher.StartGateway();
		}

		private void OnGatewayStopClick(object? sender, RoutedEventArgs e)
		{
			OracleAgentLauncher.StopGateway();
		}

		private void OnGatewayStatusClick(object? sender, RoutedEventArgs e)
		{
			OracleAgentLauncher.GatewayStatus();
		}

		private void OnCopySocketClick(object? sender, RoutedEventArgs e)
		{
			if(!string.IsNullOrWhiteSpace(ViewModel.SocketPath)) {
				Clipboard?.SetTextAsync(ViewModel.SocketPath);
			}
		}

		private void OnOpenPathClick(object? sender, RoutedEventArgs e)
		{
			if(sender is not Button button || button.Tag is not string action) {
				return;
			}

			switch(action) {
				case "oracle_root":
					OracleAgentLauncher.OpenOracleRoot();
					break;
				case "oracle_roms":
					OracleAgentLauncher.OpenOracleRoms();
					break;
				case "oracle_savestates":
					OracleAgentLauncher.OpenOracleSavestateLibrary();
					break;
				case "oracle_docs":
					OracleAgentLauncher.OpenOracleDocs();
					break;
				case "mesen_root":
					OracleAgentLauncher.OpenMesen2Root();
					break;
				case "mesen_socket_doc":
					OracleAgentLauncher.OpenMesen2Doc(System.IO.Path.Combine("docs", "Socket_API_Reference.md"));
					break;
				case "mesen_agent_doc":
					OracleAgentLauncher.OpenMesen2Doc(System.IO.Path.Combine("docs", "Agent_Integration_Guide.md"));
					break;
				case "mesen_debug_doc":
					OracleAgentLauncher.OpenMesen2Doc(System.IO.Path.Combine("docs", "Mesen2_Fork_Debugging.md"));
					break;
				case "mesen_savestate_folder":
					OracleAgentLauncher.OpenPath(ConfigManager.SaveStateFolder);
					break;
				case "model_catalog":
					OracleAgentLauncher.OpenPath("~/src/docs/MODEL_CATALOG.md");
					break;
				case "integration_plan":
					OracleAgentLauncher.OpenPath("~/src/docs/zelda-model-integration-plan.md");
					break;
				case "vscode_models":
					OracleAgentLauncher.OpenPath("~/src/docs/vscode-local-models.md");
					break;
				case "models_dir":
					OracleAgentLauncher.OpenPath("~/models");
					break;
				case "afs_repo":
					OracleAgentLauncher.OpenPath("~/src/lab/afs");
					break;
				case "afs_scawful_repo":
					OracleAgentLauncher.OpenPath("~/src/lab/afs-scawful");
					break;
				case "chat_registry":
					OracleAgentLauncher.OpenPath("~/src/lab/afs-scawful/config/chat_registry.toml");
					break;
			}
		}

		private void OnCloseClick(object? sender, RoutedEventArgs e)
		{
			Close();
		}
	}
}
