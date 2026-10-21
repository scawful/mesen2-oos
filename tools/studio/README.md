# Mesen Studio

Screenshots, recordings with game audio, a virtual camera and a live browser
view for any Mesen2-OOS instance. Everything runs over the instance socket, so
it works for headless agent instances and never needs a window, screen
recording or computer use.

Requirements: Python 3.10+, `ffmpeg` on `PATH` (for `export` and `live` only).
LIVE is macOS/Linux only.

## Pick an instance

Every command needs the instance's socket: `--instance NAME`
(`/tmp/mesen2-NAME.sock`), `--socket PATH`, or `MESEN2_SOCKET_PATH`.

```sh
S=tools/studio/mesen_studio.py
$S --instance agent-a shot /tmp/now.png                 # full frame PNG
$S --instance agent-a shot /tmp/hero.png --preset closeup
```

## Record

```sh
$S --instance agent-a record start ~/Movies/run.avi --preset trailer   # video + audio
$S --instance agent-a record start ~/Movies/run.avi --wav              # also a separate WAV
$S --instance agent-a record stop
$S export ~/Movies/run.avi ~/Movies/run.mp4 --scale 4                  # H.264, nearest-neighbor
$S export ~/Movies/run.avi ~/Movies/run.mov --prores                   # ProRes 422 HQ for editing
```

Recordings are lossless AVI (ZMBV by default) with the game's audio track.
With a camera preset or `--camera`, the clip is framed by the virtual camera at
a fixed output size.

## Virtual camera

The camera crops, zooms, pans and follows over the rendered frame. It changes
only camera screenshots, camera recordings and the live view; the game, its RAM
and any open window are untouched.

```sh
$S --instance agent-a camera presets                 # presets and follow targets for this hack
$S --instance agent-a camera preset closeup          # follow the player at 2x, 1280x720
$S --instance agent-a camera pan --x 200 --y 120 --zoom 3 --seconds 2
$S --instance agent-a camera set mode=manual x=128 y=112 zoom=1.5
$S --instance agent-a camera reset
```

Built-in presets: `full`, `trailer` (1280x720 manual), `closeup` (follow
`player`, zoom 2), `square` (1024x896). The output size is locked while a
camera recording or live view is running; pans and zoom still work.

## Live view

```sh
$S --instance agent-a live --port 8770 --preset closeup
```

Open `http://127.0.0.1:8770/` for video and audio (click Unmute). Ctrl-C stops
the stream, not the emulator. The stream is localhost only and drops frames
rather than slowing the emulator when the viewer falls behind.

Cameras larger than 640x360 are box-downscaled in the emulator before the pipe
and upscaled again by the encoder (`--max-stream-pixels` changes the limit), so
the stream keeps up even at background priority. If a viewer died without
cleaning up, the next `live` replaces its stream; `--replace` also takes over a
stream another viewer is still reading.

## Trailer reels

A reel is a shot list: each shot loads a savestate, sets a camera preset,
records for a number of emulated seconds and runs timed pans.

```json
{
  "shots": [
    {"name": "village", "state": "states/village.mss", "preset": "trailer",
     "camera": {"x": 128, "y": 100}, "seconds": 6,
     "pans": [{"at": 1.0, "x": 180, "y": 120, "zoom": 2.0, "seconds": 3}]}
  ]
}
```

```sh
$S --instance agent-a reel shots.json --out ~/Movies/trailer --export-scale 4
```

Timing counts emulated frames, so clips are the same length at any speed. Reels
do not drive input; use savestates, or wrap an agent run with `record start/stop`.

## Projects and other hacks

Mesen2-OOS loads a `.yaze` project like a ROM: it runs `[build] build_target`
(or `[files] rom_filename` when that is missing). The socket `PROJECT` command
reports what was loaded, and the studio uses it to pick presets.

Per-hack settings live in an optional sidecar next to the project,
`<Project>.mesen.json` (for a bare ROM, `<rom>.mesen.json`). yaze does not read
or write it.

```json
{
  "schema": 1,
  "adapter": "generic",
  "capture_dir": "Captures",
  "camera": {
    "follow": {
      "hero": {"memtype": "wram", "follow_x": "0x7E0010", "follow_y": "0x7E0012",
               "scroll_x": "0x7E0014", "scroll_y": "0x7E0016", "size": 2}
    },
    "presets": {
      "hero": {"mode": "follow", "follow": "hero", "width": 1280, "height": 720, "zoom": 2}
    }
  }
}
```

Adapters supply default follow targets: `alttp` (player position minus BG
scroll; the default for `.yaze` projects and Zelda ROMs) and `generic` (none).
Any game can define its own follow targets as screen-space RAM addresses.

```sh
$S project ~/src/hobby/oracle-of-secrets/Oracle-of-Secrets.yaze
```

## Tests

```sh
python3 -m unittest discover -s tools/studio/tests
```

Socket details: `docs/Socket_API_Reference.md` (CAMERA, RECORD, LIVE, PROJECT, SCREENSHOT).
