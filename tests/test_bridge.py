#!/usr/bin/env python3
"""
test_bridge.py — Mosey Bridge Protocol Tester

Sends commands to mosey_bridge and displays responses including
event frames (callbacks from mosey_server).

Usage:
    python test_bridge.py                       # Quick test: getVersion + start + stop
    python test_bridge.py listen                # Listen for events continuously
    python test_bridge.py start [filters...]    # Start with specific filters
"""

import socket
import struct
import sys
import time

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 19539

# Frame types
FRAME_REQUEST = 0x01
FRAME_REPLY = 0x02
FRAME_EVENT = 0x03

# Commands
CMD_GET_VERSION = 0
CMD_START = 1
CMD_STOP = 2
CMD_UPDATE = 3


def read_exact(sock, n):
    """Read exactly n bytes from socket."""
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Socket closed")
        buf += chunk
    return buf


def read_frame(sock):
    """Read one frame: [type:u8][payload_len:u32 LE][payload...]"""
    header = read_exact(sock, 5)
    typ = header[0]
    plen = struct.unpack("<I", header[1:5])[0]
    payload = read_exact(sock, plen) if plen > 0 else b""
    return typ, payload


def send_command(host, port, cmd, params=b"", timeout=10):
    """Send a command and return (success, result_or_error)."""
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((host, port))

        # Build and send frame
        payload = bytes([cmd]) + params
        frame = struct.pack("<BI", FRAME_REQUEST, len(payload)) + payload
        s.send(frame)

        # Read reply
        typ, payload = read_frame(s)

        if typ != FRAME_REPLY:
            return False, f"Unexpected frame type: 0x{typ:02x}"

        if len(payload) < 4:
            return False, f"Short reply: {len(payload)} bytes"

        status = struct.unpack("<i", payload[:4])[0]
        result = payload[4:] if len(payload) > 4 else b""

        return status == 0, result
    except socket.timeout:
        return False, "Timeout"
    except Exception as e:
        return False, str(e)
    finally:
        s.close()


def listen_for_events(host, port, duration=30, channels=b"\x03\x95\x00\x00\x00\x2c\x00\x00\x00\x06\x00\x00\x00"):
    """
    Connect to bridge, start AWDL discovery on the given channels.
    "channels" is a byte string: [count:u8][ch1:i32][ch2:i32]...
    Default channels: [149, 44, 6] (5GHz + 2.4GHz AWDL channels)
    """
    s = socket.socket()
    s.settimeout(duration + 5)
    try:
        s.connect((host, port))
        print(f"[*] Connected to {host}:{port}")

        # Step 1: getVersion
        payload = bytes([CMD_GET_VERSION])
        frame = struct.pack("<BI", FRAME_REQUEST, len(payload)) + payload
        s.send(frame)
        typ, payload = read_frame(s)
        if len(payload) >= 4:
            status = struct.unpack("<i", payload[:4])[0]
            version = struct.unpack("<i", payload[4:8])[0] if len(payload) >= 8 else -1
            print(f"[+] getVersion: status={status}, version={version}")
        else:
            print(f"[-] getVersion: short reply: {payload.hex()}")

        # Step 2: update("US") — MUST be called before start!
        payload = bytes([CMD_UPDATE]) + b"US"
        frame = struct.pack("<BI", FRAME_REQUEST, len(payload)) + payload
        s.send(frame)
        typ, payload = read_frame(s)
        if len(payload) >= 4:
            status = struct.unpack("<i", payload[:4])[0]
            print(f"[+] update(US): status={status}")
        else:
            print(f"[-] update: short reply: {payload.hex()}")

        # Step 3: start(channels) — pass actual WiFi channel numbers!
        # IMPORTANT: The int[] parameter to start() is a list of WiFi CHANNEL
        # numbers (not AIDL filter values). Channel 13 is invalid for AWDL.
        # AWDL uses channels like 149 (5GHz), 44 (5GHz), 6 (2.4GHz).
        payload = bytes([CMD_START]) + channels
        frame = struct.pack("<BI", FRAME_REQUEST, len(payload)) + payload
        s.send(frame)
        typ, payload = read_frame(s)
        if len(payload) >= 4:
            status = struct.unpack("<i", payload[:4])[0]
            print(f"[+] start(channels): status={status}")
        else:
            print(f"[-] start: short reply: {payload.hex()}")

        # Step 4: Listen for events
        print(f"[*] Listening for events (up to {duration}s)...")
        print(f"[*] Open AirDrop on an Apple device now!")
        print("━" * 60)

        start_time = time.time()
        event_count = 0

        # Step 3: Listen for events
        print(f"[*] Listening for events (up to {duration}s)...")
        print(f"[*] Open AirDrop on an Apple device now!")
        print("━" * 60)

        start_time = time.time()
        event_count = 0

        while time.time() - start_time < duration:
            try:
                typ, payload = read_frame(s)

                if typ == FRAME_EVENT:
                    event_count += 1
                    print(f"\n─── Event #{event_count} ───────────────────────")
                    print(f"   Frame type: EVENT (0x03)")
                    print(f"   Payload: {len(payload)} bytes")
                    print(f"   Raw hex: {payload.hex()}")

                    if len(payload) >= 1:
                        event_type = payload[0]
                        print(f"   Event type byte: 0x{event_type:02x}")

                    if len(payload) >= 5:
                        event_type = payload[0]
                        tx_code = struct.unpack("<i", payload[1:5])[0]
                        print(f"   tx_code: {tx_code} (0x{tx_code:08x})")

                    if len(payload) >= 9:
                        data_size = struct.unpack("<i", payload[5:9])[0]
                        print(f"   data_size: {data_size} bytes")

                    # Parse int32 fields
                    if len(payload) >= 9:
                        remaining = payload[9:]
                        int32_values = []
                        offset = 0
                        while offset + 4 <= len(remaining):
                            val = struct.unpack("<i", remaining[offset:offset+4])[0]
                            int32_values.append(val)
                            offset += 4

                        if int32_values:
                            print(f"   int32 fields ({len(int32_values)}):")
                            for i, v in enumerate(int32_values):
                                print(f"     [{i}] = {v} (0x{v:08x}, "
                                      f"hex bytes: {struct.pack('<i', v).hex()})")

                            # Hex dump remaining bytes
                            if offset < len(remaining):
                                remaining_bytes = remaining[offset:]
                                print(f"   Additional data ({len(remaining_bytes)} bytes): "
                                      f"{remaining_bytes.hex()}")

                    print("─" * 45)

                elif typ == FRAME_REPLY:
                    # Orphaned reply (shouldn't happen)
                    status = struct.unpack("<i", payload[:4])[0] if len(payload) >= 4 else -1
                    print(f"[?] Orphaned reply: status={status}, data={payload.hex()}")

                else:
                    print(f"[?] Unknown frame type: 0x{typ:02x}, len={len(payload)}")

            except socket.timeout:
                # No events within timeout period — that's expected
                if event_count == 0:
                    elapsed = int(time.time() - start_time)
                    print(f"\r[*] Waiting... ({elapsed}s, no events yet)", end="", flush=True)
                continue

        # Step 4: stop
        payload = bytes([CMD_STOP])
        frame = struct.pack("<BI", FRAME_REQUEST, len(payload)) + payload
        s.send(frame)
        typ, payload = read_frame(s)
        status = struct.unpack("<i", payload[:4])[0] if len(payload) >= 4 else -1
        print(f"\n[+] stop(): status={status}")

        print(f"\n[*] Total events received: {event_count}")

    except Exception as e:
        print(f"[-] Error: {e}")
    finally:
        s.close()


