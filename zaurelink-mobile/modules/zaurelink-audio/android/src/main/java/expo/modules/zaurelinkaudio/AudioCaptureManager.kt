package expo.modules.zaurelinkaudio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

/** One committed utterance's summary. Raw PCM is retained inside AudioCaptureManager and does NOT
 * cross the JS<->native bridge per-frame (the core architecture rule) — only this metadata is
 * emitted. utteranceId is the key the translate provider will use to fetch the retained PCM
 * native-to-native (Phase 4 hand-off), so audio still never crosses the bridge. */
data class UtteranceInfo(
  val utteranceId: String,
  val sampleCount: Int,
  val durationMs: Long,
  val peakLevel: Double,
)

enum class CaptureMode {
  /** Phase 2: user holds a button; the whole hold is one utterance. */
  PUSH_TO_TALK,

  /** Phase 3: continuous capture; DSP -> VAD -> SpeechCommitGate segments utterances automatically. */
  AUTO_VAD,
}

/**
 * TRD §1.2 AudioCaptureManager + §3 pipeline. Owns the AudioRecord session at 16kHz mono, runs
 * the DSP -> VAD -> commit-gate chain on a background thread, and retains committed-utterance PCM
 * natively. JS only ever sees throttled level events and per-utterance metadata.
 */
