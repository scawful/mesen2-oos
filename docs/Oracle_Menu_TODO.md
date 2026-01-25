# Oracle Menu TODOs

## Diagnostics (ZSCustomOverworld v3 + Day/Night)
- ✅ Added gateway actions + Control Center buttons:
  - `check_day_night` → `mesen2_client.py time --json`
  - `check_zsow_status` → `mesen2_client.py diagnostics --json`
- ✅ Added Oracle menu diagnostics group (output windows + Control Center tab shortcut).
- TODO deeper checks:
  - Validate ZSCustomOverworld v3 tables/entry points and map collision data.
  - Add automated overworld transition smoke run (`overworld_explorer.py transitions`).
  - Add palette/tint sanity (night vs day) with SubColor expectations.
  - Add "Deep Diagnostics" output (items/flags/sprites/watch) to the UI workflow.

## Oracle Control Center improvements
- Show latest save state metadata (area, room, module, tags) in Save States tab.
- ✅ Added Save State slot count + Separate-by-patch toggles (and active SaveState folder).
- Add inline save-state label editor (slot grid + quick rename/clear).
- Add a small log pane to display action outputs inline (gateway stdout/stderr).
- Surface gateway action list dynamically (load `/actions` or `list-actions`).
- Add a "Preferences diagnostics" view to explain which config folder is active (helps SketchyBar launch issues).

## Agent debugging improvements
- Add session logging (agent vs user actions) and persist to `.context` scratchpad.
- ✅ Add "require running" guardrails for input injection (opt-out flag).
- Add state-library coverage checklist (Dark World crash repro states).
- Add CLI command to export a full "debug report" bundle (state + screenshot + time + warnings).
- Add macOS shortcut diagnostics (Ctrl vs Cmd) in Control Center to avoid silent failures.

## Model routing + orchestration
- Extend `~/src/lab/afs/tools/orchestrator.py` to support:
  - `openai` provider (gpt-5.2)
  - `anthropic` provider (opus-4.5 / sonnet-4.5)
- Add a lightweight selector in Control Center for routing prompts to local/remote models.
