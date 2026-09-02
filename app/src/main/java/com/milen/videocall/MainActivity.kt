package com.milen.videocall

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Single-screen video call app for two specific people.
 *
 * How it works: both phones connect to the same signaling server and join a
 * room with the same name (a shared "room code" you both agree on, e.g. over
 * WhatsApp text). The first person to join is the "initiator" and creates a
 * WebRTC offer once the second person joins; the signaling server just
 * relays that offer/answer/ICE-candidate handshake. Once connected, video
 * and audio flow directly between the two phones (or via a TURN relay if a
 * direct connection isn't possible) - not through the signaling server.
 */
class MainActivity : AppCompatActivity(), WebRTCClient.Listener, SignalingClient.Listener {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001

        // Signaling server deployed on Render.
        private const val SIGNALING_SERVER_URL = "wss://videocall-signaling-zrd4.onrender.com"
    }

    private lateinit var rootEglBase: EglBase
    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null

    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var roomInput: EditText
    private lateinit var joinButton: Button
    private lateinit var hangupButton: Button
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button
    private lateinit var statusText: TextView
    private lateinit var joinBar: LinearLayout
    private lateinit var controlBar: LinearLayout

    private var isInitiator = false
    private var micEnabled = true
    private var cameraEnabled = true
    private var inCall = false

    // Auto-hide the on-screen buttons during a call so they don't block the video -
    // tapping anywhere on the video brings them back for a few seconds.
    private var controlsVisible = true
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val autoHideDelayMs = 4000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRenderer = findViewById(R.id.localRenderer)
        remoteRenderer = findViewById(R.id.remoteRenderer)
        roomInput = findViewById(R.id.roomInput)
        joinButton = findViewById(R.id.joinButton)
        hangupButton = findViewById(R.id.hangupButton)
        micButton = findViewById(R.id.micButton)
        cameraButton = findViewById(R.id.cameraButton)
        statusText = findViewById(R.id.statusText)
        joinBar = findViewById(R.id.joinBar)
        controlBar = findViewById(R.id.controlBar)

        rootEglBase = EglBase.create()
        remoteRenderer.init(rootEglBase.eglBaseContext, null)
        localRenderer.init(rootEglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        localRenderer.setZOrderMediaOverlay(true)

        joinButton.setOnClickListener { checkPermissionsAndJoin() }
        hangupButton.setOnClickListener { hangUp() }
        micButton.setOnClickListener { toggleMic() }
        cameraButton.setOnClickListener { toggleCamera() }

        // Tapping anywhere on the video toggles the buttons: hide them immediately
        // if shown, or bring them back (and restart the auto-hide timer) if hidden.
        remoteRenderer.setOnClickListener {
            if (!inCall) return@setOnClickListener
            if (controlsVisible) {
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
                setControlsVisible(false)
            } else {
                setControlsVisible(true)
                resetHideControlsTimer()
            }
        }

        setInCallUi(active = false)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val targetAlpha = if (visible) 1f else 0f
        for (view in listOf(controlBar, joinBar)) {
            if (visible) view.visibility = View.VISIBLE
            view.animate()
                .alpha(targetAlpha)
                .setDuration(200)
                .withEndAction { if (!visible) view.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun resetHideControlsTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (inCall) {
            hideControlsHandler.postDelayed(hideControlsRunnable, autoHideDelayMs)
        }
    }

    private fun checkPermissionsAndJoin() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCallFlow()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                startCallFlow()
            } else {
                Toast.makeText(this, "Нужен е достъп до камера и микрофон, за да се обадиш.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCallFlow() {
        val room = roomInput.text.toString().trim()
        if (room.isEmpty()) {
            Toast.makeText(this, "Въведи име на стая (същото и на двата телефона).", Toast.LENGTH_SHORT).show()
            return
        }
        if (SIGNALING_SERVER_URL.contains("YOUR-SIGNALING-SERVER-URL")) {
            Toast.makeText(
                this,
                "Първо въведи адреса на сървъра в MainActivity.kt (SIGNALING_SERVER_URL). Виж README.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setupAudioForCall()

        val client = WebRTCClient(applicationContext, rootEglBase.eglBaseContext, this)
        webRTCClient = client
        try {
            client.startLocalCapture(localRenderer)
        } catch (e: Exception) {
            Toast.makeText(this, "Камерата не може да бъде достъпена: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        client.createPeerConnection()

        val signaling = SignalingClient(SIGNALING_SERVER_URL, this)
        signalingClient = signaling
        signaling.connectAndJoin(room)

        statusText.text = "Свързване към сървъра..."
        setInCallUi(active = true)
        inCall = true
        resetHideControlsTimer()
    }

    private fun setupAudioForCall() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    // ---------------- SignalingClient.Listener ----------------

    override fun onJoined(isInitiator: Boolean) {
        this.isInitiator = isInitiator
        runOnUiThread {
            statusText.text = if (isInitiator) {
                "Чакаме другия човек да влезе в стаята..."
            } else {
                "Влязохме в стаята, свързваме се..."
            }
        }
    }

    override fun onPeerJoined() {
        runOnUiThread { statusText.text = "Установяваме връзка..." }
        if (isInitiator) {
            webRTCClient?.createOffer { desc ->
                signalingClient?.sendOffer(desc.description)
            }
        }
    }

    override fun onPeerLeft() {
        runOnUiThread {
            statusText.text = "Другият човек напусна разговора."
            remoteRenderer.clearImage()
        }
    }

    override fun onOffer(sdp: String) {
        webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
        webRTCClient?.createAnswer { desc ->
            signalingClient?.sendAnswer(desc.description)
        }
    }

    override fun onAnswer(sdp: String) {
        webRTCClient?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun onIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        webRTCClient?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, "Грешка: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSocketClosed() {
        runOnUiThread { statusText.text = "Връзката със сървъра е прекъсната." }
    }

    // ---------------- WebRTCClient.Listener ----------------

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        signalingClient?.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        runOnUiThread { track.addSink(remoteRenderer) }
    }

    override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState?) {
        runOnUiThread {
            statusText.text = when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> "Разговор"
                PeerConnection.PeerConnectionState.DISCONNECTED -> "Прекъсва се..."
                PeerConnection.PeerConnectionState.FAILED -> "Връзката пропадна. Опитай пак."
                PeerConnection.PeerConnectionState.CLOSED -> "Затворено"
                else -> statusText.text
            }
        }
    }

    // ---------------- UI actions ----------------

    private fun toggleMic() {
        micEnabled = !micEnabled
        webRTCClient?.setMicEnabled(micEnabled)
        micButton.text = if (micEnabled) "Микрофон вкл." else "Микрофон изкл."
    }

    private fun toggleCamera() {
        cameraEnabled = !cameraEnabled
        webRTCClient?.setCameraEnabled(cameraEnabled)
        cameraButton.text = if (cameraEnabled) "Камера вкл." else "Камера изкл."
    }

    private fun hangUp() {
        if (!inCall) return
        signalingClient?.leave()
        webRTCClient?.close()
        signalingClient = null
        webRTCClient = null
        remoteRenderer.clearImage()
        statusText.text = "Разговорът приключи."
        setInCallUi(active = false)
        inCall = false
        micEnabled = true
        cameraEnabled = true
        micButton.text = "Микрофон вкл."
        cameraButton.text = "Камера вкл."

        // Stop the auto-hide timer and make sure the buttons are visible again
        // for the next call.
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        setControlsVisible(true)
    }

    private fun setInCallUi(active: Boolean) {
        joinButton.isEnabled = !active
        roomInput.isEnabled = !active
        hangupButton.isEnabled = active
        micButton.isEnabled = active
        cameraButton.isEnabled = active
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacksAndMessages(null)
        if (inCall) {
            try { signalingClient?.leave() } catch (e: Exception) { /* ignore */ }
            try { webRTCClient?.close() } catch (e: Exception) { /* ignore */ }
        }
        localRenderer.release()
        remoteRenderer.release()
        rootEglBase.release()
    }
}
