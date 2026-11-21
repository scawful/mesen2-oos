using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using Mesen.Utilities;

internal static class PlaySessionTest
{
	private static int checks;

	private static void Check(bool ok, string message)
	{
		if(!ok) throw new Exception(message);
		checks++;
	}

	public static int Main()
	{
		string root = Path.Combine(Path.GetTempPath(), "mesen-play-session-" + Guid.NewGuid().ToString("N"));
		Directory.CreateDirectory(Path.Combine(root, "Data", "debug"));
		File.WriteAllText(Path.Combine(root, "Data", "debug", "save_state_library.json"),
			"{\"library_root\":\"Roms/SaveStates/library\",\"entries\":[" +
			"{\"id\":\"pre_d6\",\"state_path\":\"Roms/SaveStates/library/pre_d6.mss\",\"meta\":{\"label\":\"D6 gate\"}}," +
			"{\"id\":\"named\",\"path\":\"named.mss\",\"label\":\"Named\"}," +
			"{\"id\":\"escape\",\"path\":\"../secret.mss\",\"label\":\"nope\"}]}");
		File.WriteAllText(Path.Combine(root, "Data", "debug", "checkpoints.json"),
			"{\"checkpoints\":[{\"id\":\"intro-bed\",\"label\":\"House\",\"location\":{\"entrance\":\"0x01\"}}],\"todo\":[{\"id\":\"later\"}]}");
		File.WriteAllText(Path.Combine(root, "Data", "debug", "warps.json"),
			"{\"entrances\":[{\"id\":\"0x01\",\"name\":\"Link's House\"}],\"areas\":[{\"area\":\"0x33\",\"name\":\"Beach\",\"landing\":\"outside\"}]}");

		var jumps = PlaySession.LoadJumps(root);
		PlayJump? gate = jumps.FirstOrDefault(j => j.CatalogId == "savestate:pre_d6");
		Check(gate != null && gate.Label == "D6 gate", "savestate label");
		Check(PlaySession.ResolveStateFile(root, gate!)!.Replace('\\', '/').EndsWith("Roms/SaveStates/library/pre_d6.mss"), "resolved path");
		PlayJump? named = jumps.FirstOrDefault(j => j.CatalogId == "savestate:named");
		Check(named != null && named.StatePath == "Roms/SaveStates/library/named.mss", "bare filename joins library root");
		PlayJump? escaped = jumps.FirstOrDefault(j => j.CatalogId == "savestate:escape");
		Check(escaped != null && escaped.StatePath == "", "dotdot path dropped");
		Check(jumps.Any(j => j.CatalogId == "checkpoint:intro-bed" && j.Location == "entrance 0x01"), "checkpoint");
		Check(!jumps.Any(j => j.SourceId == "later"), "todo checkpoint omitted");
		Check(jumps.Any(j => j.CatalogId == "warp-area:0x33" && j.Kind == "warp-area" && j.Location == "outside"), "area warp");

		string boards = Path.Combine(root, "boards");
		PlaySession.AppendFeedback(boards, "needs_changes", "bug", "clipped", PlaySession.SourceDesktop, gate);
		PlaySession.AppendFeedback(boards, "comment", "review", "reads well", PlaySession.SourceYaze, named);
		string file = Path.Combine(boards, "in-play", "feedback-in-play.json");
		using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(file));
		Check(doc.RootElement.GetProperty("schema").GetString() == PlaySession.FeedbackSchema, "feedback schema");
		Check(doc.RootElement.GetProperty("play_schema").GetString() == PlaySession.PlaySchema, "play schema");
		Check(doc.RootElement.GetProperty("id").GetString() == "in-play", "board id");
		Check(doc.RootElement.GetProperty("history").GetArrayLength() == 2, "two history rows");
		Check(doc.RootElement.GetProperty("status").GetString() == "needs_changes", "bug stays the decision");
		Check(doc.RootElement.GetProperty("history")[1].GetProperty("play").GetProperty("jump").GetProperty("id").GetString() == "named", "review jump id");
		Check(doc.RootElement.GetProperty("history")[0].GetProperty("play").GetProperty("source").GetString() == PlaySession.SourceDesktop, "bug source");

		bool rejected = false;
		try {
			PlaySession.AppendFeedback(boards, "needs_changes", "bug", "  ", PlaySession.SourceAndroid, null);
		} catch (ArgumentException) {
			rejected = true;
		}
		Check(rejected, "blank bug note rejected");

		Console.WriteLine("PlaySessionTest OK (" + checks + " checks)");
		return 0;
	}
}
