"""Unit tests for socket_discovery.discover_socket_path().

Uses mocks to assert canonical order: env wins; then status files; then glob by mtime.
Non-PID socket names (e.g. mesen2-isolation.sock) are allowed.
"""

import json
import os
from unittest import mock

import pytest

from socket_discovery import discover_socket_path


def test_discover_prefers_env():
    """MESEN2_SOCKET_PATH wins when set and path exists."""
    with mock.patch.dict(os.environ, {"MESEN2_SOCKET_PATH": "/tmp/mesen2-custom.sock"}):
        with mock.patch("os.path.exists", return_value=True):
            assert discover_socket_path() == "/tmp/mesen2-custom.sock"


def test_discover_falls_back_when_env_path_missing():
    """When env is set but path does not exist, fall through to status/glob."""
    with mock.patch.dict(os.environ, {"MESEN2_SOCKET_PATH": "/tmp/nonexistent.sock"}):
        with mock.patch("os.path.exists", side_effect=lambda p: p != "/tmp/nonexistent.sock"):
            with mock.patch("glob.glob") as m_glob:
                m_glob.return_value = ["/tmp/mesen2-isolation.sock"]
                with mock.patch("os.path.getmtime", return_value=1000.0):
                    assert discover_socket_path() == "/tmp/mesen2-isolation.sock"


def test_discover_glob_by_mtime_no_pid_assumption():
    """Glob fallback sorts by mtime; non-PID names (e.g. isolation) are valid."""
    with mock.patch.dict(os.environ, {}, clear=False):
        with mock.patch("os.environ.get", return_value=None):
            pass
    with mock.patch("glob.glob", side_effect=[
        [],  # no status files
        ["/tmp/mesen2-isolation.sock", "/tmp/mesen2-99999.sock"],
    ]):
        with mock.patch("os.path.getmtime", side_effect=lambda p: 2000.0 if "isolation" in p else 1000.0):
            # Newest first: isolation (2000) then 99999 (1000)
            assert discover_socket_path() == "/tmp/mesen2-isolation.sock"


def test_discover_returns_none_when_no_sockets():
    """Return None when no env, no status files, and no sockets."""
    with mock.patch("glob.glob", return_value=[]):
        assert discover_socket_path() is None


def test_discover_uses_status_file_socket_path():
    """When status files exist, use socketPath from JSON; sort by status file mtime."""
    with mock.patch.dict(os.environ, {}, clear=False):
        with mock.patch("os.path.exists", return_value=True):
            with mock.patch("glob.glob") as m_glob:
                def glob_side_effect(pattern):
                    if "status" in pattern:
                        return ["/tmp/mesen2-1.status", "/tmp/mesen2-2.status"]
                    return []
                m_glob.side_effect = glob_side_effect
                with mock.patch("builtins.open", mock.mock_open(read_data='{"socketPath": "/tmp/mesen2-from-status.sock"}')):
                    with mock.patch("os.path.getmtime", return_value=1000.0):
                        assert discover_socket_path() == "/tmp/mesen2-from-status.sock"


def test_discover_verify_skips_stale_and_returns_next_candidate():
    """When verify callback fails for first candidate, use next responsive one."""
    with mock.patch.dict(os.environ, {"MESEN2_SOCKET_PATH": "/tmp/mesen2-stale.sock"}):
        with mock.patch("os.path.exists", return_value=True):
            with mock.patch("glob.glob", side_effect=[
                [],  # no status files
                ["/tmp/mesen2-good.sock"],
            ]):
                with mock.patch("os.path.getmtime", return_value=1000.0):
                    chosen = discover_socket_path(
                        verify=lambda p: p == "/tmp/mesen2-good.sock"
                    )
                    assert chosen == "/tmp/mesen2-good.sock"
