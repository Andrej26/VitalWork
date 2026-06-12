package com.vitalwork.app.data.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /**
     * Was connected, then the link briefly went quiet, but the bond/pairing is kept and a
     * reconnect is expected automatically (e.g. a VR headset that dozed off). Rendered amber
     * "Reconnecting…" — distinct from a true [DISCONNECTED] (gray, link torn down / never up).
     */
    RECONNECTING,
    ERROR
}
