using ReactiveUI.Fody.Helpers;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.Config
{
	public class VideoRecordConfig : BaseConfig<VideoRecordConfig>
	{
		[Reactive] public VideoCodec Codec { get; set; } = VideoCodec.CSCD;
		[Reactive] public UInt32 CompressionLevel { get; set; } = 6;
		[Reactive] public bool RecordSystemHud { get; set; } = false;
		[Reactive] public bool RecordInputHud { get; set; } = false;

		[Reactive] public CaptureFormat Format { get; set; } = CaptureFormat.Mp4;
		[Reactive] public CaptureFraming Framing { get; set; } = CaptureFraming.FullScreen;
		[Reactive] public int Mp4Scale { get; set; } = 4;
		[Reactive] public string ClipsFolder { get; set; } = "";
	}

	public enum VideoCodec
	{
		None = 0,
		ZMBV = 1,
		CSCD = 2,
		GIF = 3
	}

	public enum CaptureFormat
	{
		Mp4 = 0,
		Avi = 1,
		Gif = 2
	}

	public enum CaptureFraming
	{
		FullScreen = 0,
		FollowLink720p = 1,
		Wide720p = 2,
		Square = 3
	}
}
