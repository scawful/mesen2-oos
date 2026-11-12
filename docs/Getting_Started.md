# Start with the generic socket tools

Mesen2-OOS can inspect and control a ROM without an Oracle of Secrets checkout.
Use `tools/mesen2ctl` for generic emulator commands. The separate
`tools/oos_client/mesen2_client.py` adds Oracle-specific RAM, inventory and catalog
operations; those operations assume the matching Oracle ROM and checkout.

## Build from source

This repository currently has no published GitHub release binaries. The local
`dist/` directory, when present, is not a public download or a release guarantee.

The UI targets .NET 8. Native builds need a C++ compiler and SDL2 development
files. The macOS helper also requires CMake, the Xcode command-line tools
(`xcrun`) and `codesign`:

```sh
git clone https://github.com/scawful/mesen2-oos.git
cd mesen2-oos
bash tools/build_mesen2_oos_cmake.sh
```

The helper selects `osx-arm64` or `osx-x64` from the host architecture. Its app
output is `bin/<rid>/Release/<rid>/publish/Mesen2 OOS.app`. It builds and signs an
app locally; it does not install unless `--install` is supplied. Local signing
is not notarization. Intel macOS, Linux and Windows distribution packages still
need separate platform validation before release claims.

`make` is the other existing native/UI build entry point. The macOS helper is
not a Linux or Windows packaging command. Check the selected build's compiler,
SDL2 and .NET setup before starting a build.

## Check an owned instance on macOS or Linux

The following commands are examples to run yourself. Set `MESEN_BIN` to the
actual executable from your build and `MESEN_ROM` to your test ROM. On macOS the
executable is inside the app at `Contents/MacOS/Mesen`.

```sh
export MESEN_BIN="/absolute/path/to/Mesen"
export MESEN_ROM="/absolute/path/to/test.sfc"
export MESEN2_HOME="$PWD/.mesen-smoke-$$"
export MESEN2_SOCKET_PATH="/tmp/mesen2-smoke-$$.sock"
mkdir -p "$MESEN2_HOME"
printf 'Socket for the second terminal: %s\n' "$MESEN2_SOCKET_PATH"
"$MESEN_BIN" "$MESEN_ROM" --headless --noaudio --noinput --multiInstance
```

Keep that terminal open. In a second terminal, enter the printed socket path
and run these commands from the repository root:

```sh
export MESEN2_SOCKET_PATH="/tmp/mesen2-smoke-<printed-id>.sock"
python3 tools/mesen2ctl ping
python3 tools/mesen2ctl rominfo
python3 tools/mesen2ctl state
python3 tools/mesen2ctl raw '{"type":"CAPABILITIES"}'
```

`ping` proves connectivity. `rominfo` identifies the loaded ROM. `state` reports
emulator state; it does not prove gameplay or a dialogue is ready for input.
The first terminal's Ctrl+C stops this owned headless process. Retain the
isolated profile if you need its battery saves, states or logs.

`MESEN2_SOCKET_PATH` is an explicit selection, like `--socket`. If that endpoint
is missing or unavailable, the command fails; it does not choose another running
instance. `--socket` takes precedence, followed by `--pid`, then the environment.
Omitting all three enables automatic discovery, which can select another session
when several instances are running. Use an explicit selection for automation.

The generic CLI uses Unix-domain sockets. These instructions do not establish
Windows transport support. Socket commands include memory writes, input and
save/load operations: use your own test instance and an isolated profile.

## Use Oracle-specific tools

If the Oracle checkout is not a sibling named `oracle-of-secrets`, set `OOS_ROOT`
to its absolute path. No author-specific directory is required:

```sh
export OOS_ROOT="/absolute/path/to/oracle-of-secrets"
python3 tools/oos_client/mesen2_client.py --socket "$MESEN2_SOCKET_PATH" health
```

The Oracle client has additional prerequisites for catalogs, symbols, save
libraries and build-specific memory addresses. See its
[README](../tools/oos_client/README.md) and the
[socket API reference](Socket_API_Reference.md).

## Verify the CLI selection fix offline

With Python 3.10 or newer and pytest installed in the selected Python environment:

```sh
python3 -m pytest -q test_mesen2ctl_selection.py test_socket_discovery.py
```

These tests do not launch an emulator. Frame stepping, gameplay input, native
save/reopen, app installation and OS packaging need their own focused checks.
