"""Canonical Mesen2 socket discovery for tests and scripts.

Discovery order:
1. MESEN2_SOCKET_PATH (then MESEN2_SOCKET) if set and path exists
2. Status files: glob mesen2-*.status, read socketPath, sort by status file mtime
3. Fallback: glob mesen2-*.sock, sort by socket file mtime (most recent first)

Do not assume socket names contain a PID (e.g. mesen2-isolation.sock is valid).
"""

import glob
import json
import os
from pathlib import Path
from typing import Callable, List


def _collect_socket_candidates() -> List[str]:
    """Collect socket candidates in discovery priority order."""
    candidates: List[str] = []

    # 1. Env
    for env_var in ("MESEN2_SOCKET_PATH", "MESEN2_SOCKET"):
        path = os.environ.get(env_var)
        if path and os.path.exists(path):
            candidates.append(path)

    # 2. Status files: read socketPath, sort by status file mtime (newest first)
    status_files = glob.glob("/tmp/mesen2-*.status")
    if status_files:
        status_candidates = []
        for sf in status_files:
            try:
                with open(sf) as f:
                    data = json.load(f)
                sp = data.get("socketPath")
                if sp and isinstance(sp, str) and os.path.exists(sp):
                    mtime = os.path.getmtime(sf)
                    status_candidates.append((mtime, sp))
            except (OSError, json.JSONDecodeError, KeyError):
                continue
        if status_candidates:
            status_candidates.sort(key=lambda x: -x[0])
            candidates.extend(path for _, path in status_candidates)

    # 3. Fallback: glob sockets by mtime (do not assume PID in name)
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if sockets:
        candidates.extend(sorted(sockets, key=lambda p: -os.path.getmtime(p)))

    # Deduplicate while preserving order
    deduped: List[str] = []
    seen = set()
    for path in candidates:
        if path not in seen:
            deduped.append(path)
            seen.add(path)
    return deduped


def discover_socket_path(verify: Callable[[str], bool] | None = None) -> str | None:
    """Return the first discovered socket path, optionally requiring verify(path)."""
    candidates = _collect_socket_candidates()
    if not candidates:
        return None

    if verify is None:
        return candidates[0]

    for path in candidates:
        try:
            if verify(path):
                return path
        except Exception:
            continue
    return None
