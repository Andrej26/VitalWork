package com.vitalwork.app.data.webrtc.model

/** State of the screen-share WebRTC session, surfaced to the link UI. */
enum class ShareState {
    /** No screen-share session. */
    IDLE,

    /** Server asked to view; awaiting the client's consent + offer. */
    REQUESTING,

    /** Client is capturing + sending its screen. */
    SHARING,

    /** Server is receiving + rendering the remote screen. */
    VIEWING,

    /** Setup failed. */
    ERROR
}
