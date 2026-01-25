using Mesen.Interop;

namespace Mesen.Utilities
{
	public static class OracleOverlayController
	{
		private const string OverlayScriptName = "OracleOverlayCommand";

		public static void Toggle(string property)
		{
			string script = $"if hudState then hudState.{property} = not hudState.{property} end";
			RunLuaSnippet(script);
		}

		public static void RunLuaSnippet(string content)
		{
			// LoadScript(name, path, content, scriptId)
			// scriptId = -1 ensures a transient script without overwriting existing scripts.
			DebugApi.LoadScript(OverlayScriptName, Program.OriginalFolder, content, -1);
		}
	}
}
