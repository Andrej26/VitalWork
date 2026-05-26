# VR Headset WebSocket Mock Server

A simple GUI tool to emulate the VR headset's WebSocket server for testing the Android app.

## Setup

### 1. Install Python

Download and install Python 3.8 or later from:
https://www.python.org/downloads/

During installation, make sure to check **"Add Python to PATH"**.

To verify installation, open Command Prompt and run:
```
python --version
```

### 2. Install Dependencies

Open Command Prompt in this folder and run:
```
pip install -r requirements.txt
```

This installs both `websockets` (for the WebSocket server) and `zeroconf` (for mDNS auto-discovery).

### 3. Run the Mock Server

Navigate to the project folder and run:
```
python mock_server.py
```

A GUI window will appear showing the server address (e.g., `ws://192.168.1.100:9090`).

## Usage

### mDNS Auto-Discovery

The mock server automatically advertises itself on the local network using mDNS (Bonjour/Avahi) under the service type `_narrowingchamber._tcp.`. The Android app's VR Connection screen can discover the server automatically — no need to type an IP address manually.

To use auto-discovery:
1. Make sure both your laptop and the Android device are on the same WiFi network.
2. Start the mock server.
3. In the Android app, go to the VR Connection screen and tap **Discover**.
4. The mock server should appear in the discovered device list.

The status bar in the GUI shows **mDNS: Advertising** while the service is active. Closing the window unregisters the service cleanly.

### Connecting from Android (Manual)

If auto-discovery is not available, use the IP address shown in the GUI to connect manually:
```
ws://192.168.1.x:9090
```

Make sure both devices are on the same network.

### GUI Controls

- **Start Recording** - Sends a `start_recording` event to all connected clients
- **Stop Recording** - Sends a `stop_recording` event to all connected clients
- **Clear Log** - Clears the command log display

### Firewall

If you can't connect from another device, you may need to allow Python through Windows Firewall:

1. Open Windows Security
2. Go to Firewall & network protection
3. Click "Allow an app through firewall"
4. Click "Change settings", then "Allow another app"
5. Browse to your Python installation (e.g., `C:\Python312\python.exe`)
6. Check both Private and Public networks
7. Click OK

## Supported Commands

The server responds to these commands with mock data:

| Command | Description |
|---------|-------------|
| `scene` | Reload current scene or load a scene by name |
| `trigger_event` | Trigger a named event on a target object |
