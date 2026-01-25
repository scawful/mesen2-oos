import socket
import json
import time
import os
import sys

SOCKET_PATH_PATTERN = "/tmp/mesen2-*.sock"

def find_socket_path():
    import glob
    paths = glob.glob(SOCKET_PATH_PATTERN)
    if not paths:
        return None
    # return the most recently accessed one
    return max(paths, key=os.path.getatime)

def test_events():
    socket_path = find_socket_path()
    if not socket_path:
        print("Mesen2 socket not found. Is it running?")
        sys.exit(1)
        
    print(f"Connecting to {socket_path}...")
    
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
        s.connect(socket_path)
        
        # Helper to send command
        def send_cmd(cmd):
            print(f"> {cmd}")
            s.sendall((json.dumps(cmd) + "\n").encode('utf-8'))
            
        # Helper to read response
        def read_response():
            data = b""
            while b"\n" not in data:
                chunk = s.recv(4096)
                if not chunk: break
                data += chunk
            line = data.decode('utf-8').strip()
            print(f"< {line}")
            return json.loads(line)

        # 1. Subscribe
        send_cmd({"type": "SUBSCRIBE", "events": "breakpoint_hit,frame_complete"})
        resp = read_response() # Subscription response
        
        if not resp.get("success"):
            print("FAILED to subscribe")
            return

        print("\nListening for events (Press Ctrl+C to stop)...")
        print("1. Please unpause the emulator to get 'frame_complete'")
        print("2. Please trigger a breakpoint to get 'breakpoint_hit'")
        
        frame_event_received = False
        break_event_received = False
        
        start_time = time.time()
        while not (frame_event_received and break_event_received):
            if time.time() - start_time > 30:
                print("Timeout waiting for events")
                break
                
            resp = read_response()
            if resp.get("type") == "EVENT":
                evt_type = resp.get("event")
                print(f"EVENT RECEIVED: {evt_type}")
                
                if evt_type == "frame_complete":
                    frame_event_received = True
                elif evt_type == "breakpoint_hit":
                    break_event_received = True
                    
        if frame_event_received and break_event_received:
            print("\nSUCCESS: Both events received!")
        else:
            print(f"\nPARTIAL: Frame: {frame_event_received}, Break: {break_event_received}")

def test_status_file():
    socket_path = find_socket_path()
    if not socket_path:
        return
        
    status_path = socket_path.replace(".sock", ".status")
    print(f"\nChecking status file: {status_path}")
    
    if os.path.exists(status_path):
        with open(status_path, 'r') as f:
            try:
                data = json.load(f)
                print(json.dumps(data, indent=2))
                
                if "romHash" in data and "paused" in data:
                    print("SUCCESS: romHash and paused fields found")
                else:
                    print("FAIL: Missing expected fields")
            except Exception as e:
                print(f"Error reading status file: {e}")
    else:
        print("Status file not found")

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "status":
        test_status_file()
    else:
        test_events()
