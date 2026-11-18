using System;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Mesen.Interop;

namespace Mesen.Utilities
{
	public sealed record class OracleStatusSnapshot
	{
		public bool MesenRunning { get; init; }
		public bool MesenPaused { get; init; }
		public string? RomName { get; init; }
		public bool SocketFound { get; init; }
		public bool? YazeRunning { get; init; }
		public string? SocketPath { get; init; }
		public DateTimeOffset Timestamp { get; init; }

		public static OracleStatusSnapshot Empty()
		{
			return new OracleStatusSnapshot() {
				MesenRunning = false,
				MesenPaused = false,
				RomName = null,
				SocketFound = false,
				YazeRunning = null,
				SocketPath = null,
				Timestamp = DateTimeOffset.MinValue
			};
		}
	}

	public static class OracleAgentStatus
	{
		public static Task<OracleStatusSnapshot> FetchAsync()
		{
			return Task.Run(() => {
				bool running = EmuApi.IsRunning();
				bool paused = running && EmuApi.IsPaused();
				string? romName = null;
				if(running) {
					RomInfo rom = EmuApi.GetRomInfo();
					if(rom.Format != RomFormat.Unknown) {
						romName = rom.GetRomName();
					}
				}

				string? socketPath = GetLocalSocketPath();
				bool socketFound = !string.IsNullOrWhiteSpace(socketPath);
				bool? yazeRunning = GetYazeRunning();

				return new OracleStatusSnapshot() {
					MesenRunning = running,
					MesenPaused = paused,
					RomName = romName,
					SocketFound = socketFound,
					YazeRunning = yazeRunning,
					SocketPath = socketPath,
					Timestamp = DateTimeOffset.Now
				};
			});
		}

		/// <summary>
		/// Resolve THIS Mesen2 process's Unix socket path without probing other running instances.
		/// Matches SocketServer::SocketServer() in Core/Shared/SocketServer.cpp.
		/// </summary>
		public static string? GetLocalSocketPath()
		{
			if(OperatingSystem.IsWindows()) {
				return null;
			}

			string? envPath = Environment.GetEnvironmentVariable("MESEN2_SOCKET_PATH");
			if(!string.IsNullOrWhiteSpace(envPath)) {
				return File.Exists(envPath) ? envPath : null;
			}

			int currentPid = Environment.ProcessId;
			string pidSocket = $"/tmp/mesen2-{currentPid}.sock";
			if(File.Exists(pidSocket)) {
				return pidSocket;
			}

			try {
				foreach(string statusPath in Directory.GetFiles("/tmp", "mesen2-*.status")) {
					try {
						using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(statusPath));
						if(doc.RootElement.TryGetProperty("pid", out JsonElement pidElem)
							&& pidElem.TryGetInt32(out int pid)
							&& pid == currentPid
							&& doc.RootElement.TryGetProperty("socketPath", out JsonElement socketElem)) {
							string? candidate = socketElem.GetString();
							if(!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate)) {
								return candidate;
							}
						}
					} catch {
						// Ignore malformed status files
					}
				}
			} catch {
				// Ignore /tmp enumeration failures
			}

			return null;
		}

		private static bool? GetYazeRunning()
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

			try {
				Process[] procs = Process.GetProcessesByName("yaze");
				bool anyAlive = false;
				foreach(Process proc in procs) {
					using(proc) {
						if(!anyAlive) {
							try {
								anyAlive = !proc.HasExited;
							} catch {
								anyAlive = true;
							}
						}
					}
				}
				if(anyAlive) {
					return true;
				}
			} catch {
				// Ignore process enumeration errors
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

				using Process proc = Process.GetProcessById(pid);
				return !proc.HasExited;
			} catch {
				return false;
			}
		}
	}
}
