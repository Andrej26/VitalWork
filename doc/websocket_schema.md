# WebSocket Protocol Schema

## Overview

The VR application runs a WebSocket server that allows remote clients to send commands and receive notifications about in-game events. The server uses the websocket-sharp library and runs on port **9090** by default.

**Connection URL:** `ws://{ipAddress}:9090`


## Messages (Server → Client)

All outgoing messages use this format:

```json
{"type": "response|event", "success": true|false, "msg": "string"}
```

| Field | Type | Values |
|-------|------|--------|
| `type` | string | `"response"` or `"event"` |
| `success` | boolean | `true` or `false` |
| `msg` | string | Message or event name |
| `value` | number (optional) | Event-specific data; defaults to `0` when unused |


## Commands (Client → Server)

### `scene`

```json
{"command": "scene", "action": "reload"}
{"command": "scene", "action": "load", "sceneName": "SceneName"}
```

### `trigger_event`

```json
{"command": "trigger_event", "target": "GameObjectName", "eventName": "onClick"}
```

`eventName` is optional, defaults to `"onClick"`.


## Events (Server -> Client)
Same format as outgoing messages

```json
{"type": "response|event", "success": true|false, "msg": "string", "value": 0}
```

In `msg` is encoded event name. Client should handle these. The `value` field carries event-specific data where applicable (defaults to `0` when not used).

| Event | Description | `value` |
|-------|-------------|---------|
| `start_recording` | Sensors recording started | — |
| `stop_recording` | Sensors recording stopped | — |
| `suds` | SUDs score submitted by participant *(StressChamber build only)* | SUDs score (integer) |
