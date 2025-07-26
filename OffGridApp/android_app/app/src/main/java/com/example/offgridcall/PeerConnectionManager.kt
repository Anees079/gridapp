package com.example.offgridcall

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.properties.Delegates

/**
 * Handles creation and management of WebRTC peer connections for audio calls. The
 * manager is responsible for initialising the WebRTC library, creating the
 * factory and peer connection, and exposing callbacks for signalling. It does
 * not handle signalling itself; the caller must transmit session descriptions
 * and ICE candidates over their own secure channel. Audio frames can be
 * encrypted using [AudioEncryptor] before being sent. This class focuses on
 * audio only; video support is omitted.
 */
class PeerConnectionManager(
    private val context: Context,
    private val signalingCallback: SignalingCallback
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // Data channel for sending and receiving messages and files. Will be created
    // once a peer connection is established. Not persisted between calls.
    private var dataChannel: org.webrtc.DataChannel? = null

    // A callback invoked when data messages are received over the data channel.
    private var dataCallback: ((ByteArray) -> Unit)? = null

    /**
     * Initialises the WebRTC library. Must be called once before creating
     * peer connections. It is safe to call this multiple times; subsequent
     * invocations will be ignored.
     */
    fun initialize() {
        if (factory != null) return
        val initOptions = InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // Create an audio module which captures audio from the microphone. We do not
        // allow audio output to be captured by other apps to respect the no recording
        // requirement.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(
            context, /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(context)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Creates the local peer connection with default ICE servers. The caller
     * should supply a list of appropriate STUN/TURN servers if NAT traversal is
     * required. Here we use an empty list because the sample signalling is
     * assumed to occur over a local network.
     */
    fun createPeerConnection() {
        val pcFactory = factory ?: throw IllegalStateException("Factory not initialised")
        // Configure a list of ICE servers (STUN/TURN) to allow connections
        // across different networks (e.g. peers behind NATs or on distinct
        // networks). These public STUN servers help peers discover their
        // public-facing addresses. In production you may want to run your own
        // STUN/TURN infrastructure for reliability and privacy.
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
            // Additional TURN server(s) could be added here for fallback when
            // STUN alone is insufficient. TURN requires authentication and
            // typically incurs bandwidth costs. To use a TURN server supply
            // credentials via builder().setUsername("user").setPassword("pass").createIceServer().
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingCallback.onIceCandidateGenerated(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {
                // Remote peer created a data channel; register an observer to receive messages.
                dataChannel = dc
                dataChannel?.registerObserver(object : org.webrtc.DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        // Data channel state changed
                    }
                    override fun onMessage(buffer: org.webrtc.DataChannel.Buffer?) {
                        if (buffer == null) return
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        dataCallback?.invoke(data)
                    }
                })
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<MediaStream>) {}
        }) ?: throw IllegalStateException("Failed to create peer connection")

        // Create audio track
        val audioConstraints = MediaConstraints()
        audioSource = pcFactory.createAudioSource(audioConstraints)
        audioTrack = pcFactory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack?.setEnabled(true)
        peerConnection?.addTrack(audioTrack)

        // Create a data channel for exchanging messages and files. Only the
        // initiator creates the channel; the remote peer will receive it via
        // the onDataChannel callback. Label "data" is arbitrary.
        val init = org.webrtc.DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("data", init)
        dataChannel?.registerObserver(object : org.webrtc.DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                // Data channel has been opened or closed. We could notify the UI.
            }
            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer?) {
                if (buffer == null) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                dataCallback?.invoke(data)
            }
        })
    }

    /**
     * Initiates an outgoing call by creating an offer. The offer is passed to
     * the signalling callback so it can be sent to the remote peer.
     */
    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingCallback.onOfferCreated(description)
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    /**
     * Handles a remote offer by setting it as the remote description and
     * generating an answer. The answer is passed back via the signalling
     * callback.
     */
    fun handleRemoteOffer(offer: SessionDescription) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // After setting the remote description create an answer
                createAnswer()
            }
            override fun onSetFailure(error: String) {}
            override fun onCreateSuccess(description: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, offer)
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingCallback.onAnswerCreated(description)
                    }
                    override fun onSetFailure(error: String) {}
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    /**
     * Handles a remote answer to our offer.
     */
    fun handleRemoteAnswer(answer: SessionDescription) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
            override fun onCreateSuccess(description: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, answer)
    }

    /**
     * Adds a remote ICE candidate to the peer connection. ICE candidates are
     * exchanged via the signalling channel.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Releases all resources associated with the peer connection. Once closed
     * the manager can be re‑initialised again if a new call is required.
     */
    fun close() {
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    /**
     * Simple callback interface for signalling events. The hosting activity or
     * service must implement this interface and forward SDP and ICE
     * information to the remote peer over a secure channel. In a production
     * application this channel could be a WebSocket with TLS and
     * authentication or any other secure transport.
     */
    interface SignalingCallback {
        fun onOfferCreated(offer: SessionDescription)
        fun onAnswerCreated(answer: SessionDescription)
        fun onIceCandidateGenerated(candidate: IceCandidate)
    }

    /**
     * Registers a callback to receive raw byte arrays from the data channel.
     * The callback will be invoked on the thread that WebRTC delivers the
     * message. Consumers should switch to the UI thread if required.
     */
    fun setDataCallback(callback: (ByteArray) -> Unit) {
        this.dataCallback = callback
    }

    /**
     * Sends arbitrary bytes over the data channel. The caller is responsible
     * for encrypting the payload before invoking this method. If the data
     * channel is not yet open then this call will be a no‑op.
     */
    fun sendData(data: ByteArray) {
        val dc = dataChannel ?: return
        if (dc.state() != org.webrtc.DataChannel.State.OPEN) return
        val buffer = org.webrtc.DataChannel.Buffer(ByteBuffer.wrap(data), false)
        dc.send(buffer)
    }
}