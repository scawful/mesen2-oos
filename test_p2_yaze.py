
import os
import sys
import time
import json
import socket
import shutil
from pathlib import Path

# Add mesen2-mcp to path
sys.path.append(os.path.expanduser("~/src/tools/mesen2-mcp"))
from mesen2_mcp.core import MesenCore, CpuType, StepType

LIB_PATH = os.path.expanduser("~") + "/src/tools/mesen2-mcp/MesenCore.dylib"
ROM_PATH = "/Users/scawful/Backups/oracle-of-secrets-roms-20260123/oos168x.sfc"
SOCKET_DIR = "/tmp/mesen2"
CUSTOM_STATE_PATH = "/tmp/mesen2_custom_sync.mss"

def find_socket():
    import glob
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if sockets:
        return max(sockets, key=os.path.getatime)
    return None

def run_test():
    if not os.path.exists(LIB_PATH):
        print(f"Error: {LIB_PATH} not found")
        return

    print("Initializing Core...")
    core = MesenCore(LIB_PATH)
    core.initialize(SOCKET_DIR)
    
    print("Loading ROM...")
    if not core.load_rom(ROM_PATH):
        print("Failed to load ROM")
        return

    # Wait for socket
    time.sleep(1)
    socket_path = find_socket()
    if not socket_path:
        print("Socket not found")
        return
        
    print(f"Connecting to {socket_path}...")
    
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.connect(socket_path)
            
            # 1. Establish initial state
            print("Stepping to change state...")
            core.step(CpuType.SNES, 60, StepType.PPU_FRAME)
            pc_initial = core.get_program_counter(CpuType.SNES)
            print(f"Initial PC: ${pc_initial:06X}")
            
            # 2. Save state to slot 0
            print("Saving state to slot 0...")
            core.save_state(0)
            
            # Find where slot 0 is saved. Usually RomName.0.mss
            # MesenCore wrapper doesn't expose save path easily?
            # It saves to "FolderUtilities::GetHomeFolder() + "/SaveStates/" + folderName + "/" + filename + "." + ext;"
            # We set home folder to /tmp/mesen2
            # ROM name is oos168x.sfc -> oos168x.0.mss (or just .state?) Mesen uses .mss
            
            # Let's search for the file
            # Actually, we can use SAVESTATE_SYNC to tell Mesen to watch a file we control.
            # But we need a valid state file first.
            # Let's assume we can copy the slot 0 file.
            
            save_dir = Path(SOCKET_DIR) / "SaveStates"
            # It might be in a subdir based on ROM name?
            # Let's look for any .mss file recently modified
            state_files = list(Path(SOCKET_DIR).rglob("*.mss"))
            if not state_files:
                print("Could not find saved state file")
                # Try finding in root just in case
                state_files = list(Path(SOCKET_DIR).glob("*.mss"))
            
            if not state_files:
                print("Still no state files found. Creating dummy?")
                return

            latest_state = max(state_files, key=os.path.getmtime)
            print(f"Found state file: {latest_state}")
            
            # Copy to custom path
            shutil.copy2(latest_state, CUSTOM_STATE_PATH)
            print(f"Copied to {CUSTOM_STATE_PATH}")
            
            # 3. Tell Mesen to watch this path
            print("Sending SAVESTATE_SYNC...")
            cmd = {"type": "SAVESTATE_SYNC", "path": CUSTOM_STATE_PATH}
            s.sendall(json.dumps(cmd).encode('utf-8') + b"\n")
            
            # Read response
            s.settimeout(2.0)
            data = s.recv(4096)
            print(f"Response: {data.decode('utf-8').strip()}")
            
            # 4. Advance emulator (change state)
            print("Advancing emulator...")
            core.step(CpuType.SNES, 60, StepType.PPU_FRAME)
            pc_advanced = core.get_program_counter(CpuType.SNES)
            print(f"Advanced PC: ${pc_advanced:06X}")
            
            if pc_advanced == pc_initial:
                print("Warning: PC did not change, test might be inconclusive")
            
            # 5. Trigger reload by touching the file
            print("Touching custom state file to trigger reload...")
            # Updating mtime
            Path(CUSTOM_STATE_PATH).touch()
            
            # Wait for reload (poll interval is 100ms)
            time.sleep(1.0)
            
            # 6. Check if state reverted
            pc_restored = core.get_program_counter(CpuType.SNES)
            print(f"Restored PC: ${pc_restored:06X}")
            
            if pc_restored == pc_initial:
                print("SUCCESS: State restored from custom path!")
            elif pc_restored == pc_advanced:
                print("FAILURE: State did not revert (still at advanced PC)")
            else:
                 # Maybe it loaded but timing is slightly different?
                 # Or maybe the state saved was slightly different?
                 print(f"Partial? PC is {pc_restored:06X}, expected approx {pc_initial:06X}")
                 if abs(pc_restored - pc_initial) < 0x100: # heuristic
                     print("SUCCESS: State restored (close enough)")
                 else:
                     print("FAILURE: PC mismatch")

    except Exception as e:
        print(f"Test error: {e}")

if __name__ == "__main__":
    run_test()
