"""Compatibility entry point for the maintained Oracle cheat client.

The implementation lives in tools/oos_client; keep warp and save fixes there.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "oos_client"))
from mesen2_client_lib import oracle_cheats as _implementation
from mesen2_client_lib.oracle_cheats import (  # noqa: E402, F401
    CheatsError, GameState, OracleCheats, add_parsers, apply_write,
    find_area, find_entrance, hexint, load_json, oracle_root,
    resolve_checkpoint, run,
)


def __getattr__(name):
    return getattr(_implementation, name)
