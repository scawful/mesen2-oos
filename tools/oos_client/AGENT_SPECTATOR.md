# Probe spectator (watch, replay, notes)

`agent_spectator.py` records explicitly registered agent-owned Mesen sessions and serves the **Probes** page.
scawful opens it from the planning board: http://127.0.0.1:55260/probes (the board proxies `/probes` to the
recorder on 127.0.0.1:8769). It is an observer: it never pauses, resumes, resets, sends input, loads or saves
a state, attaches the debugger or stops an emulator.

| What | Where |
|---|---|
| Page | http://127.0.0.1:55260/probes (direct: http://127.0.0.1:8769/probes) |
| Home | `~/.context/projects/oracle-of-secrets/scratchpad/probe_runs/` |
| Registrations | `probe_runs/sessions.json` (use `register`, never hand-edit) |
| Runs | `probe_runs/<session>/runs/<YYYYmmdd-HHMMSS>/` — `run.json`, `frames/*.png`, `frames.jsonl`, `commands.jsonl`, `events.jsonl` |
| Notes | `probe_runs/comments.jsonl` (scawful's notes on a run moment; agents read open ones) |

## For agents running a probe

1. After your owned headless Mesen is up, register it (replaces your previous entry; others are kept):
   ```sh
   python3 ~/src/hobby/mesen2-oos/tools/oos_client/agent_spectator.py register \
     --id claude-d6-probe --socket "$MESEN2_SOCKET_PATH" --rom-sha1 "$ROM_SHA1" \
     --label "Claude · D6 minecart probe" --fixture "Mines-open save" \
     --evidence-glob '/abs/packet/evidence/d6/*/result.json'
   ```
   `--evidence-glob`/`--evidence-file` point at the JSON your driver already writes; the page shows its
   latest step. Up to 8 sessions; registrations whose socket is gone are dropped when the list is full.
2. Before writing or changing a probe, read scawful's open notes and act on the ones for your area:
   ```sh
   python3 ~/src/hobby/mesen2-oos/tools/oos_client/agent_spectator.py comments --open
   ```
   Each note carries the run, time, frame and the last six socket commands before that moment.
3. When your probe change addresses a note, resolve it with what changed:
   `agent_spectator.py resolve NOTE_ID --note 'Probe now waits for module 07 before WRITE' --by YOUR_LANE`
4. Keep owning input, pause/resume, assertions, checkpoints and cleanup. Stop your emulator as usual and
   `unregister --id ...` when the lane ends. Closing the page is never a signal to stop a bot.
5. Never register scawful's `oos-rc-play` session (refused by the tool).

## What gets recorded

- **Frames**: SCREENSHOT at `--record-fps` (default 2) while connected, viewer or not. Identical frames
  (paused picture) are skipped. About 15 KB per stored frame.
- **Commands**: the agent's own socket commands via `COMMAND_HISTORY since=<seq>` (seq, frame, wall time,
  params cut to 96 chars, success). Needs a Mesen build with the extended `COMMAND_HISTORY`; older builds
  record frames and events and the page says the command log is unsupported. Observer reads are tagged
  `observer=spectator` and dropped. If more than 1000 commands happen between polls, a `command_gap` event
  records how many were missed.
- **Events**: room/area/mode, health, death module, save/load, connect/disconnect, driver report changes
  (1 Hz, sampled, non-atomic). GAMESTATE is read only on an attested binary (`passive_binary` in
  `sessions.json`, see below); otherwise STATE/ROMINFO/SCREENSHOT only.
- **Runs**: one run per connected stretch; unreachable for 30 s ends it. The newest `--keep-runs`
  (default 40) runs per session are kept; older run folders are deleted by the recorder.

Recordings are sampled observations for review, not deterministic replay or proof of a cause. Brief
transitions between frames can be missed.

## Run the recorder

It runs as launchd service `com.scawful.oos-probe-recorder` (KeepAlive, approved by scawful 2026-11-23):

| What | Where |
|---|---|
| Plist | `~/Library/LaunchAgents/com.scawful.oos-probe-recorder.plist` |
| Launcher | `probe_runs/run_recorder.sh` (uses the main checkout once it has this version, else the branch worktree) |
| Log | `~/Library/Logs/oos-probe-recorder.log` |
| Restart | `launchctl kickstart -k gui/$(id -u)/com.scawful.oos-probe-recorder` |
| Remove | `launchctl bootout gui/$(id -u)/com.scawful.oos-probe-recorder` then delete the plist |

Restart after editing `agent_spectator.py` (the HTML is read per request). Manual run, when the service is off:

```sh
python3 ~/src/hobby/mesen2-oos/tools/oos_client/agent_spectator.py serve      # port 8769, home above
```

Options: `--port`, `--fps` (live view, default 4), `--record-fps` (0 disables recording), `--keep-runs`,
`--home`. The legacy form `--config FILE --out DIR` still works. A used port is an error; never terminate an
unrelated listener to reclaim it.

## Passive game-state attestation

Rich GAMESTATE polling is off unless `sessions.json` has a tested binary:

```json
"passive_binary": {"path": "/Applications/Mesen2 OOS.app/Contents/MacOS/Mesen", "sha256": "SHA256 OF A BINARY VERIFIED ON AN OWNED CANARY"}
```

Verify on an owned disposable emulator that STATE → GAMESTATE → SCREENSHOT → STATE neither attaches the
debugger nor changes pause state. Do not use someone else's active run for that check.

## HTTP API (127.0.0.1 only; same under `/probes`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | Registered sessions, live state, last 40 commands, current run |
| GET | `/api/frame?id=` | Latest PNG (viewer lease drives the live rate) |
| GET | `/api/runs[?id=]` | Run metadata, newest first |
| GET | `/api/run?id=&run=` | Frames index, commands, events, notes for one run |
| GET | `/api/run_frame?id=&run=&f=000123.png` | One recorded frame |
| GET | `/api/comments[?status=open]` | Notes |
| POST | `/api/comment` | `{session, run, t, frame, text}` (JSON, local Origin only) |
| POST | `/api/comment_status` | `{id, status: open|done, note}` |

No other POST exists; emulator control routes return 405.

## Verification

```sh
python3 -m unittest discover -s tools/oos_client/tests -p 'test_*.py' -v
```

Covers the original observer contract (video lease, reconnect, wrong-ROM refusal, unknown binary,
blocked controls) plus run recording, frame de-duplication, command-log filtering of observer reads,
legacy-binary fallback, run end after a gap, notes with command context, POST guards and registration.
