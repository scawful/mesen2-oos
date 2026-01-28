using Mesen.ViewModels;
using ReactiveUI.Fody.Helpers;

namespace Mesen.Config
{
    public class OracleConfig : BaseConfig<OracleConfig>
    {
        [Reactive] public bool AutoStartGateway { get; set; } = true;
        [Reactive] public bool AutoStartYaze { get; set; } = true;
        [Reactive] public string DevRomPath { get; set; } = "";

        public OracleConfig()
        {
        }
        
        public override void ApplyConfig()
        {
            // Nothing to apply to core specifically for these UI-only settings right now
        }
    }
}
