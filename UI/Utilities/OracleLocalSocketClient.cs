using Avalonia.Controls;
using Avalonia.Threading;
using Mesen.Windows;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	/// <summary>
	/// Direct in-process Unix-socket client for Mesen's own SocketServer, replacing the
	/// retired Python agent_gateway.py wrapper for Oracle menu Socket Tools and Health checks.
	/// </summary>
	public static class OracleLocalSocketClient
	{
		private const int SocketTimeoutMs = 5000;

		private static readonly JsonSerializerOptions PrettyJsonOptions = new() {
			WriteIndented = true,
			Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
		};

		public static void RunSocketCommandWithOutput(string commandType, IReadOnlyDictionary<string, string>? args, string title)
		{
			Task.Run(async () => {
				string output = await ExecuteFormattedAsync(commandType, args).ConfigureAwait(false);
				Dispatcher.UIThread.Post(() => {
					Window? parent = ApplicationHelper.GetActiveOrMainWindow();
					OracleAgentOutputWindow wnd = ApplicationHelper.GetOrCreateUniqueWindow(parent as Control, () => new OracleAgentOutputWindow());
					wnd.Title = title;
					wnd.Output = output;
				});
			});
		}

		public static async Task<string> ExecuteFormattedAsync(string commandType, IReadOnlyDictionary<string, string>? args = null)
		{
			string? socketPath = OracleAgentStatus.GetLocalSocketPath();
			if(string.IsNullOrWhiteSpace(socketPath)) {
				return "Local Mesen2 socket is not active for this instance.";
			}

			JsonObject request = new() {
				["type"] = commandType
			};
			if(args != null) {
				foreach(KeyValuePair<string, string> pair in args) {
					request[pair.Key] = pair.Value;
				}
			}

			string payload = request.ToJsonString() + "\n";

			try {
				using Socket socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
				socket.SendTimeout = SocketTimeoutMs;
				socket.ReceiveTimeout = SocketTimeoutMs;
				await socket.ConnectAsync(new UnixDomainSocketEndPoint(socketPath)).ConfigureAwait(false);

				using NetworkStream stream = new NetworkStream(socket, ownsSocket: false);
				byte[] requestBytes = Encoding.UTF8.GetBytes(payload);
				await stream.WriteAsync(requestBytes).ConfigureAwait(false);
				await stream.FlushAsync().ConfigureAwait(false);

				using StreamReader reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, bufferSize: 4096, leaveOpen: true);
				string? responseLine = await reader.ReadLineAsync().ConfigureAwait(false);
				if(string.IsNullOrWhiteSpace(responseLine)) {
					return "Empty response from local Mesen2 socket.";
				}

				return FormatResponse(commandType, socketPath, responseLine);
			} catch(Exception ex) {
				return $"Socket command '{commandType}' failed ({socketPath}): {ex.Message}";
			}
		}

		private static string FormatResponse(string commandType, string socketPath, string responseLine)
		{
			try {
				JsonNode? node = JsonNode.Parse(responseLine);
				if(node is not JsonObject obj) {
					return responseLine;
				}

				bool success = obj["success"]?.GetValue<bool>() ?? false;
				StringBuilder sb = new StringBuilder();
				sb.AppendLine($"Command: {commandType} ({(success ? "OK" : "ERROR")})");
				sb.AppendLine($"Socket:  {socketPath}");
				sb.AppendLine();

				if(success) {
					JsonNode? data = obj["data"];
					if(data == null) {
						sb.Append("null");
					} else if(data is JsonValue val && val.TryGetValue<string>(out string? strValue)) {
						sb.Append(strValue);
					} else {
						sb.Append(data.ToJsonString(PrettyJsonOptions));
					}
				} else {
					string error = obj["error"]?.ToString() ?? "Unknown error";
					string codeSuffix = obj["errorCode"] != null ? $" (errorCode {obj["errorCode"]})" : "";
					sb.Append($"Error{codeSuffix}: {error}");
				}

				return sb.ToString();
			} catch {
				return responseLine;
			}
		}
	}
}
