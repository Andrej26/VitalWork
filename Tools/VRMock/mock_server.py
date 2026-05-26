"""
VR Headset WebSocket Mock Server
A GUI application that emulates the VR headset's WebSocket server for testing.
"""

import asyncio
import json
import queue
import socket
import threading
import tkinter as tk
from tkinter import scrolledtext
from datetime import datetime
from typing import Set

try:
    import websockets
    from websockets.asyncio.server import ServerConnection
except ImportError:
    print("Error: websockets library not found.")
    print("Please install it with: pip install -r requirements.txt")
    exit(1)

try:
    from zeroconf import Zeroconf, ServiceInfo
except ImportError:
    print("Error: zeroconf library not found.")
    print("Please install it with: pip install -r requirements.txt")
    exit(1)


def get_local_ip():
    """Get the local IP address for display purposes."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "0.0.0.0"


class MdnsAdvertiser:
    SERVICE_TYPE = "_narrowingchamber._tcp.local."
    SERVICE_NAME = "NarrowingChamber"

    def __init__(self, port: int):
        self.port = port
        self._zeroconf: Zeroconf | None = None
        self._info: ServiceInfo | None = None

    def start(self, log_fn):
        ip = get_local_ip()
        self._zeroconf = Zeroconf()
        self._info = ServiceInfo(
            type_=self.SERVICE_TYPE,
            name=f"{self.SERVICE_NAME}.{self.SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=self.port,
            properties={},
        )
        self._zeroconf.register_service(self._info, strict=False)
        log_fn(f"mDNS: advertising '{self.SERVICE_NAME}' on {ip}:{self.port}")

    def stop(self, log_fn):
        if self._zeroconf and self._info:
            self._zeroconf.unregister_service(self._info)
            self._zeroconf.close()
            self._zeroconf = None
            self._info = None
            log_fn("mDNS: advertising stopped")


class MockVRServer:
    def __init__(self):
        self.clients: Set[ServerConnection] = set()
        self.log_callback = None
        self.connection_callback = None

    def set_callbacks(self, log_callback, connection_callback):
        self.log_callback = log_callback
        self.connection_callback = connection_callback

    def log(self, message: str):
        if self.log_callback:
            self.log_callback(message)

    def update_connection_status(self):
        if self.connection_callback:
            self.connection_callback(len(self.clients))

    async def handle_command(self, command: dict) -> dict:
        """Process incoming command and return appropriate mock response."""
        cmd_type = command.get("command", "unknown")

        if cmd_type == "scene":
            action = command.get("action", "reload")
            if action == "load":
                scene_name = command.get("sceneName", "DefaultScene")
                return {
                    "type": "response",
                    "success": True,
                    "msg": f"Scene '{scene_name}' loaded"
                }
            else:
                return {
                    "type": "response",
                    "success": True,
                    "msg": "Scene reloaded"
                }

        elif cmd_type == "trigger_event":
            event_name = command.get("eventName", "onClick")
            target = command.get("target", "default_target")
            return {
                "type": "response",
                "success": True,
                "msg": f"Event '{event_name}' triggered on '{target}'"
            }

        else:
            return {
                "type": "response",
                "success": False,
                "msg": f"Unknown command: {cmd_type}"
            }

    async def handler(self, websocket: ServerConnection):
        """Handle a WebSocket connection."""
        self.clients.add(websocket)
        client_addr = websocket.remote_address
        self.log(f"Client connected: {client_addr}")
        self.update_connection_status()

        try:
            async for message in websocket:
                self.log(f"Received: {message}")

                try:
                    command = json.loads(message)
                    response = await self.handle_command(command)
                    response_json = json.dumps(response)
                    await websocket.send(response_json)
                    self.log(f"Sent: {response_json}")
                except json.JSONDecodeError:
                    error_response = {
                        "type": "response",
                        "success": False,
                        "msg": "Invalid JSON"
                    }
                    await websocket.send(json.dumps(error_response))
                    self.log(f"Error: Invalid JSON received")

        except websockets.exceptions.ConnectionClosed:
            self.log(f"Client disconnected: {client_addr}")
        finally:
            self.clients.discard(websocket)
            self.update_connection_status()

    async def broadcast(self, message: dict):
        """Send a message to all connected clients."""
        if not self.clients:
            self.log("No clients connected to receive broadcast")
            return

        message_json = json.dumps(message)
        self.log(f"Broadcasting: {message_json}")

        websockets_to_remove = set()
        for client in self.clients:
            try:
                await client.send(message_json)
            except websockets.exceptions.ConnectionClosed:
                websockets_to_remove.add(client)

        for client in websockets_to_remove:
            self.clients.discard(client)
        self.update_connection_status()

    def send_start_recording(self, loop):
        """Send start_recording event to all clients."""
        event = {
            "type": "event",
            "success": True,
            "msg": "start_recording"
        }
        asyncio.run_coroutine_threadsafe(self.broadcast(event), loop)

    def send_stop_recording(self, loop):
        """Send stop_recording event to all clients."""
        event = {
            "type": "event",
            "success": True,
            "msg": "stop_recording"
        }
        asyncio.run_coroutine_threadsafe(self.broadcast(event), loop)

    def send_suds(self, loop, value: int):
        """Send a suds event with the given value (0–9) to all clients."""
        event = {
            "type": "event",
            "success": True,
            "msg": "suds",
            "value": value
        }
        asyncio.run_coroutine_threadsafe(self.broadcast(event), loop)


class MockServerGUI:
    def __init__(self):
        self.server = MockVRServer()
        self.mdns = MdnsAdvertiser(port=9090)
        self.loop = None
        self.server_task = None
        self._log_queue: queue.Queue = queue.Queue()
        self._conn_queue: queue.Queue = queue.Queue()

        # Create main window
        self.root = tk.Tk()
        self.root.title("VR Headset Mock Server")
        self.root.geometry("700x500")
        self.root.minsize(500, 400)

        self._create_widgets()
        self.server.set_callbacks(self._log_message, self._update_connection_status)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _create_widgets(self):
        # Top frame for status
        status_frame = tk.Frame(self.root, padx=10, pady=5)
        status_frame.pack(fill=tk.X)

        self.local_ip = get_local_ip()
        tk.Label(status_frame, text="Server Status:").pack(side=tk.LEFT)
        self.status_label = tk.Label(status_frame, text=f"Running on ws://{self.local_ip}:9090", fg="green")
        self.status_label.pack(side=tk.LEFT, padx=5)

        tk.Label(status_frame, text="|").pack(side=tk.LEFT, padx=5)

        tk.Label(status_frame, text="Connections:").pack(side=tk.LEFT)
        self.connection_label = tk.Label(status_frame, text="0", fg="blue", width=3)
        self.connection_label.pack(side=tk.LEFT)

        tk.Label(status_frame, text="|").pack(side=tk.LEFT, padx=5)
        tk.Label(status_frame, text="mDNS:").pack(side=tk.LEFT)
        tk.Label(status_frame, text="Advertising", fg="green").pack(side=tk.LEFT, padx=5)

        # Log area
        log_frame = tk.Frame(self.root, padx=10, pady=5)
        log_frame.pack(fill=tk.BOTH, expand=True)

        tk.Label(log_frame, text="Command Log:").pack(anchor=tk.W)

        self.log_area = scrolledtext.ScrolledText(
            log_frame,
            wrap=tk.WORD,
            font=("Consolas", 9),
            bg="#1e1e1e",
            fg="#d4d4d4"
        )
        self.log_area.pack(fill=tk.BOTH, expand=True)
        self.log_area.config(state=tk.DISABLED)

        # Button frame — row 1: recording triggers + clear log
        button_frame = tk.Frame(self.root, padx=10, pady=5)
        button_frame.pack(fill=tk.X)

        tk.Label(button_frame, text="Recording Triggers:").pack(side=tk.LEFT)

        self.start_bf_btn = tk.Button(
            button_frame,
            text="Start Recording",
            command=self._send_start_recording,
            bg="#4CAF50",
            fg="white",
            padx=10
        )
        self.start_bf_btn.pack(side=tk.LEFT, padx=5)

        self.stop_bf_btn = tk.Button(
            button_frame,
            text="Stop Recording",
            command=self._send_stop_recording,
            bg="#f44336",
            fg="white",
            padx=10
        )
        self.stop_bf_btn.pack(side=tk.LEFT, padx=5)

        tk.Frame(button_frame).pack(side=tk.LEFT, expand=True)

        self.clear_btn = tk.Button(
            button_frame,
            text="Clear Log",
            command=self._clear_log,
            padx=10
        )
        self.clear_btn.pack(side=tk.RIGHT)

        # Button frame — row 2: SUDs event
        suds_frame = tk.Frame(self.root, padx=10, pady=5)
        suds_frame.pack(fill=tk.X)

        tk.Label(suds_frame, text="SUDs Event:").pack(side=tk.LEFT)

        self.suds_spinbox = tk.Spinbox(
            suds_frame,
            from_=0,
            to=9,
            width=4,
            font=("Consolas", 11),
            justify=tk.CENTER
        )
        self.suds_spinbox.pack(side=tk.LEFT, padx=5)

        self.send_suds_btn = tk.Button(
            suds_frame,
            text="Send SUDs",
            command=self._send_suds,
            bg="#9C27B0",
            fg="white",
            padx=10
        )
        self.send_suds_btn.pack(side=tk.LEFT, padx=5)

    def _log_message(self, message: str):
        """Queue a log message for display on the main thread."""
        timestamp = datetime.now().strftime("%H:%M:%S")
        self._log_queue.put(f"[{timestamp}] {message}\n")

    def _update_connection_status(self, count: int):
        """Queue a connection count update for the main thread."""
        self._conn_queue.put(count)

    def _poll_queues(self):
        """Drain log and connection queues on the main thread, then reschedule."""
        try:
            while True:
                msg = self._log_queue.get_nowait()
                self.log_area.config(state=tk.NORMAL)
                self.log_area.insert(tk.END, msg)
                self.log_area.see(tk.END)
                self.log_area.config(state=tk.DISABLED)
        except queue.Empty:
            pass

        try:
            count = None
            while True:
                count = self._conn_queue.get_nowait()
        except queue.Empty:
            pass
        if count is not None:
            self.connection_label.config(
                text=str(count),
                fg="green" if count > 0 else "blue",
            )

        self.root.after(100, self._poll_queues)

    def _clear_log(self):
        """Clear the log area."""
        self.log_area.config(state=tk.NORMAL)
        self.log_area.delete(1.0, tk.END)
        self.log_area.config(state=tk.DISABLED)

    def _send_start_recording(self):
        """Send start_recording event to all clients."""
        if self.loop:
            self.server.send_start_recording(self.loop)

    def _send_stop_recording(self):
        """Send stop_recording event to all clients."""
        if self.loop:
            self.server.send_stop_recording(self.loop)

    def _send_suds(self):
        """Send a suds event with the selected value to all clients."""
        if self.loop:
            try:
                value = int(self.suds_spinbox.get())
                value = max(0, min(9, value))
                self.server.send_suds(self.loop, value)
            except ValueError:
                self._log_message("Error: Invalid SUDs value")

    async def _run_server(self):
        """Run the WebSocket server."""
        async with websockets.serve(self.server.handler, "0.0.0.0", 9090):
            self._log_message(f"WebSocket server started on ws://{self.local_ip}:9090")
            self._log_message("Listening on all interfaces (0.0.0.0:9090)")
            await asyncio.Future()  # Run forever

    def _start_server_thread(self):
        """Start the server in a separate thread."""
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self._run_server())

    def _on_close(self):
        """Handle window close: stop mDNS advertising then destroy window."""
        self.mdns.stop(self._log_message)
        self.root.destroy()

    def run(self):
        """Start the GUI and server."""
        # Start server in background thread
        server_thread = threading.Thread(target=self._start_server_thread, daemon=True)
        server_thread.start()

        # Start mDNS advertising
        self.mdns.start(self._log_message)

        # Start draining the thread-safe queues on the main thread
        self.root.after(100, self._poll_queues)

        # Run GUI main loop
        self.root.mainloop()


def main():
    app = MockServerGUI()
    app.run()


if __name__ == "__main__":
    main()
