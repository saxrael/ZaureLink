package expo.modules.zaurelinkaudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class RoutingState(val value: String) {
  EARPOD_MIC("earpod_mic"),
  PHONE_MIC_SPEAKER("phone_mic_speaker"),
  DISCONNECTED("disconnected"),
}

/**
 * TRD §1.2 AudioRoutingController + §4 Hardware Audio Routing Protocol. Owns all AudioManager /
 * AudioDeviceInfo state and exposes routing as a simple enum. Written to fail closed on an
 * unexpected Bluetooth disconnect (mute + explicit opt-in required), never fail open (silent
 * reroute of the private channel to the public loudspeaker) — the highest live-demo/privacy risk.
 */
class AudioRoutingController(
  private val context: Context,
  private val onBluetoothDisconnected: () -> Unit,
  private val onRoutingChanged: (RoutingState) -> Unit,
) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile
  var state: RoutingState = RoutingState.PHONE_MIC_SPEAKER
    private set

  private var scoReceiver: BroadcastReceiver? = null

  /** Set while we are deliberately tearing SCO down, so the resulting DISCONNECTED broadcast is
   * recognised as our own doing rather than the user's earpiece dying. */
  @Volatile private var expectingScoTeardown = false

  /** Signals SCO actually reaching CONNECTED, so capture can wait for the link (see [awaitSco]). */
  @Volatile private var scoConnected: CountDownLatch? = null

  /**
   * True when a device capable of carrying the PRIVATE channel is present.
   *
   * Previously this tested only TYPE_BLUETOOTH_SCO, which is why ordinary earbuds were never
   * detected: modern TWS hardware enumerates as TYPE_BLE_HEADSET on Android 12+, and hearing aids as
   * their own type. A2DP is deliberately NOT accepted here even though it is Bluetooth audio — it is
   * an output-only profile with no microphone, so treating it as the private channel would capture
   * from the phone's own mic while playing into the user's ears, which is precisely the split that
   * leaks the private side of the conversation. Presence of an output-only sink is a different
   * question, answered by [hasBluetoothOutput].
   */
  fun hasBluetoothScoDevice(): Boolean = outputTypes().any { it in VOICE_CAPABLE_TYPES }

  /**
   * True when ANY Bluetooth sink is attached, including output-only A2DP/BLE speakers.
   *
   * This is the question the TTS layer needs: Android sends media audio to a connected sink
   * automatically, so anything spoken for the other party has to be forced to the built-in speaker
   * whenever this is true, regardless of whether the device can carry the private channel.
   */
  fun hasBluetoothOutput(): Boolean = outputTypes().any { it in BLUETOOTH_OUTPUT_TYPES }

  private fun outputTypes(): List<Int> =
    try {
      audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    } catch (t: Throwable) {
      // Enumeration throws SecurityException without BLUETOOTH_CONNECT on API 31+. Report "nothing
      // attached" rather than crashing: the caller then stays on the public channel, which is the
      // safe direction to be wrong in.
      Log.w(TAG, "Could not enumerate audio outputs: ${t.message}")
      emptyList()
    }

  // TRD §4.2. On API 31+ the SCO calls below are deprecated AND frequently no-ops — they return
  // without error while audio stays on the phone — so the modern setCommunicationDevice() path is
  // tried first and the legacy calls remain only for older devices (NFR-07's target floor).
  @Suppress("DEPRECATION")
  fun configureForBluetoothSco() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    val latch = CountDownLatch(1)
    scoConnected = latch

    val routed =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setCommunicationDeviceOfType(VOICE_CAPABLE_TYPES).also {
          if (it) latch.countDown() // setCommunicationDevice is synchronous; no SCO broadcast to await
        }
      } else {
        false
      }

    if (!routed) {
      audioManager.startBluetoothSco()
      audioManager.isBluetoothScoOn = true
    }
    audioManager.isSpeakerphoneOn = false
    setState(RoutingState.EARPOD_MIC)
  }

  /**
   * Blocks briefly until the SCO link is actually up.
   *
   * startBluetoothSco() is asynchronous and takes a few hundred milliseconds to establish the voice
   * link. Starting AudioRecord immediately means the opening moments of an utterance are captured
   * from the phone's own microphone — audible as a clipped or wrong-sounding first word, and on the
   * private channel it is also the wrong microphone entirely. Returns regardless after the timeout:
   * a slightly-late start beats refusing to record.
   */
  fun awaitSco(timeoutMs: Long = SCO_CONNECT_TIMEOUT_MS) {
    val latch = scoConnected ?: return
    try {
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "SCO did not report CONNECTED within ${timeoutMs}ms — starting capture anyway")
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  @Suppress("DEPRECATION")
  fun configureForPhoneMicSpeaker() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

    // Order is load-bearing. stopBluetoothSco() synchronously broadcasts SCO_AUDIO_STATE_DISCONNECTED,
    // and the receiver treats a disconnect while state == EARPOD_MIC as the earpiece failing: it
    // halted capture and raised the disconnect banner. Because state used to be updated AFTER this
    // call, the user's own deliberate "continue on speaker" fallback fired the very alarm it was
    // meant to resolve. Marking the teardown as expected and moving the state change ahead of it
    // makes the receiver ignore a disconnect we caused ourselves.
    expectingScoTeardown = true
    setState(RoutingState.PHONE_MIC_SPEAKER)
    scoConnected = null

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.clearCommunicationDevice()
      }
      audioManager.stopBluetoothSco()
      audioManager.isBluetoothScoOn = false
    } catch (t: Throwable) {
      Log.w(TAG, "Error tearing down SCO: ${t.message}")
    } finally {
      expectingScoTeardown = false
    }

    audioManager.isSpeakerphoneOn = true
  }

  fun registerScoReceiver() {
    if (scoReceiver != null) return
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
          val scoState =
            intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)

          if (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            scoConnected?.countDown()
            return
          }

          // Only an UNEXPECTED disconnect while we were actively on the private earpod channel is
          // the dangerous case (TRD §4.1 "Disconnected mid-session" row): halt, do not reroute.
          if (scoState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED &&
            state == RoutingState.EARPOD_MIC &&
            !expectingScoTeardown
          ) {
            setState(RoutingState.DISCONNECTED)
            onBluetoothDisconnected()
          }
        }
      }
    val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
    // API 33+ requires an explicit export flag; apps targeting 34+ crash without one. This receiver
    // listens to a system broadcast only, so it must not be exported.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(receiver, filter)
    }
    scoReceiver = receiver
  }

  @Suppress("DEPRECATION")
  fun teardown() {
    scoReceiver?.let {
      try {
        context.unregisterReceiver(it)
      } catch (_: Exception) {
      }
    }
    scoReceiver = null
    expectingScoTeardown = true
    scoConnected = null
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.clearCommunicationDevice()
      }
      audioManager.stopBluetoothSco()
    } catch (_: Exception) {
    }
    expectingScoTeardown = false
    audioManager.isBluetoothScoOn = false
    audioManager.isSpeakerphoneOn = false
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  private fun setCommunicationDeviceOfType(types: Set<Int>): Boolean =
    try {
      val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in types }
      if (device == null) {
        false
      } else {
        audioManager.setCommunicationDevice(device).also {
          Log.i(TAG, "setCommunicationDevice(type=${device.type}) -> $it")
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "setCommunicationDevice failed: ${t.message}")
      false
    }

  private fun setState(next: RoutingState) {
    state = next
    onRoutingChanged(next)
  }

  companion object {
    private const val TAG = "ZaurelinkRouting"
    private const val SCO_CONNECT_TIMEOUT_MS = 1500L

    /** Can carry a two-way voice channel (has a microphone). */
    private val VOICE_CAPABLE_TYPES: Set<Int> =
      buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
      }

    /** Any Bluetooth sink audio can land on, including output-only profiles. */
    private val BLUETOOTH_OUTPUT_TYPES: Set<Int> =
      buildSet {
        addAll(VOICE_CAPABLE_TYPES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
      }
  }
}