class AudioCaptureManager(
  context: Context,
  private val onLevel: (Double) -> Unit,
  private val onUtterance: (UtteranceInfo) -> Unit,
) {
  companion object {
    const val SAMPLE_RATE = 16000 // TRD §3.1: 16kHz mono PCM
    const val FRAME_SAMPLES = 320 // TRD §3.1: 20ms DSP frames @ 16kHz
    const val VAD_WINDOW = 512 // TRD §3.1: 32ms Silero VAD windows @ 16kHz
    private const val PREROLL_FRAMES = 10 // ~200ms of onset context prepended on speech start
    private const val LEVEL_EMIT_INTERVAL_MS = 100L // throttle level events; never per-frame
    private const val TAG = "ZaurelinkCapture"
  }

  // Pipeline stages. DSP = real WebRTC NS (self-degrades to passthrough if the native lib is
  // unavailable). VAD = real Silero if the ONNX model loads, else the energy baseline — both
  // decided once here and logged, so a packaging failure never kills capture.
  private val dsp: DspFilter = WebRtcNsDspFilter()
  private val energyVad = EnergyVadEngine()
  private val sileroVad: SileroVadEngine? =
    try {
      SileroVadEngine(context.applicationContext)
    } catch (t: Throwable) {
      Log.w(TAG, "Silero VAD unavailable, falling back to energy VAD: ${t.message}")
      null
    }
  private val vad: VadEngine = sileroVad ?: energyVad
  private val gate = SpeechCommitGate()

  /** Which VAD/DSP engine actually loaded (for honest UI/telemetry) — a native-lib load failure on
   * a given device degrades silently otherwise, so this surfaces it instead of hiding it. */
  val vadEngineName: String get() = if (sileroVad != null) "silero" else "energy"
  val dspEngineName: String get() = if (dsp.isActive) "webrtc_ns" else "passthrough"

  @Volatile
  var mode: CaptureMode = CaptureMode.PUSH_TO_TALK

  @Volatile private var isCapturing = false
  private var captureThread: Thread? = null
  private var audioRecord: AudioRecord? = null

  // Current utterance being accumulated (frame-sized chunks; avoids per-sample boxing).
  private val utterance = ArrayList<ShortArray>()
  private var utterancePeak = 0.0
  private val preRoll = ArrayDeque<ShortArray>()
  private var inSpeech = false

  // Rolling accumulator that re-frames 320-sample DSP frames into 512-sample VAD windows.
  private var vadCarry = ShortArray(VAD_WINDOW * 2)
  private var vadCarryLen = 0

  // Last committed utterance's PCM, retained for Phase 4 native hand-off / debugging.
  private var lastPcm = ShortArray(0)
  private var utteranceCounter = 0L

  val isActive: Boolean get() = isCapturing

  fun setVadConfig(rmsThreshold: Double, minSpeechFrames: Int, minSilenceFrames: Int) {
    energyVad.rmsThreshold = rmsThreshold
    gate.minSpeechFrames = minSpeechFrames
    gate.minSilenceFrames = minSilenceFrames
  }

  @Synchronized
  fun start() {
    if (isCapturing) return
    val minBuf =
      AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuf > 0) { "AudioRecord.getMinBufferSize returned $minBuf (unsupported config)" }

    val record =
      AudioRecord(
        // VOICE_COMMUNICATION engages the platform AEC/NS on the comms path, matching the
        // AudioManager MODE_IN_COMMUNICATION routing in AudioRoutingController.
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf, FRAME_SAMPLES * 2 * 4),
      )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      error("AudioRecord failed to initialize — check RECORD_AUDIO permission or mic availability")
    }

    resetPipeline()
    audioRecord = record
    isCapturing = true
    record.startRecording()
    captureThread = thread(name = "zaurelink-capture", isDaemon = true) { loop(record) }
  }

  private fun loop(record: AudioRecord) {
    val frame = ShortArray(FRAME_SAMPLES)
    var lastEmit = 0L
    while (isCapturing) {
      val n = record.read(frame, 0, frame.size)
      if (n <= 0) continue

      val denoised = dsp.process(if (n == frame.size) frame.copyOf() else frame.copyOf(n))

      var framePeak = 0
      for (i in denoised.indices) {
        val a = abs(denoised[i].toInt())
        if (a > framePeak) framePeak = a
      }
      val level = framePeak / 32768.0
      val now = System.currentTimeMillis()
      if (now - lastEmit >= LEVEL_EMIT_INTERVAL_MS) {
        lastEmit = now
        onLevel(level)
      }

      if (mode == CaptureMode.PUSH_TO_TALK) {
        appendUtterance(denoised)
      } else {
        processAutoVad(denoised)
      }
    }
  }

  private fun processAutoVad(denoised: ShortArray) {
    // Keep recent frames as onset context, dumped into the utterance when speech is confirmed.
    preRoll.addLast(denoised)
    while (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()
    if (inSpeech) appendUtterance(denoised)

    appendCarry(denoised)
    while (vadCarryLen >= VAD_WINDOW) {
      val window = vadCarry.copyOfRange(0, VAD_WINDOW)
      System.arraycopy(vadCarry, VAD_WINDOW, vadCarry, 0, vadCarryLen - VAD_WINDOW)
      vadCarryLen -= VAD_WINDOW

      when (gate.accept(vad.isSpeech(window))) {
        VadEvent.SPEECH_START -> {
          inSpeech = true
          utterancePeak = 0.0
          for (f in preRoll) appendUtterance(f)
        }
        VadEvent.SPEECH_END -> {
          inSpeech = false
          finalizeUtterance()
        }
        VadEvent.NONE -> {}
      }
    }
  }

  private fun appendCarry(frame: ShortArray) {
    if (vadCarryLen + frame.size > vadCarry.size) {
      vadCarry = vadCarry.copyOf(maxOf(vadCarry.size * 2, vadCarryLen + frame.size))
    }
    System.arraycopy(frame, 0, vadCarry, vadCarryLen, frame.size)
    vadCarryLen += frame.size
  }

  private fun appendUtterance(frame: ShortArray) {
    utterance.add(frame)
    var p = 0
    for (s in frame) {
      val a = abs(s.toInt())
      if (a > p) p = a
    }
    val lvl = p / 32768.0
    if (lvl > utterancePeak) utterancePeak = lvl
  }

  private fun finalizeUtterance() {
    val total = utterance.sumOf { it.size }
    if (total == 0) {
      utterance.clear()
      return
    }
    val out = ShortArray(total)
    var off = 0
    for (c in utterance) {
      c.copyInto(out, off)
      off += c.size
    }
    lastPcm = out
    val info =
      UtteranceInfo(
        utteranceId = "utt-${++utteranceCounter}",
        sampleCount = total,
        durationMs = total * 1000L / SAMPLE_RATE,
        peakLevel = utterancePeak,
      )
    utterance.clear()
    utterancePeak = 0.0
    onUtterance(info)
  }

  private fun resetPipeline() {
    gate.reset()
    vad.reset() // clear Silero's LSTM state so one utterance never bleeds into the next
    inSpeech = false
    vadCarryLen = 0
    utterancePeak = 0.0
    synchronized(utterance) { utterance.clear() }
    preRoll.clear()
  }

  /** Stops capture. In push-to-talk the whole hold becomes one utterance; in auto-VAD any
   * in-progress utterance is flushed. Emission goes through the onUtterance callback either way. */
  @Synchronized
  fun stop() {
    if (!isCapturing) return
    isCapturing = false
    joinThread(500)
    releaseRecord()

    if (mode == CaptureMode.PUSH_TO_TALK || inSpeech) {
      finalizeUtterance()
    } else {
      utterance.clear()
    }
    resetPipeline()
  }

  /** Fail-safe halt (TRD §4): stop immediately, emit nothing, drop any in-progress utterance. */
  fun release() {
    isCapturing = false
    joinThread(200)
    releaseRecord()
    utterance.clear()
    resetPipeline()
  }

  /** Escape hatch for debugging / Phase 4 native hand-off. Not used on the per-frame hot path. */
  fun getLastUtterancePcm(): ShortArray = lastPcm

  fun releaseEngines() {
    dsp.release()
    vad.release()
  }

  private fun joinThread(millis: Long) {
    try {
      captureThread?.join(millis)
    } catch (_: InterruptedException) {
    }
    captureThread = null
  }

  private fun releaseRecord() {
    audioRecord?.let {
      try {
        it.stop()
      } catch (_: Exception) {
      }
      it.release()
    }
    audioRecord = null
  }
}
