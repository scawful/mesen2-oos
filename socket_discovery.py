"""Canonical Mesen2 socket discovery for tests and scripts.

Discovery order:
1. MESEN2_SOCKET_PATH (then MESEN2_SOCKET) if set and the path exists, or if it is a tcp:// endpoint
2. MESEN2_TCP_PORT (+ optional MESEN2_TCP_HOST, default 127.0.0.1)
3. Status files: glob mesen2-*.status, read socketPath, sort by status file mtime
4. Fallback: glob mesen2-*.sock, sort by socket file mtime (most recent first)

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
        if path and (path.startswith("tcp://") or path.startswith("tcp:") or os.path.exists(path)):
            candidates.append(path)

    tcp_port = os.environ.get("MESEN2_TCP_PORT")
    if tcp_port:
        host = os.environ.get("MESEN2_TCP_HOST") or "127.0.0.1"
        candidates.append(f"tcp://{host}:{tcp_port}")

    # 2. Status files: read socketPath, sort by status file mtime (newest first)
    status_files = glob.glob("/tmp/mesen2-*.status")
    if status_files:
        status_candidates = []
        for sf in status_files:
            try:
                with open(sf) as f:
                    data = json.load(f)
                sp = data.get("socketPath")
                if sp and isinstance(sp, str):
                    is_tcp = sp.startswith("tcp://") or sp.startswith("tcp:")
                    if is_tcp or os.path.exists(sp):
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
