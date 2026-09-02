package com.milen.videocall

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Wraps everything WebRTC-specific: camera/mic capture, the PeerConnection,
 * and creating/consuming offers, answers and ICE candidates.
 *
 * This class knows nothing about the network transport used to exchange
 * signaling messages - that's [SignalingClient]'s job. [MainActivity] wires
 * the two together.
 */
class WebRTCClient(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState?)
    }

    companion object {
        private const val TAG = "WebRTCClient"
    }

    // Public STUN (Google, free) + Open Relay Project's public free TURN servers.
    // TURN is what lets the call connect when both phones are behind strict /
    // carrier-grade NAT (common on mobile data) - STUN alone isn't always enough.
    // For a production app you'd want your own TURN credentials (see README),
    // but these public ones are fine to get started.
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        // Use WebRTC's own software echo canceller / noise suppressor instead of the
        // phone's hardware ones. On a lot of budget/mid-range Android phones the hardware
        // AEC is overly aggressive and "ducks" (mutes/suppresses) the mic whenever it
        // thinks it hears an echo - that's the cutting-out / suppressed sound. WebRTC's
        // software AEC3 is high quality and much less trigger-happy.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    /** Starts the camera + mic and renders the local preview into [localRenderer]. */
    fun startLocalCapture(localRenderer: SurfaceViewRenderer) {
        val capturer = createCameraCapturer()
            ?: throw IllegalStateException("No usable camera found on this device")
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        surfaceTextureHelper = helper

        val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        localVideoSource = videoSource
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("local_video_track", videoSource)
        videoTrack.addSink(localRenderer)
        localVideoTrack = videoTrack

        // Keep echo cancellation + noise suppression on (needed since the call runs on
        // speakerphone), but turn off automatic gain control - AGC is the other big cause
        // of audio that randomly "pumps"/ducks in volume, which sounds exactly like what
        // was reported.
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioSource = audioSource
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Prefer the front-facing camera for a face-to-face call.
        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                enumerator.createCapturer(name, null)?.let { return it }
            }
        }
        for (name in deviceNames) {
            enumerator.createCapturer(name, null)?.let { return it }
        }
        return null
    }

    /** Must be called after [startLocalCapture]. */
    fun createPeerConnection() {
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(candidate)
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) listener.onRemoteVideoTrack(track)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                listener.onConnectionStateChange(newState)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) listener.onRemoteVideoTrack(track)
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)

        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
    }

    fun createOffer(onLocalSdpReady: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(sdpObserver(onCreateSuccess = { desc ->
            val boosted = SessionDescription(desc.type, boostOpusAudioQuality(desc.description))
            peerConnection?.setLocalDescription(sdpObserver(), boosted)
            onLocalSdpReady(boosted)
        }), MediaConstraints())
    }

    fun createAnswer(onLocalSdpReady: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(sdpObserver(onCreateSuccess = { desc ->
            val boosted = SessionDescription(desc.type, boostOpusAudioQuality(desc.description))
            peerConnection?.setLocalDescription(sdpObserver(), boosted)
            onLocalSdpReady(boosted)
        }), MediaConstraints())
    }

    /**
     * By default Opus negotiates a fairly low, variable bitrate (~20-32 kbps) which is
     * exactly what makes calls sound like "free apps" - compressed and thin. This edits
     * the SDP text to explicitly request a higher, steadier bitrate plus FEC (forward
     * error correction, so a lost packet doesn't sound like a dropout on flaky mobile
     * data). No extra library needed - WebRTC's SDP is just plain text.
     */
    private fun boostOpusAudioQuality(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val opusRtpmapRegex = Regex("^a=rtpmap:(\\d+) opus/48000")
        var opusPayloadType: String? = null
        for (line in lines) {
            val match = opusRtpmapRegex.find(line)
            if (match != null) {
                opusPayloadType = match.groupValues[1]
                break
            }
        }
        if (opusPayloadType == null) return sdp

        val fmtpPrefix = "a=fmtp:$opusPayloadType"
        val extraParams = "maxaveragebitrate=128000;stereo=0;useinbandfec=1;maxplaybackrate=48000"
        var fmtpIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith(fmtpPrefix)) {
                fmtpIndex = i
                break
            }
        }
        if (fmtpIndex != -1) {
            if (!lines[fmtpIndex].contains("maxaveragebitrate")) {
                lines[fmtpIndex] = lines[fmtpIndex] + ";$extraParams"
            }
        } else {
            val rtpmapIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPayloadType") }
            if (rtpmapIndex != -1) {
                lines.add(rtpmapIndex + 1, "$fmtpPrefix $extraParams")
            }
        }
        return lines.joinToString("\r\n")
    }

    fun setRemoteDescription(desc: SessionDescription) {
        peerConnection?.setRemoteDescription(sdpObserver(), desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "stopCapture failed", e)
        }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoSource?.dispose()
        localAudioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun sdpObserver(
        onCreateSuccess: ((SessionDescription) -> Unit)? = null,
        onSetSuccess: (() -> Unit)? = null
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            desc?.let { onCreateSuccess?.invoke(it) }
        }
        override fun onSetSuccess() {
            onSetSuccess?.invoke()
        }
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failure: $error")
        }
    }
}
