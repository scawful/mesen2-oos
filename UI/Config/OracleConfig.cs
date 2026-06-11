using Mesen.ViewModels;
using ReactiveUI.Fody.Helpers;

namespace Mesen.Config
{
    public enum OracleDebugPanelVisibility
    {
        Hidden = 0,
        Collapsed = 1,
        Expanded = 2
    }

    public class OracleConfig : BaseConfig<OracleConfig>
    {
        [Reactive] public bool AutoStartGateway { get; set; } = true;
        [Reactive] public bool AutoStartYaze { get; set; } = true;
        [Reactive] public string DevRomPath { get; set; } = "";
        [Reactive] public OracleDebugPanelVisibility DebugPanelVisibility { get; set; } = OracleDebugPanelVisibility.Collapsed;
        [Reactive] public string DebugPanelMacroProfilePath { get; set; } = "";

        public OracleConfig()
        {
        }
        
        public void ApplyConfig()
        {
            // Nothing to apply to core specifically for these UI-only settings right now
        }
    }
}
