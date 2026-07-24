package expo.modules.zaurelinkaudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager

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

  fun hasBluetoothScoDevice(): Boolean =
    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

  // TRD §4.2 — reproduced from the spec. startBluetoothSco()/isBluetoothScoOn are deprecated in
  // API 31+ (setCommunicationDevice() is the replacement) but retained here for broad compat with
  // the Snapdragon-4-series / older-Android target floor (NFR-07). Revisit if minSdk rises.
  @Suppress("DEPRECATION")
  fun configureForBluetoothSco() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.startBluetoothSco()
    audioManager.isBluetoothScoOn = true
    audioManager.isSpeakerphoneOn = false
    setState(RoutingState.EARPOD_MIC)
  }

  @Suppress("DEPRECATION")
  fun configureForPhoneMicSpeaker() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.stopBluetoothSco()
    audioManager.isBluetoothScoOn = false
    audioManager.isSpeakerphoneOn = true
    setState(RoutingState.PHONE_MIC_SPEAKER)
  }

  fun registerScoReceiver() {
    if (scoReceiver != null) return
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
          val scoState =
            intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
          // Only a disconnect while we were actively on the private earpod channel is the
          // dangerous case (TRD §4.1 "Disconnected mid-session" row): halt, do not reroute.
          if (scoState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && state == RoutingState.EARPOD_MIC) {
            setState(RoutingState.DISCONNECTED)
            onBluetoothDisconnected()
          }
        }
      }
    context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
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
    try {
      audioManager.stopBluetoothSco()
    } catch (_: Exception) {
    }
    audioManager.isBluetoothScoOn = false
    audioManager.isSpeakerphoneOn = false
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  private fun setState(next: RoutingState) {
    state = next
    onRoutingChanged(next)
  }
}
