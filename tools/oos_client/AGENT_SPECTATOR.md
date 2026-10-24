# Passive bot spectator

`agent_spectator.py` serves a localhost viewer for explicitly named Mesen sessions. It is independent of the bot driver: hiding video, changing the selected bot, or closing the page does not pause, resume, reset, detach the debugger, send input, load a state, save a state, or stop an emulator.

## Run

```sh
python3 tools/oos_client/agent_spectator.py \
  --config /absolute/path/sessions.json \
  --out /absolute/path/spectator-logs --port 8769
```

Open `http://127.0.0.1:8769/`. The server listens only on loopback. Keep the recorder running when closing the viewer; Ctrl-C stops only the recorder. A currently used port is an error; never terminate an unrelated listener to reclaim it.

Config is reloaded when its file changes. Preserve other agents' entries and replace the file atomically:

```json
{
  "sessions": [
    {
      "id": "claude-mines",
      "label": "Claude · Goron Mines",
      "socket": "/tmp/mesen2-claude-equip-0929.sock",
      "expected_rom_sha1": "SHA1 OF THE EXACT CANDIDATE",
      "fixtures": ["Mines-open save", "Invincibility; not combat balance proof"],
      "evidence_glob": "/absolute/packet/evidence/d6/*/result.json"
    },
    {
      "id": "codex-intro",
      "label": "Codex · Opening route",
      "socket": "/tmp/mesen2-owned-route.sock",
      "expected_rom_sha1": "SHA1 OF THIS OTHER CANDIDATE",
      "fixtures": [],
      "evidence_file": "/absolute/packet/evidence/continued_run/results.json"
    }
  ]
}
```

The socket must be explicit and absolute. No newest-socket lookup, fallback session, bot control endpoint, or user play-window registration exists. A disconnected bot waits for that same socket. The expected ROM hash is checked on connection and periodically; mismatch stops picture/game-state observation until the configured candidate returns. Sampling is sequential, not an atomic ROM/frame identity transaction.

## Passive game-state availability

STATE, ROMINFO and SCREENSHOT are the basic observer commands. Rich GAMESTATE polling is disabled unless `passive_binary` supplies a tested executable path and SHA256:

```json
"passive_binary": {
  "path": "/Applications/Mesen2 OOS.app/Contents/MacOS/Mesen",
  "sha256": "SHA256 OF A BINARY VERIFIED ON AN OWNED CANARY"
}
```

Before adding this attestation, verify on an owned disposable emulator that STATE → GAMESTATE → SCREENSHOT → STATE neither attaches the debugger nor changes pause state, and that an unpaused emulator continues advancing. The observer matches the target's status file, PID, executable path and file hash. Unknown or replaced binaries retain basic video/status observation. Do not use someone else's active run to discover whether an old GAMESTATE implementation attaches the debugger.

No READBLOCK, BATCH, register writes, debug attach/detach, SUBSCRIBE, capture savestates or control calls are used. Existing `oos_telemetry.py` remains the fuller controller-side telemetry recorder; its memory reads/capture behavior is a separate capability and is not silently invoked by this viewer.

## Logging and driver reports

Each configured bot writes `events.jsonl`, three rotated backups (8 MiB each), and one overwritten `latest.png`. Records include host time, sequence, observer run ID and bot ID. Samples contain emulator frame/pause state and available game state. Events include connection/reconnection, room/area/mode, health/supplies, death-module observation, and emulator-reported last save/load changes. An initial snapshot or save-state load is not counted as an item pickup or damage event. These logs are sampled observations, not deterministic replay or proof of the cause of damage.

Driver reports are read from disk only. `evidence_file` supports the existing continuation JSON with `commands[].command`, `commands[].after`, start/end and outcome. `evidence_glob` follows the newest matching JSON, supporting Claude's existing D6 `log`/`notes` and result structure. Partial writes are retried. The viewer labels them **latest** published actions; a completed step's report is not a live intention feed. Preserve the full driver evidence separately: the spectator shows a compact summary and does not replace test assertions or ROM/checkpoint lineage.

STATE/game samples continue at 1 Hz while hidden. Images are captured only while a viewer requests the selected feed (2.5-second lease), with 4 FPS default maximum. Background tabs stop requesting frames. Recording can miss brief transitions; successful save status does not prove cold-boot persistence, and an item byte alone does not prove a legitimate grant.

## Agent handoff contract

1. Keep the bot's existing headless instance and explicit socket. Do not relaunch it as a visible window just to spectate.
2. Register only your session, exact ROM hash, fixtures, and report path. Update your socket if the next process uses a new PID-specific name; preserve all other registrations.
3. Keep publishing action/outcome data to your own evidence file. No live driver patch is required for current Oracle continuation and D6 scripts.
4. Continue owning all input, pause/resume, assertions, checkpoint creation and emulator cleanup. The observer owns none of those responsibilities.
5. Finish by saving your evidence and stopping your emulator as usual. The viewer will show the disconnect; closing the viewer is never a signal to stop the bot.

## Verification

```sh
python3 -m unittest discover -s tools/oos_client/tests -p test_agent_spectator.py -v
```

Six checks cover the observer, including real Unix-socket transport and an HTTP server: video delivery, hidden-view capture cessation with logging continuing, health/location events, reconnect, wrong-ROM refusal, unknown-binary fallback, blocked controls/cross-host requests, partial driver-file recovery, invalid config preservation, and suppression of health/item deltas across title and save-load transitions. Browser hide/show and native behavior need separate evidence against the actual executable.
