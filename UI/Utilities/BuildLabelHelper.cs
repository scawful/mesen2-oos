using System;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Mesen.Utilities
{
	/// <summary>
	/// Finds a playtest build label (e.g. "v0.9.0-b17") for a ROM file, for the player window title.
	/// </summary>
	public static class BuildLabelHelper
	{
		private static readonly Regex _versionInName = new(@"v\d+\.\d+\.\d+-b\d+", RegexOptions.Compiled);

		/// <summary>
		/// Label from latest.json next to the ROM (written by Oracle's stage_playtest.py) when its sha256
		/// matches the ROM file, else from a "v1.2.3-b4" part of the file name, else empty.
		/// </summary>
		public static string Find(string romPath)
		{
			if(string.IsNullOrWhiteSpace(romPath) || !File.Exists(romPath)) {
				return "";
			}

			string manifestPath = Path.Combine(Path.GetDirectoryName(romPath) ?? "", "latest.json");
			try {
				if(File.Exists(manifestPath)) {
					using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
					JsonElement root = doc.RootElement;
					string sha256 = root.TryGetProperty("sha256", out JsonElement hash) ? hash.GetString() ?? "" : "";
					if(sha256.Length > 0 && string.Equals(sha256, GetSha256(romPath), StringComparison.OrdinalIgnoreCase)) {
						if(root.TryGetProperty("version", out JsonElement version) && root.TryGetProperty("build", out JsonElement build)) {
							return $"v{version}-b{build}";
						}
						if(root.TryGetProperty("name", out JsonElement name)) {
							return name.GetString() ?? "";
						}
					}
				}
			} catch(Exception ex) when(ex is IOException || ex is JsonException || ex is UnauthorizedAccessException || ex is InvalidOperationException) {
				//Unreadable manifest: fall back to the file name
			}

			Match match = _versionInName.Match(Path.GetFileName(romPath));
			return match.Success ? match.Value : "";
		}

		private static string GetSha256(string path)
		{
			using FileStream stream = File.OpenRead(path);
			return Convert.ToHexString(SHA256.HashData(stream));
		}
	}
}
