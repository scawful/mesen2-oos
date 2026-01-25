using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	public sealed record class OracleStatusSnapshot
	{
		public bool GatewayOnline { get; init; }
		public bool SocketFound { get; init; }
		public bool? YazeRunning { get; init; }
		public bool? Mesen2Connected { get; init; }
		public string? SocketPath { get; init; }
		public string? GatewayError { get; init; }
		public DateTimeOffset Timestamp { get; init; }

		public static OracleStatusSnapshot Empty()
		{
			return new OracleStatusSnapshot() {
				GatewayOnline = false,
				SocketFound = false,
				YazeRunning = null,
				Mesen2Connected = null,
				SocketPath = null,
				GatewayError = null,
				Timestamp = DateTimeOffset.MinValue
			};
		}
	}

	public static class OracleAgentStatus
	{
		private const int DefaultGatewayPort = 8765;
		private static readonly TimeSpan _timeout = TimeSpan.FromMilliseconds(350);

		public static async Task<OracleStatusSnapshot> FetchAsync()
		{
			OracleStatusSnapshot snapshot = OracleStatusSnapshot.Empty();
			int port = ResolveGatewayPort();

			try {
				using CancellationTokenSource cts = new CancellationTokenSource(_timeout);
				using HttpClient client = new HttpClient();
				HttpResponseMessage response = await client.GetAsync($"http://127.0.0.1:{port}/status", cts.Token).ConfigureAwait(false);
				if(response.IsSuccessStatusCode) {
					string json = await response.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
					OracleStatusSnapshot? parsed = ParseGatewayStatus(json);
					if(parsed != null) {
						snapshot = parsed;
					}
				}
			} catch(Exception ex) {
				snapshot = snapshot with { GatewayError = ex.Message };
			}

			string? socketPath = snapshot.SocketPath ?? GetLocalSocketPath();
			bool socketFound = !string.IsNullOrWhiteSpace(socketPath);
			bool? yazeRunning = snapshot.YazeRunning ?? GetYazeRunningFromPid();

			return new OracleStatusSnapshot() {
				GatewayOnline = snapshot.GatewayOnline,
				SocketFound = socketFound,
				YazeRunning = yazeRunning,
				Mesen2Connected = snapshot.Mesen2Connected,
				SocketPath = socketPath,
				GatewayError = snapshot.GatewayError,
				Timestamp = DateTimeOffset.Now
			};
		}

		private static OracleStatusSnapshot? ParseGatewayStatus(string json)
		{
			try {
				using JsonDocument doc = JsonDocument.Parse(json);
				if(!doc.RootElement.TryGetProperty("ok", out JsonElement okElem) || okElem.ValueKind != JsonValueKind.True) {
					return null;
				}

				bool? mesenConnected = null;
				bool? yazeRunning = null;
				string? socketPath = null;

				if(doc.RootElement.TryGetProperty("status", out JsonElement statusElem)) {
					if(statusElem.TryGetProperty("mesen2_connected", out JsonElement connectedElem)) {
						if(connectedElem.ValueKind == JsonValueKind.True || connectedElem.ValueKind == JsonValueKind.False) {
							mesenConnected = connectedElem.GetBoolean();
						}
					}

					if(statusElem.TryGetProperty("mesen2_sockets", out JsonElement socketsElem) && socketsElem.ValueKind == JsonValueKind.Array) {
						foreach(JsonElement socketElem in socketsElem.EnumerateArray()) {
							string? candidate = socketElem.GetString();
							if(!string.IsNullOrWhiteSpace(candidate)) {
								socketPath = candidate;
								break;
							}
						}
					}

					if(statusElem.TryGetProperty("yaze_running", out JsonElement yazeElem)) {
						if(yazeElem.ValueKind == JsonValueKind.True || yazeElem.ValueKind == JsonValueKind.False) {
							yazeRunning = yazeElem.GetBoolean();
						}
					}
				}

				return new OracleStatusSnapshot() {
					GatewayOnline = true,
					SocketFound = !string.IsNullOrWhiteSpace(socketPath),
					YazeRunning = yazeRunning,
					Mesen2Connected = mesenConnected,
					SocketPath = socketPath,
					GatewayError = null,
					Timestamp = DateTimeOffset.Now
				};
			} catch {
				return null;
			}
		}

		private static int ResolveGatewayPort()
		{
			string? envPort = Environment.GetEnvironmentVariable("OOS_AGENT_GATEWAY_PORT");
			if(int.TryParse(envPort, out int port)) {
				return port;
			}
			return DefaultGatewayPort;
		}

		private static string? GetLocalSocketPath()
		{
			if(OperatingSystem.IsWindows()) {
				return null;
			}

			string candidate = $"/tmp/mesen2-{Environment.ProcessId}.sock";
			return File.Exists(candidate) ? candidate : null;
		}

		private static bool? GetYazeRunningFromPid()
		{
			if(OperatingSystem.IsWindows()) {
				return null;
			}

			string[] pidFiles = {
				"/tmp/oos_yaze_service.pid",
				"/tmp/oos_yaze_gui.pid"
			};

			foreach(string pidFile in pidFiles) {
				if(IsPidRunning(pidFile)) {
					return true;
				}
			}

			return false;
		}

		private static bool IsPidRunning(string pidFile)
		{
			try {
				if(!File.Exists(pidFile)) {
					return false;
				}

				string text = File.ReadAllText(pidFile).Trim();
				if(!int.TryParse(text, out int pid)) {
					return false;
				}

				System.Diagnostics.Process proc = System.Diagnostics.Process.GetProcessById(pid);
				return !proc.HasExited;
			} catch {
				return false;
			}
		}
	}
}
