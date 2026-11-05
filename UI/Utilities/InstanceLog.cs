using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	/// <summary>
	/// Append-only lifecycle log in the profile folder (instance.log), so an instance that
	/// disappears leaves a record of how: quit request, window close, signal, crash or an
	/// exit nobody asked for (Force Quit, kill).
	/// </summary>
	public static class InstanceLog
	{
		private const long MaxSize = 1024 * 1024;
		private static readonly object _lock = new();
		private static string? _path;
		private static bool _quitRequested;
		private static PosixSignalRegistration? _sigHup;
		private static PosixSignalRegistration? _sigQuit;

		public static void Start(string homeFolder, string[] args, bool headless)
		{
			try {
				_path = Path.Combine(homeFolder, "instance.log");
				if(File.Exists(_path) && new FileInfo(_path).Length > MaxSize) {
					File.Move(_path, _path + ".1", true);
				}
			} catch {
				//Logging must never stop the emulator from starting
			}

			Write("");
			Write($"start pid={Environment.ProcessId} headless={headless} instance={Environment.GetEnvironmentVariable("MESEN2_INSTANCE") ?? "-"} socket={Environment.GetEnvironmentVariable("MESEN2_SOCKET_PATH") ?? "-"}");
			Write($"exe={Program.ExePath} args=[{string.Join(" ", args)}]");

			AppDomain.CurrentDomain.UnhandledException += (s, e) => Write("unhandled exception (terminating=" + e.IsTerminating + "): " + e.ExceptionObject);
			TaskScheduler.UnobservedTaskException += (s, e) => Write("unobserved task exception: " + e.Exception);
			AppDomain.CurrentDomain.ProcessExit += (s, e) => {
				string reason = _quitRequested ? "" : " (no quit request seen: Force Quit, kill or a native exit)";
				Write($"process exit code={Environment.ExitCode}{reason}");
			};
			_sigHup = PosixSignalRegistration.Create(PosixSignal.SIGHUP, ctx => Write("received SIGHUP"));
			_sigQuit = PosixSignalRegistration.Create(PosixSignal.SIGQUIT, ctx => Write("received SIGQUIT"));
		}

		/// <summary>Records that the app is shutting down on purpose (window closed, quit, stop signal).</summary>
		public static void QuitRequested(string reason)
		{
			_quitRequested = true;
			Write("quit requested: " + reason);
		}

		public static void Write(string message)
		{
			if(_path == null) {
				return;
			}

			string line = message.Length == 0 ? "----" : $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{Environment.ProcessId}] {message}";
			lock(_lock) {
				try {
					File.AppendAllText(_path, line + Environment.NewLine);
				} catch {
					//Ignore logging failures (disk full, folder removed, etc.)
				}
			}
		}
	}
}
