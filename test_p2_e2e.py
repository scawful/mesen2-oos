
import os
import sys
import time
import json
import socket
import threading
from pathlib import Path

# Add mesen2-mcp to path
sys.path.append(os.path.expanduser("~/src/tools/mesen2-mcp"))

from mesen2_mcp.core import MesenCore, CpuType, StepType

# Path to dylib (using the one we deployed)
LIB_PATH = os.path.expanduser("~") + "/src/tools/mesen2-mcp/MesenCore.dylib"
ROM_PATH = "/Users/scawful/Backups/oracle-of-secrets-roms-20260123/oos168x.sfc"
SOCKET_DIR = "/tmp/mesen2" # Default home for Mesen2 headless

def listen_for_events(stop_event, events_received):
    # Find socket
    import glob
    while not stop_event.is_set():
        sockets = glob.glob("/tmp/mesen2-*.sock")
        if sockets:
            socket_path = max(sockets, key=os.path.getatime)
            break
        time.sleep(0.1)
    
    if stop_event.is_set(): return

    print(f"Connecting to {socket_path}...")
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.connect(socket_path)
            
            # Subscribe
            s.sendall(json.dumps({"type": "SUBSCRIBE", "events": "breakpoint_hit,frame_complete"}).encode('utf-8') + b"\n")
            
            # Read loop
            while not stop_event.is_set():
                s.settimeout(0.5)
                try:
                    data = b""
                    while b"\n" not in data:
                        chunk = s.recv(4096)
                        if not chunk: break
                        data += chunk
                    
                    if not data: break
                    
                    for line in data.decode('utf-8').splitlines():
                        if not line: continue
                        try:
                            msg = json.loads(line)
                            if msg.get("type") == "EVENT":
                                evt = msg.get("event")
                                if evt == "frame_complete":
                                    events_received["frame"] = True
                                elif evt == "breakpoint_hit":
                                    events_received["break"] = True
                                    print("BREAKPOINT HIT received!")
                        except:
                            pass
                except socket.timeout:
                    continue
    except Exception as e:
        print(f"Listener error: {e}")

def run_test():
    if not os.path.exists(LIB_PATH):
        print(f"Error: {LIB_PATH} not found")
        return

    if not os.path.exists(ROM_PATH):
        print(f"Error: {ROM_PATH} not found")
        return

    print("Initializing Core...")
    core = MesenCore(LIB_PATH)
    core.initialize(SOCKET_DIR)
    
    print("Loading ROM...")
    if not core.load_rom(ROM_PATH):
        print("Failed to load ROM")
        return

    # Start event listener
    stop_event = threading.Event()
    events_received = {"frame": False, "break": False}
    listener = threading.Thread(target=listen_for_events, args=(stop_event, events_received))
    listener.start()

    try:
        print("Running execution...")
        # Run a few frames to trigger frame_complete
        core.step(CpuType.SNES, 10, StepType.PPU_FRAME)
        
        # Check frame event
        time.sleep(1)
        if not events_received["frame"]:
            print("FAILURE: Did not receive frame_complete event")
        else:
            print("SUCCESS: Received frame_complete event")

        # Set breakpoint at current PC + some instructions, or Reset Vector?
        # Let's set a breakpoint at reset vector and reset
        pc = core.get_program_counter(CpuType.SNES)
        print(f"Current PC: ${pc:06X}")
        
        # Reset to get known state
        # core.reset() # This might not work via core wrapper directly? Wrapper has reset()
        
        # We'll just step, set BP at current PC, resume?
        # Actually, let's set a BP at NMI vector or something frequent? 
        # Or just current PC + 3 instructions.
        
        # Easiest: Set BP at Reset Vector, then Reset.
        # How to get Reset Vector? 
        # We can just use the address we are at? 
        # Let's verify we can get status file first.
        
        status_files = [f for f in os.listdir("/tmp") if f.startswith("mesen2-") and f.endswith(".status")]
        if status_files:
            s_path = os.path.join("/tmp", status_files[0])
            with open(s_path, 'r') as f:
                s_data = json.load(f)
                print("Status file content:", json.dumps(s_data, indent=2))
                if "romHash" in s_data and "paused" in s_data:
                    print("SUCCESS: Status file has new fields")
                else:
                    print("FAILURE: Status file missing fields")
        else:
            print("FAILURE: No status file found")

        # Now Breakpoint test
        # Let's find where we are
        pc = core.get_program_counter(CpuType.SNES)
        
        # Import InteropBreakpoint from core (need to adjust import path in script?)
        from mesen2_mcp.core import InteropBreakpoint, BreakpointTypeFlags, MemoryType
        
        # We create a breakpoint at current PC+1 instruction (approx) or same PC
        # Actually, if we just set it at current PC and Resume, it should hit immediately?
        # But we need to step off it first if we are on it?
        
        print(f"Setting BP at ${pc:06X}")
        bp = InteropBreakpoint()
        bp.Id = 1
        bp.CpuType = CpuType.SNES.value
        bp.MemoryType = MemoryType.SNES_PRG_ROM.value
        bp.Type = int(BreakpointTypeFlags.EXECUTE)
        bp.StartAddress = pc
        bp.EndAddress = pc
        bp.Enabled = True
        bp.MarkEvent = False
        bp.IgnoreDummyOperations = True
        bp.Condition = b"\x00" * 1000
        
        # Core wrapper set_breakpoints expects a list of dicts or objects?
        # server.py implementation: core.set_breakpoints([bp])
        core.set_breakpoints([bp])
        
        print("Resuming...")
        core.resume()
        time.sleep(1)
        
        if events_received["break"]:
            print("SUCCESS: Received breakpoint_hit event")
        else:
             # Maybe we didn't hit it because we were already PAST it or something?
             # Let's try Resetting
             print("Resetting to hit BP...")
             # core.reset() 
             # We need to expose reset in MesenCore python wrapper?
             # Assuming it's not exposed, we can try to write to PC?
             # core.set_program_counter(pc, CpuType.SNES)
             pass
             
        if not events_received["break"]:
            print("FAILURE: Did not receive breakpoint_hit event")

    finally:
        stop_event.set()
        listener.join()
        # core destructor should cleanup?

if __name__ == "__main__":
    run_test()
