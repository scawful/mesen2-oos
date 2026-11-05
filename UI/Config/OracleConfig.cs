using Mesen.Interop;
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

        // Opt-in: Oracle ROMs (ALTTP header title, expanded past 1 MiB) all use Saves/<name>.srm,
        // whatever the ROM file is called. Empty keeps the default per-filename save.
        [Reactive] public string PersistentSaveName { get; set; } = "";

        // Marks this instance as the player's window: the title shows "Oracle <build> [play]"
        // instead of the agent markers (ACTIVE, [instance], [src:...]).
        [Reactive] public bool PlayerWindow { get; set; } = false;

        public OracleConfig()
        {
        }

        public void ApplyConfig()
        {
            EmuApi.SetOracleSaveName(PersistentSaveName.Trim());
        }
    }
}
