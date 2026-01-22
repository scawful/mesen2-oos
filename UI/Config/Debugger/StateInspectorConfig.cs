using ReactiveUI.Fody.Helpers;
using System.Collections.Generic;

namespace Mesen.Config
{
	public class StateInspectorConfig : BaseWindowConfig<StateInspectorConfig>
	{
		[Reactive] public RefreshTimingConfig RefreshTiming { get; set; } = new();
		[Reactive] public List<int> ColumnWidths { get; set; } = new();
	}
}
