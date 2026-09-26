# Offer ROM: hand a new build to a running session

Branch `claude/offer-rom`. Lets an agent offer a new ROM build while scawful is
playing, and load it without losing his in-game save.

## Commands

Socket (JSON over `/tmp/mesen2-<instance>.sock`):

| Command | Params | Effect |
|---|---|---|
| `OFFER_ROM` | `path`, `label` (optional, default file name), `notes` (optional) | Stores a pending offer (replaces any older one) and shows the GUI banner. |
| `OFFER_ROM_STATUS` | none | Pending offer plus the result of the last live load. |
| `OFFER_ROM_CLEAR` | none | Withdraws the pending offer. |
| `LOAD_ROM_LIVE` | `path` (optional), `label` (optional), `state` (optional, default `true`) | Loads a ROM keeping the running game's save. No `path` = accept the pending offer (same as the banner's Load now). |

Client (`tools/oos_client/mesen2_client.py`, reached from Oracle through
`Scripts/Mesen2/mesen2_client.py`):

```bash
C=~/src/hobby/oracle-of-secrets/Scripts/Mesen2/mesen2_client.py
python3 $C --socket /tmp/mesen2-oos-rc-play.sock offer-rom Roms/TestBuilds/v10/oos168x.sfc --label v10 --notes "D6 door fix"
python3 $C --socket /tmp/mesen2-oos-rc-play.sock offer-status
python3 $C --socket /tmp/mesen2-oos-rc-play.sock offer-clear
python3 $C --socket /tmp/mesen2-oos-rc-play.sock load-rom-live            # accept the pending offer
python3 $C --socket /tmp/mesen2-oos-rc-play.sock load-rom-live <rom.sfc>  # "just load it"
python3 $C --socket ... load-rom-live <rom.sfc> --no-state                # skip the backup state
```

All four print the offer JSON:

```json
{"pending": false, "id": 1, "path": "", "label": "", "notes": "", "offered_at_ms": 0,
 "snoozed": false, "loading": false,
 "last_load": {"count": 1, "ok": true, "path": ".../romB.sfc", "label": "v10", "error": "",
               "state": "<home>/SaveStates/romA_before_live_load.mss",
               "save": "<home>/Saves/romA.srm",
               "backup": "<home>/Saves/SrmBackups/romA_20260926-083012.srm", "at_ms": ...}}
```

The client changes live in `tools/oos_client`, which is untracked in the main
checkout. Apply them there with:

```bash
cd ~/src/hobby/mesen2-oos && patch -p1 < ~/src/hobby/mesen2-oos-offer-rom/docs/offer_rom_client.patch
```

## What the player sees (GUI)

- A banner docked above the game: `New ROM available: <label>`, the notes, and
  `[Load now]` `[Later]`. The path is in the tooltip. The window grows by the
  banner height so the game keeps its scale (same logic as the Oracle strip).
- Load now: the banner shows `Loading <label>...`, then disappears when the new
  ROM is running. If the load fails, the banner stays and shows `Load failed: ...`.
- Later: hides the banner. The offer stays pending until it is loaded,
  replaced by a new `OFFER_ROM`, or withdrawn with `OFFER_ROM_CLEAR`.
  While pending it is also under `File > Load Offered ROM: <label>`, and an
  agent can accept it for him with `load-rom-live` (no path).
- The offer lives in the running process only; quitting Mesen drops it.

## What a live load does

`LOAD_ROM_LIVE`, the banner's Load now and the File menu item all run
`SocketServer::LoadRomLive`:

1. If a game is running, save a backup state of it to
   `SaveStates/<save name>_before_live_load.mss` (skip with `state=false`).
2. Keep the running game's save name for the new ROM path
   (`Emulator::SetBatteryNameOverride`), so the new ROM reads and writes the
   same `Saves/<save name>.srm` even if its file name differs. This also holds
   for later power cycles / reloads of that path.
3. `Emulator::LoadRom`: Mesen writes the running game's SRAM to disk, then
   (new) copies that `.srm` to `Saves/SrmBackups/<save name>_<yyyyMMdd-HHmmss>.srm`,
   then loads the new ROM, which reads the flushed file.
4. GUI only: add the ROM to recent files, reapply the config
   (`ConfigManager.Config.ApplyConfig()`: input, video, audio) and restore the
   window position, size and state captured when the load started.

The ALTTP in-game save lives in SRAM only after the game saves (save menu,
death). Unsaved progress in WRAM is only in the backup state, and states from
an old build are not safe to load into a new build.

## Oracle persistent save (opt-in)

To keep one battery save across versioned Oracle ROM file names
(`oos168x-v10.sfc`, `oos168x-v11.sfc`, ...), set in the profile's
`settings.json` (edit while that Mesen instance is closed; it rewrites the file
on exit):

```json
"Oracle": { "PersistentSaveName": "oos168x" }
```

