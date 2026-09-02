package com.milen.videocall

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the tiny Node.js signaling server (see /signaling-server) over a
 * plain WebSocket. It only ever exchanges small JSON text messages - never
 * any audio/video - to help the two phones find each other and hand each
 * other the offer/answer/ICE-candidate data WebRTC needs to connect directly.
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onJoined(isInitiator: Boolean)
        fun onPeerJoined()
        fun onPeerLeft()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onError(message: String)
        fun onSocketClosed()
    }

    companion object {
        private const val TAG = "SignalingClient"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connectAndJoin(room: String) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val msg = JSONObject().apply {
                    put("type", "join")
                    put("room", room)
                }
                ws.send(msg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                listener.onError(t.message ?: "Connection error")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener.onSocketClosed()
            }
        })
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed message: $text")
            return
        }

        when (json.optString("type")) {
            "joined" -> listener.onJoined(json.optBoolean("isInitiator"))
            "peer-joined" -> listener.onPeerJoined()
            "peer-left" -> listener.onPeerLeft()
            "offer" -> listener.onOffer(json.optString("sdp"))
            "answer" -> listener.onAnswer(json.optString("sdp"))
            "ice" -> {
                val candidateJson = json.optJSONObject("candidate") ?: return
                listener.onIceCandidate(
                    candidateJson.optString("sdpMid", null),
                    candidateJson.optInt("sdpMLineIndex"),
                    candidateJson.optString("candidate")
                )
            }
            "error" -> listener.onError(json.optString("message"))
        }
    }

    fun sendOffer(sdp: String) = sendSdp("offer", sdp)

    fun sendAnswer(sdp: String) = sendSdp("answer", sdp)

    private fun sendSdp(type: String, sdp: String) {
        val msg = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
        }
        webSocket?.send(msg.toString())
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val candidateJson = JSONObject().apply {
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
        }
        val msg = JSONObject().apply {
            put("type", "ice")
            put("candidate", candidateJson)
        }
        webSocket?.send(msg.toString())
    }

    fun leave() {
        val msg = JSONObject().apply { put("type", "leave") }
        webSocket?.send(msg.toString())
        webSocket?.close(1000, "Leaving")
        webSocket = null
    }
}
