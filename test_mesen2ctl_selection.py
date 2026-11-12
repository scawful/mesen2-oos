"""Offline regression tests for the generic CLI's explicit socket selection."""

import argparse
import importlib.machinery
import importlib.util
from pathlib import Path
from unittest import mock

import pytest


loader = importlib.machinery.SourceFileLoader(
    "mesen2ctl", str(Path(__file__).parent / "tools" / "mesen2ctl")
)
spec = importlib.util.spec_from_loader(loader.name, loader)
ctl = importlib.util.module_from_spec(spec)
loader.exec_module(ctl)


@pytest.mark.parametrize("exists,is_socket", [(False, False), (True, False), (True, True)])
def test_explicit_environment_never_discovers_another_instance(monkeypatch, exists, is_socket):
    selected = "/tmp/mesen2-requested.sock"
    monkeypatch.setenv("MESEN2_SOCKET_PATH", selected)
    args = argparse.Namespace(socket=None, pid=None)
    with mock.patch.object(Path, "exists", return_value=exists), \
            mock.patch.object(Path, "is_socket", return_value=is_socket), \
            mock.patch.object(ctl, "find_mesen_sockets", return_value=["/tmp/mesen2-other.sock"]) as discover, \
            mock.patch.object(ctl, "send_command") as send:
        assert ctl.get_socket_path(args) == selected
        discover.assert_not_called()
        send.assert_not_called()


def test_socket_argument_overrides_environment(monkeypatch):
    monkeypatch.setenv("MESEN2_SOCKET_PATH", "/tmp/mesen2-env.sock")
    args = argparse.Namespace(socket="/tmp/mesen2-argument.sock", pid=123)
    assert ctl.get_socket_path(args) == args.socket


def test_pid_argument_overrides_environment(monkeypatch):
    monkeypatch.setenv("MESEN2_SOCKET_PATH", "/tmp/mesen2-env.sock")
    args = argparse.Namespace(socket=None, pid=123)
    with mock.patch.object(ctl.os.path, "exists", return_value=True):
        assert ctl.get_socket_path(args) == "/tmp/mesen2-123.sock"


def test_unset_environment_preserves_discovery(monkeypatch):
    monkeypatch.delenv("MESEN2_SOCKET_PATH", raising=False)
    args = argparse.Namespace(socket=None, pid=None)
    with mock.patch.object(ctl, "find_mesen_sockets", return_value=["/tmp/mesen2-only.sock"]):
        assert ctl.get_socket_path(args) == "/tmp/mesen2-only.sock"


def test_missing_environment_socket_reports_failure(monkeypatch, tmp_path, capsys):
    selected = str(tmp_path / "missing.sock")
    monkeypatch.setenv("MESEN2_SOCKET_PATH", selected)
    monkeypatch.setattr(ctl.sys, "argv", ["mesen2ctl", "pause"])
    with mock.patch.object(ctl, "find_mesen_sockets", return_value=["/tmp/mesen2-other.sock"]) as discover, \
            mock.patch.object(ctl, "send_command", side_effect=FileNotFoundError) as send:
        assert ctl.main() == 1
        assert f"Socket not found: {selected}" in capsys.readouterr().err
        discover.assert_not_called()
        send.assert_called_once_with(selected, {"type": "PAUSE"}, timeout=5.0)