def main():
    host = DEFAULT_HOST
    port = DEFAULT_PORT

    if len(sys.argv) > 1:
        host = sys.argv[1]

    if len(sys.argv) > 2:
        port = int(sys.argv[2])

    command = sys.argv[3] if len(sys.argv) > 3 else "quick"

    if command == "listen":
        # Listen for events with optional channels
        channels_bytes = b"\x03\x95\x00\x00\x00\x2c\x00\x00\x00\x06\x00\x00\x00"  # default: [149,44,6]
        if len(sys.argv) > 4:
            channels = [int(ch) for ch in sys.argv[4:]]
            channels_bytes = bytes([len(channels)]) + b"".join(
                struct.pack("<i", ch) for ch in channels
            )
        listen_for_events(host, port, duration=60, channels=channels_bytes)

    elif command == "start":
        # Start with specific channels (auto-update US first)
        print("[*] Initializing mosey_server with update(US)...")
        send_command(host, port, CMD_UPDATE, b"US")  # ignore result

        channels_bytes = b"\x03\x95\x00\x00\x00\x2c\x00\x00\x00\x06\x00\x00\x00"  # default: [149,44,6]
        if len(sys.argv) > 4:
            channels = [int(ch) for ch in sys.argv[4:]]
            channels_bytes = bytes([len(channels)]) + b"".join(
                struct.pack("<i", ch) for ch in channels
            )
        success, result = send_command(host, port, CMD_START, channels_bytes)
        print(f"start(): success={success}, result={result.hex() if result else 'OK'}")

    elif command == "stop":
        success, result = send_command(host, port, CMD_STOP)
        print(f"stop(): success={success}")

    elif command == "version":
        success, result = send_command(host, port, CMD_GET_VERSION)
        if success and len(result) >= 4:
            v = struct.unpack("<i", result[:4])[0]
            print(f"getVersion(): {v}")
        else:
            print(f"getVersion(): failed ({result})")

    else:
        # Quick test: getVersion -> update("US") -> start -> stop
        print("=== getVersion (cmd=0) ===")
        success, result = send_command(host, port, CMD_GET_VERSION)
        if success and len(result) >= 4:
            v = struct.unpack("<i", result[:4])[0]
            print(f"  -> version = {v}")
        else:
            print(f"  -> FAILED: {result}")

        print("\n=== update (cmd=3, country='US') ===")
        success, result = send_command(host, port, CMD_UPDATE, b"US")
        if success:
            print(f"  -> status = OK (mosey_server initialized)")
        else:
            print(f"  -> FAILED: {result}")

        print("\n=== start (cmd=1, channels=[149,44,6] AWDL) ===")
        channels_bytes = bytes([3]) + struct.pack("<i", 149) + struct.pack("<i", 44) + struct.pack("<i", 6)
        success, result = send_command(host, port, CMD_START, channels_bytes)
        if success:
            print(f"  -> status = OK")
        else:
            print(f"  -> FAILED: {result}")

        print("\n=== stop (cmd=2) ===")
        success, result = send_command(host, port, CMD_STOP)
        if success:
            print(f"  -> status = OK")
        else:
            print(f"  -> FAILED: {result}")


if __name__ == "__main__":
    main()
