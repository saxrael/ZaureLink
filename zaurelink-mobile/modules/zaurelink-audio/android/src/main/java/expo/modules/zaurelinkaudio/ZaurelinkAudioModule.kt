package expo.modules.zaurelinkaudio

import android.Manifest
import android.content.Context
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Phase 2 (TRD §3/§4): real AudioRecord capture + AudioManager routing, exposed to JS only as
 * high-level calls and events. Raw PCM never crosses the bridge per-frame — the capture loop and
 * routing state stay native; JS sees onAudioLevel (throttled), onUtteranceReady (metadata), and
 * the fail-safe onBluetoothDisconnected signal.
 *
 * Still deferred: DSP + Silero VAD (Phase 3) turn push-to-talk into automatic utterance detection,
 * and the LiteRT-LM providers (Phase 4) consume the retained PCM native-to-native.
 */
class ZaurelinkAudioModule : Module() {
  private var capture: AudioCaptureManager? = null
  private var routing: AudioRoutingController? = null

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() =
    ModuleDefinition {
      Name("ZaurelinkAudio")

      Events("onAudioLevel", "onUtteranceReady", "onBluetoothDisconnected", "onRoutingChanged")

      AsyncFunction("getRecordingPermission") { promise: Promise ->
        Permissions.getPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.RECORD_AUDIO,
        )
      }

      AsyncFunction("requestRecordingPermission") { promise: Promise ->
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.RECORD_AUDIO,
        )
      }

      Function("hasBluetoothSco") { ensureRouting().hasBluetoothScoDevice() }

      Function("getRoutingState") {
        routing?.state?.value ?: RoutingState.PHONE_MIC_SPEAKER.value
      }

      // Which VAD/DSP engine actually loaded on this device (Silero/WebRTC NS vs their fallbacks).
      // null until the capture manager is first created (e.g. by setCaptureMode/setVadConfig on
      // mount) — a real native-lib load failure is otherwise invisible until VAD behaves worse.
      Function("getEngineDiagnostics") {
        val c = capture
        if (c == null) null else mapOf("vad" to c.vadEngineName, "dsp" to c.dspEngineName)
      }

      // Capture segmentation strategy: "push_to_talk" (Phase 2) or "auto_vad" (Phase 3).
      Function("setCaptureMode") { mode: String ->
        ensureCapture().mode =
          if (mode == "auto_vad") CaptureMode.AUTO_VAD else CaptureMode.PUSH_TO_TALK
      }

      // Dev-tunable VAD parameters (TRD §3.1: field-tune against real recordings, never hardcode).
      Function("setVadConfig") {
          rmsThreshold: Double,
          sileroThreshold: Double,
          minSpeechFrames: Int,
          minSilenceFrames: Int,
        ->
        ensureCapture().setVadConfig(rmsThreshold, sileroThreshold, minSpeechFrames, minSilenceFrames)
      }

      // Start capture. preferBluetooth routes to the private earpod channel when an SCO device is
      // present (TRD §4.1), else the public phone mic/speaker channel. In auto_vad mode capture
      // runs until stopCapture and emits onUtteranceReady per detected utterance; in push_to_talk
      // the whole start..stop span is a single utterance.
      AsyncFunction("startCapture") { preferBluetooth: Boolean ->
        val r = ensureRouting()
        r.registerScoReceiver()
        if (preferBluetooth && r.hasBluetoothScoDevice()) {
          r.configureForBluetoothSco()
        } else {
          r.configureForPhoneMicSpeaker()
        }
        ensureCapture().start()
      }

      AsyncFunction("stopCapture") { capture?.stop() }

      // Explicit opt-in after a Bluetooth disconnect (PRD §3.3 / TRD §4): the user chooses to
      // continue on the public phone mic/speaker. Never invoked automatically.
      AsyncFunction("continueOnPhoneMicSpeaker") {
        ensureRouting().configureForPhoneMicSpeaker()
        ensureCapture().start()
      }

      OnDestroy {
        capture?.release()
        capture?.releaseEngines()
        routing?.teardown()
      }
    }

  private fun ensureCapture(): AudioCaptureManager =
    capture
      ?: AudioCaptureManager(
          context = context,
          onLevel = { level -> sendEvent("onAudioLevel", mapOf("level" to level)) },
          onUtterance = { info ->
            // Native hand-off (TRD §2.2): lastPcm is set before this callback fires (see
            // AudioCaptureManager.finalizeUtterance), so this is exactly this utterance's audio.
            // zaurelink-translate resolves it back out by id — raw PCM never reaches JS.
            capture?.getLastUtterancePcm()?.let { pcm -> UtteranceStore.put(info.utteranceId, pcm) }
            sendEvent(
              "onUtteranceReady",
              mapOf(
                "utteranceId" to info.utteranceId,
                "sampleCount" to info.sampleCount,
                "durationMs" to info.durationMs,
                "peakLevel" to info.peakLevel,
              ),
            )
          },
        )
        .also { capture = it }

  private fun ensureRouting(): AudioRoutingController =
    routing
      ?: AudioRoutingController(
          context = context,
          onBluetoothDisconnected = {
            // Fail-safe: halt capture immediately, do NOT reroute. JS shows the opt-in banner.
            capture?.release()
            sendEvent("onBluetoothDisconnected", emptyMap<String, Any?>())
          },
          onRoutingChanged = { s -> sendEvent("onRoutingChanged", mapOf("state" to s.value)) },
        )
        .also { routing = it }
}
