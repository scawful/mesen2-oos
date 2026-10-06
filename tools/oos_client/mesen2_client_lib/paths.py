"""Filesystem paths for the Oracle Mesen2 client."""

import os
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]  # mesen2-oos/tools/oos_client
# Oracle of Secrets checkout. Default: sibling of the mesen2-oos checkout.
REPO_ROOT = Path(
    os.environ.get("OOS_ROOT")
    or os.environ.get("ORACLE_OF_SECRETS_ROOT")
    or Path(__file__).resolve().parents[4] / "oracle-of-secrets"
).expanduser().resolve()
def _oracle_catalog(name: str) -> Path:
    """Oracle catalogs live in Data/debug (2026-10-06); older checkouts kept them in Docs/Debugging/Testing."""
    current = REPO_ROOT / "Data" / "debug" / name
    legacy = REPO_ROOT / "Docs" / "Debugging" / "Testing" / name
    return current if current.exists() or not legacy.exists() else legacy

MANIFEST_PATH = _oracle_catalog("save_state_library.json")
LIBRARY_ROOT = REPO_ROOT / "Roms" / "SaveStates" / "library"

# Save-data (SRAM/WRAM savefile mirror) library.
SAVE_DATA_MANIFEST_PATH = _oracle_catalog("save_data_library.json")
SAVE_DATA_LIBRARY_ROOT = REPO_ROOT / "Roms" / "SaveData" / "library"

# Human-editable save-data "profiles" (item/flag loadouts) live in-repo.
SAVE_DATA_PROFILE_DIR = REPO_ROOT / "Docs" / "Debugging" / "Testing" / "save_data_profiles"
