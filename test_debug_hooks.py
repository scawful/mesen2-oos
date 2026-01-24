#!/usr/bin/env python3
"""Test script for Mesen2 debugger hooks (P register and memory write tracking)."""

import socket
import json
import sys
import time
import glob

def find_socket():
    """Find the active Mesen2 socket."""
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if not sockets:
        print("Error: No Mesen2 socket found. Is Mesen running?")
        sys.exit(1)
    return sorted(sockets, key=lambda x: -int(x.split('-')[1].split('.')[0]))[0]

def send_command(sock_path, cmd):
    """Send a command and receive response."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(sock_path)
    s.sendall((json.dumps(cmd) + "\n").encode())
    s.settimeout(2.0)
    response = b""
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response += chunk
            if b"\n" in response:
                break
    except socket.timeout:
        pass
    s.close()
    return json.loads(response.decode().strip())

def main():
    sock = find_socket()
    print(f"Using socket: {sock}\n")

    # Check state
    state = send_command(sock, {"type": "STATE"})
    if not state.get("success") or "No ROM" in state.get("error", ""):
        print("Error: No ROM loaded. Please load a ROM first.")
        sys.exit(1)

    print(f"Emulation: frame={state['data']['frame']}, fps={state['data']['fps']:.1f}")
    print()

    # Enable P_WATCH
    print("=== Enabling P register tracking ===")
    result = send_command(sock, {"type": "P_WATCH", "action": "start", "depth": "500"})
    print(f"  {result['data']}")

    # Add memory watches
    print("\n=== Adding memory watches ===")
    watches = [
        ("0x7E0022", 2, "Link X Position"),
        ("0x7E0020", 2, "Link Y Position"),
        ("0x7E0116", 2, "VRAM Upload Index"),
    ]
    for addr, size, desc in watches:
        result = send_command(sock, {
            "type": "MEM_WATCH_WRITES",
            "action": "add",
            "addr": addr,
            "size": str(size),
            "depth": "50"
        })
        if result.get("success"):
            print(f"  Watch {result['data']['watch_id']}: {desc} ({addr})")
        else:
            print(f"  Failed to add watch for {desc}: {result.get('error')}")

    # Wait for execution
    print("\n=== Waiting 3 seconds for execution... ===")
    time.sleep(3)

    # Get P register log
    print("\n=== P Register Changes ===")
    result = send_command(sock, {"type": "P_LOG", "count": "20"})
    if result.get("success"):
        total = result["data"].get("total", 0)
        entries = result["data"].get("entries", [])
        print(f"Total changes captured: {total}")
        if entries:
            print("Recent changes:")
            for e in entries[:10]:
                print(f"  PC=${e['pc']:>8} | P: 0x{e['old_p']:>2} -> 0x{e['new_p']:>2} | {e['flags_changed']:>4} | opcode=0x{e['opcode']}")

    # Get memory blame for each watch
    print("\n=== Memory Write Attribution ===")
    result = send_command(sock, {"type": "MEM_WATCH_WRITES", "action": "list"})
    if result.get("success"):
        for watch in result["data"]["watches"]:
            addr = watch["addr"]
            blame = send_command(sock, {"type": "MEM_BLAME", "addr": addr})
            if blame.get("success"):
                writes = blame["data"].get("writes", [])
                print(f"\n{addr} ({watch['log_count']} writes logged):")
                if writes:
                    for w in writes[:5]:
                        print(f"  PC=${w['pc']} wrote 0x{w['value']:04X} at cycle {w['cycle']}")
                else:
                    print("  No writes captured yet")

    print("\n=== Done ===")

if __name__ == "__main__":
    main()
