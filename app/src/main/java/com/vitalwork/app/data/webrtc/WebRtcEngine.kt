package com.vitalwork.app.data.webrtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the process-wide WebRTC bootstrap: a single [EglBase] (shared by the encoder/decoder and the
 * renderer) and a lazily-built [PeerConnectionFactory]. Heavy to create, so it's a [Singleton] and
 * the factory is only built on first use (first screen-share).
 */
@Singleton
class WebRtcEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Shared EGL context — also passed to each [org.webrtc.SurfaceViewRenderer]. */
    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
