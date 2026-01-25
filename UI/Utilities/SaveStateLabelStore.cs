using System;
using System.IO;

namespace Mesen.Utilities
{
	public static class SaveStateLabelStore
	{
		public const string LabelExtension = ".label";

		public static string GetLabelPath(string statePath)
		{
			return statePath + LabelExtension;
		}

		public static string? TryGetLabel(string statePath)
		{
			if(string.IsNullOrWhiteSpace(statePath)) {
				return null;
			}

			try {
				string labelPath = GetLabelPath(statePath);
				if(!File.Exists(labelPath)) {
					return null;
				}

				string label = File.ReadAllText(labelPath).Trim();
				return string.IsNullOrWhiteSpace(label) ? null : label;
			} catch {
				return null;
			}
		}

		public static bool TryWriteLabel(string statePath, string? label)
		{
			if(string.IsNullOrWhiteSpace(statePath)) {
				return false;
			}

			try {
				string labelPath = GetLabelPath(statePath);
				string normalized = label?.Trim() ?? string.Empty;
				if(string.IsNullOrWhiteSpace(normalized)) {
					if(File.Exists(labelPath)) {
						File.Delete(labelPath);
					}
					return true;
				}

				File.WriteAllText(labelPath, normalized);
				return true;
			} catch {
				return false;
			}
		}
	}
}
