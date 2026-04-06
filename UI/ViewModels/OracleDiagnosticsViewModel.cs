using System;
using System.Threading.Tasks;
using Mesen.Utilities;
using ReactiveUI;
using ReactiveUI.Fody.Helpers;

namespace Mesen.ViewModels
{
	public class OracleDiagnosticsViewModel : ViewModelBase
	{
		[Reactive] public string DiagnosticsOutput { get; set; } = "";
		[Reactive] public bool IsRunning { get; private set; }

		public void AppendOutput(string text)
		{
			string sep = string.IsNullOrEmpty(DiagnosticsOutput) ? "" : Environment.NewLine + "---" + Environment.NewLine;
			DiagnosticsOutput += sep + $"[{DateTime.Now:HH:mm:ss}] {text}";
		}

		public async Task RunZsowStatusAsync()
		{
			if(IsRunning) return;
			IsRunning = true;
			try {
				string? result = await AgentLauncher.RunGatewayActionWithOutputAsync("check_zsow_status");
				if(result != null) {
					AppendOutput("ZSCustomOverworld Status:" + Environment.NewLine + result);
				}
			} finally {
				IsRunning = false;
			}
		}

		public async Task RunDayNightStatusAsync()
		{
			if(IsRunning) return;
			IsRunning = true;
			try {
				string? result = await AgentLauncher.RunGatewayActionWithOutputAsync("check_day_night");
				if(result != null) {
					AppendOutput("Day/Night Status:" + Environment.NewLine + result);
				}
			} finally {
				IsRunning = false;
			}
		}

		public void ClearOutput()
		{
			DiagnosticsOutput = "";
		}
	}
}
