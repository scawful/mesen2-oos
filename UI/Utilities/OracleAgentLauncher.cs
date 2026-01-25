using Avalonia.Controls;
using Avalonia.Threading;
using Mesen.Windows;
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	public static class OracleAgentLauncher
	{
		private const string GatewayScriptName = "oracle_agent_gateway.py";

		public static void RunGatewayAction(string action)
		{
			RunGatewayCommand(new[] { "action", action });
		}

		public static void StartGateway()
		{
			RunGatewayCommand(new[] { "serve", "--daemon" });
		}

		public static void StopGateway()
		{
			RunGatewayCommand(new[] { "stop" });
		}

		public static void GatewayStatus()
		{
			RunGatewayCommandWithOutput(new[] { "status" }, "Oracle Agent Gateway Status");
		}

		public static void OpenMesen2Root()
		{
			if(!TryGetMesen2Root(out string root, out string? error)) {
				ShowError(error ?? "Mesen2 OOS root not found.");
				return;
			}

			OpenPath(root);
		}

		public static void OpenOracleRoot()
		{
			if(!TryGetOracleRoot(out string root, out string? error)) {
				ShowError(error ?? "Oracle-of-Secrets root not found.");
				return;
			}

			OpenPath(root);
		}

		public static void OpenOracleRoms()
		{
			OpenOraclePath("Roms");
		}

		public static void OpenOracleDocs()
		{
			OpenOraclePath("Docs");
		}

		public static void OpenOracleSavestateLibrary()
		{
			OpenOraclePath(Path.Combine("Roms", "savestates"));
		}

		public static void OpenMesen2Doc(string relativePath)
		{
			if(!TryGetMesen2Root(out string root, out string? error)) {
				ShowError(error ?? "Mesen2 OOS root not found.");
				return;
			}

			string path = Path.Combine(root, relativePath);
			OpenPath(path);
		}

		private static void OpenOraclePath(string relativePath)
		{
			if(!TryGetOracleRoot(out string root, out string? error)) {
				ShowError(error ?? "Oracle-of-Secrets root not found.");
				return;
			}

			string path = Path.Combine(root, relativePath);
			OpenPath(path);
		}

		public static void OpenPath(string path)
		{
			if(string.IsNullOrWhiteSpace(path)) {
				ShowError("Path not set.");
				return;
			}

			string expanded = ExpandHome(path);
			if(!File.Exists(expanded) && !Directory.Exists(expanded)) {
				ShowError($"Path not found: {expanded}");
				return;
			}

			try {
				Process.Start(new ProcessStartInfo() {
					FileName = expanded,
					UseShellExecute = true,
					Verb = "open"
				});
			} catch(Exception ex) {
				ShowError($"Failed to open path: {ex.Message}");
			}
		}

		private static void RunGatewayCommand(string[] args)
		{
			if(!TryGetGatewayPath(out string gatewayPath, out string? oracleRoot, out string? error)) {
				ShowError(error ?? "Oracle Agent Gateway not found.");
				return;
			}

			string pythonExe = GetPythonExecutable();
			string arguments = BuildArguments(gatewayPath, args);

			ProcessStartInfo psi = new ProcessStartInfo() {
				FileName = pythonExe,
				Arguments = arguments,
				UseShellExecute = false,
				CreateNoWindow = true,
				WorkingDirectory = oracleRoot ?? Path.GetDirectoryName(gatewayPath) ?? string.Empty
			};

			try {
				Process.Start(psi);
			} catch(Exception ex) {
				ShowError($"Failed to run Oracle Agent Gateway: {ex.Message}");
			}
		}

		private static void RunGatewayCommandWithOutput(string[] args, string title)
		{
			if(!TryGetGatewayPath(out string gatewayPath, out string? oracleRoot, out string? error)) {
				ShowError(error ?? "Oracle Agent Gateway not found.");
				return;
			}

			string pythonExe = GetPythonExecutable();
			string arguments = BuildArguments(gatewayPath, args);

			ProcessStartInfo psi = new ProcessStartInfo() {
				FileName = pythonExe,
				Arguments = arguments,
				UseShellExecute = false,
				CreateNoWindow = true,
				RedirectStandardOutput = true,
				RedirectStandardError = true,
				WorkingDirectory = oracleRoot ?? Path.GetDirectoryName(gatewayPath) ?? string.Empty
			};

			Task.Run(async () => {
				try {
					using Process? proc = Process.Start(psi);
					if(proc == null) {
						ShowError("Failed to start Oracle Agent Gateway.");
						return;
					}

					Task<string> stdoutTask = proc.StandardOutput.ReadToEndAsync();
					Task<string> stderrTask = proc.StandardError.ReadToEndAsync();

					Task waitTask = proc.WaitForExitAsync();
					Task finished = await Task.WhenAny(waitTask, Task.Delay(2000)).ConfigureAwait(false);
					if(finished != waitTask && !proc.HasExited) {
						try {
							proc.Kill();
						} catch {
							// Ignore kill failures
						}
						await waitTask.ConfigureAwait(false);
					}

					string stdout = (await stdoutTask.ConfigureAwait(false)).Trim();
					string stderr = (await stderrTask.ConfigureAwait(false)).Trim();
					string message = BuildOutputMessage(stdout, stderr);

					Dispatcher.UIThread.Post(() => {
						Window? parent = ApplicationHelper.GetActiveOrMainWindow();
						OracleAgentOutputWindow wnd = ApplicationHelper.GetOrCreateUniqueWindow(parent as Control, () => new OracleAgentOutputWindow());
						wnd.Title = title;
						wnd.Output = message;
					});
				} catch(Exception ex) {
					ShowError($"Failed to run Oracle Agent Gateway: {ex.Message}");
				}
			});
		}

		private static string GetPythonExecutable()
		{
			string? envPython = Environment.GetEnvironmentVariable("OOS_PYTHON");
			if(!string.IsNullOrWhiteSpace(envPython)) {
				return ExpandHome(envPython);
			}

			return OperatingSystem.IsWindows() ? "python" : "python3";
		}

		private static bool TryGetGatewayPath(out string gatewayPath, out string? oracleRoot, out string? error)
		{
			gatewayPath = string.Empty;
			oracleRoot = null;
			error = null;

			string? envGateway = Environment.GetEnvironmentVariable("OOS_AGENT_GATEWAY_PATH")
				?? Environment.GetEnvironmentVariable("ORACLE_AGENT_GATEWAY_PATH");

			if(!string.IsNullOrWhiteSpace(envGateway)) {
				string expanded = ExpandHome(envGateway);
				if(File.Exists(expanded)) {
					gatewayPath = expanded;
					oracleRoot = Path.GetDirectoryName(Path.GetDirectoryName(expanded));
					return true;
				}
			}

			string? root = ResolveOracleRoot();
			if(root == null) {
				error = "Oracle-of-Secrets root not found. Set ORACLE_OF_SECRETS_ROOT or OOS_ROOT.";
				return false;
			}

			string candidate = Path.Combine(root, "scripts", GatewayScriptName);
			if(!File.Exists(candidate)) {
				error = $"Gateway script not found: {candidate}";
				return false;
			}

			gatewayPath = candidate;
			oracleRoot = root;
			return true;
		}

		private static string? ResolveOracleRoot()
		{
			string? envRoot = Environment.GetEnvironmentVariable("ORACLE_OF_SECRETS_ROOT")
				?? Environment.GetEnvironmentVariable("OOS_ROOT");

			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					return expanded;
				}
			}

			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string defaultRoot = Path.Combine(home, "src", "hobby", "oracle-of-secrets");
			return Directory.Exists(defaultRoot) ? defaultRoot : null;
		}

		private static bool TryGetMesen2Root(out string root, out string? error)
		{
			root = string.Empty;
			error = null;

			string? envRoot = Environment.GetEnvironmentVariable("MESEN2_OOS_ROOT")
				?? Environment.GetEnvironmentVariable("MESEN2_ROOT");

			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					root = expanded;
					return true;
				}
			}

			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string defaultRoot = Path.Combine(home, "src", "hobby", "mesen2-oos");
			if(Directory.Exists(defaultRoot)) {
				root = defaultRoot;
				return true;
			}

			error = "Mesen2 OOS root not found. Set MESEN2_OOS_ROOT or MESEN2_ROOT.";
			return false;
		}

		private static bool TryGetOracleRoot(out string root, out string? error)
		{
			root = string.Empty;
			error = null;

			string? envRoot = Environment.GetEnvironmentVariable("ORACLE_OF_SECRETS_ROOT")
				?? Environment.GetEnvironmentVariable("OOS_ROOT");

			if(!string.IsNullOrWhiteSpace(envRoot)) {
				string expanded = ExpandHome(envRoot);
				if(Directory.Exists(expanded)) {
					root = expanded;
					return true;
				}
			}

			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			string defaultRoot = Path.Combine(home, "src", "hobby", "oracle-of-secrets");
			if(Directory.Exists(defaultRoot)) {
				root = defaultRoot;
				return true;
			}

			error = "Oracle-of-Secrets root not found. Set ORACLE_OF_SECRETS_ROOT or OOS_ROOT.";
			return false;
		}

		private static string ExpandHome(string path)
		{
			if(path.StartsWith("~")) {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
				return Path.Combine(home, path.TrimStart('~').TrimStart(Path.DirectorySeparatorChar));
			}
			return path;
		}

		private static string BuildArguments(string gatewayPath, string[] args)
		{
			string Quote(string value)
			{
				return value.Contains(' ') ? $"\"{value}\"" : value;
			}

			string arguments = Quote(gatewayPath);
			foreach(string arg in args) {
				arguments += " " + Quote(arg);
			}

			return arguments;
		}

		private static void ShowError(string message)
		{
			Dispatcher.UIThread.Post(() => {
				MessageBox.Show(ApplicationHelper.GetActiveOrMainWindow(), message, "Oracle", MessageBoxButtons.OK, MessageBoxIcon.Error);
			});
		}

		private static string BuildOutputMessage(string stdout, string stderr)
		{
			StringBuilder sb = new StringBuilder();
			if(!string.IsNullOrWhiteSpace(stdout)) {
				sb.AppendLine("STDOUT:");
				sb.AppendLine(stdout.TrimEnd());
			}
			if(!string.IsNullOrWhiteSpace(stderr)) {
				if(sb.Length > 0) {
					sb.AppendLine();
				}
				sb.AppendLine("STDERR:");
				sb.AppendLine(stderr.TrimEnd());
			}
			if(sb.Length == 0) {
				sb.AppendLine("No output returned.");
			}
			return sb.ToString().TrimEnd();
		}
	}
}