- Empty (default) = vanilla behavior: the save is named after the ROM file.
- When set, ROMs detected as Oracle use `Saves/<PersistentSaveName>.srm` for
  every load path (File > Open, recent files, socket `LOADROM`, power cycle,
  live load).
- Oracle detection: `.sfc`/`.smc`, not in an archive, SNES header title
  `THE LEGEND OF ZELDA` at `$7FC0`, and larger than 1 MiB (vanilla ALTTP is
  exactly 1 MiB; Oracle builds are expanded). Other expanded ALTTP hacks in the
  same profile would also match, so leave it off in profiles used for them.
- Before another ROM reads a shared save, the `.srm` is copied to
  `Saves/SrmBackups/<name>_<timestamp>.srm`: on every live load, and on any
  other load whose ROM path differs from the running one (a power cycle of the
  same file is skipped). Backups are never pruned (8 KB each).
- Save-state slots stay keyed by ROM file name, so each build keeps its own slots.

## Headless crash on rom-load (fixed)

Cause: `HeadlessRunner` looped on `while(EmuApi.IsRunning())`, and
`Emulator::IsRunning()` is `_console != nullptr`. Loading a different ROM calls
`Stop()` inside `InternalLoadRom`, which drops `_console` until the new console
is installed. When the 250 ms poll landed in that window, the runner left the
loop, called `EmuApi.Release()` and exited with status 0: no crash report,
socket removed. Launchd recorded exactly that for the two instances that died on
2026-09-26 (`termination reported by launchd (0, 0, 0)` at 07:04:10 and 07:06:38).

Fix (`UI/Utilities/HeadlessRunner.cs`): exit on `EmulationStopped` (sent by
Ctrl+C, `emu.stop()`, power off), which a ROM swap never sends; otherwise only
exit once `IsRunning()` has stayed false for 1 s (startup load failure, load
exception).

## Verification (2026-09-26)

Dev bundle `bin/osx-arm64/Release/osx-arm64/publish/Mesen2 OOS.app`, headless,
binary run directly (not through LaunchServices), one instance at a time, two
Oracle test builds (`impa-follower-hints` = romA, `menu-ocarina-blank-slot` =
romB) plus vanilla ALTTP (USA, 1 MiB).

- Repro before the fix (installed app): headless instance exited status 0 on
  the 6th alternating `rom-load`.
- After the fix: 60 alternating `rom-load`s, instance alive; `emu.stop(7)`
  still exits at once with code 7; `emu.stop()` exits 0.
- Offer flow: `offer-rom` -> pending; `offer-status` shows label; `load-rom-live`
  (no path) loads romB, clears the offer, writes
  `SaveStates/romA_before_live_load.mss` and `Saves/SrmBackups/romA_<ts>.srm`.
- Save carried over: 8 marker bytes written to `SnesSaveRam` at `$1FF0` under
  romA read back the same under romB after the live load and after a power
  cycle; `Saves/romB.srm` md5/mtime unchanged through live load, power cycle and
  unload.
- Same-path live load still backs up the `.srm`; a plain power cycle does not;
  `--no-state` skips the state; bad paths are rejected (client and core);
  `load-rom-live` with no offer pending is refused.
- `Oracle.PersistentSaveName = "oos-shared"`: plain `rom-load` romA -> romB
  kept the marker, only `Saves/oos-shared.srm` exists, backup written; vanilla
  ALTTP did not get the marker and wrote its own `Saves/vanilla.srm`; back on
  romA the marker was there again.
- Test scripts: scratchpad `offer-rom/test_headless.sh` (16/16 pass) and
  `test_edge.sh` (5/5 pass).

Not verified: the GUI banner, Later, the File menu item and the window/config
restore. No GUI instance was launched, to avoid stealing focus from the live
`oos-rc-play` session, and screen capture is unavailable to agents. The XAML
compiled with compiled bindings. First GUI check after install: run
`offer-rom` against your instance, confirm the banner, click Later, confirm
`File > Load Offered ROM: <label>`, load it, confirm the save continues.

## Install (needs scawful's approval)

Quit `oos-rc-play` first (a running instance keeps the old code). Backs up the
current app to `/Applications/Mesen2-Backups/<timestamp>/`:

```bash
~/src/hobby/mesen2-oos-offer-rom/tools/install_mesen2_oos.sh \
  --source "$HOME/src/hobby/mesen2-oos-offer-rom/bin/osx-arm64/Release/osx-arm64/publish/Mesen2 OOS.app" \
  --core "$HOME/src/hobby/mesen2-oos-offer-rom/build-cmake/lib/osx-arm64/libMesenCore.dylib"
cd ~/src/hobby/mesen2-oos && patch -p1 < ~/src/hobby/mesen2-oos-offer-rom/docs/offer_rom_client.patch
```

The bundle includes the uncommitted Oracle panel rework (first commit on this
branch), which is what `/Applications` runs today. It does not include the
uncommitted `Spc.cpp` / `ConfigManager.cs` / `HeadlessRunner.cs` edits in the
main checkout; neither does the installed app.
