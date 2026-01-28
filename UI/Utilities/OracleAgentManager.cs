using System;
using System.Threading.Tasks;
using Mesen.Config;
using Mesen.Interop;

namespace Mesen.Utilities
{
    public static class OracleAgentManager
    {
        private static bool _autoStarted = false;

        public static void CheckAutoStart()
        {
            if (_autoStarted) return;
            _autoStarted = true;

            if (AgentLauncher.ResolveProjectRoot() == null) {
                return;
            }

            OracleConfig cfg = ConfigManager.Config.Oracle;
            if (cfg.AutoStartGateway)
            {
                EmuApi.WriteLogEntry("[Oracle] Auto-starting Agent Gateway...");
                AgentLauncher.StartGateway();
            }

            if (cfg.AutoStartYaze)
            {
                // Give the gateway a moment to start if needed
                Task.Delay(1500).ContinueWith(_ => {
                    EmuApi.WriteLogEntry("[Oracle] Auto-starting Yaze Service...");
                    AgentLauncher.RunGatewayAction("yaze_start");
                });
            }
        }
    }
}
