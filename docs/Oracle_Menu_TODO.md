# Oracle Menu TODOs

## Diagnostics (ZSCustomOverworld v3 + Day/Night)
- Add oracle_agent_gateway actions:
  - `check_zs_custom_overworld_v3` (validate version signature + key tables)
  - `check_day_night` (read current cycle, transitions, and flags)
- Implement scripts in `oracle-of-secrets/scripts/` to:
  - Read day/night state from RAM and emit a short stability report.
  - Verify ZSCustomOverworld v3 tables/entry points and map collision data.
- Update **Oracle Control Center > Diagnostics** to enable these buttons and surface results.

## Oracle Control Center improvements
- Show latest save state metadata (area, room, module, tags) in Save States tab.
- Add a small log pane to display action outputs inline (gateway stdout/stderr).
- Surface gateway action list dynamically (load `/actions` or `list-actions`).

## Model routing + orchestration
- Extend `~/src/lab/afs/tools/orchestrator.py` to support:
  - `openai` provider (gpt-5.2)
  - `anthropic` provider (opus-4.5 / sonnet-4.5)
- Add a lightweight selector in Control Center for routing prompts to local/remote models.
