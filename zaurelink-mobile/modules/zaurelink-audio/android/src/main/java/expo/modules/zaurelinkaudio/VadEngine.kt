package expo.modules.zaurelinkaudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * TRD §1.2 VadGate / §3.1. A voice-activity detector over fixed 32ms windows (512 samples @
 * 16kHz). Kept behind an interface so the Day-1 energy baseline and the Silero neural engine are
 * interchangeable — the same swap-boundary philosophy as the TranslationProvider tiers.
 */
interface VadEngine {
  /** True if the 512-sample window is human speech. Threshold is engine-internal (set at init),
   * matching the TRD §3.2 JNI contract where threshold is passed to vadInit(). */
  fun isSpeech(window: ShortArray): Boolean

  /** Reset any per-stream state (Silero carries an LSTM state across frames). Called on every
   * capture start/stop so one utterance's context never bleeds into the next. */
  fun reset()

  fun release()
}

/**
 * Day-1 baseline: RMS-energy threshold. No model, no native deps, works fully offline — the
 * guaranteed-to-run tier and the fallback when the Silero model can't load. Honest limitation:
 * energy alone cannot tell speech from any loud sound, so in a noisy market it over-triggers. That
 * is exactly the gap [SileroVadEngine] closes. The RMS threshold is normalized 0..1 and
 * field-tunable (TRD §3.1); it is NOT the same scale as Silero's 0.5 probability default.
 */
class EnergyVadEngine(
  @Volatile var rmsThreshold: Double = 0.015,
) : VadEngine {
  override fun isSpeech(window: ShortArray): Boolean {
    if (window.isEmpty()) return false
    var sumSq = 0.0
    for (s in window) {
      val v = s / 32768.0
      sumSq += v * v
    }
    val rms = sqrt(sumSq / window.size)
    return rms >= rmsThreshold
  }

  override fun reset() {}

  override fun release() {}
}

/**
 * TRD §3.1/§3.2 real Silero VAD. Runs the bundled silero_vad.onnx (v5) via ONNX Runtime Mobile's
 * Kotlin API — no hand-written JNI. Model I/O signature verified directly from the model file:
 *   inputs : input FLOAT[1,512], state FLOAT[2,1,128], sr INT64 scalar
 *   outputs: output FLOAT[1,1] (speech prob), stateN FLOAT[2,1,128] (LSTM state carried forward)
 *
 * This slots into the exact same per-window boolean the pipeline already consumes (thresholded
 * probability), so SpeechCommitGate and everything downstream are unchanged — the swap the slot
 * always promised. Session use is single-threaded (capture thread) but release() can race from the
 * fail-safe halt, so all ORT access is guarded by a lock.
 *
 * Construction throws if the model can't be loaded; AudioCaptureManager catches that and falls back
 * to [EnergyVadEngine], so a packaging/ABI problem degrades gracefully instead of killing capture.
 */
class SileroVadEngine(
  context: Context,
  @Volatile var threshold: Float = 0.5f, // TRD §3.1 default VAD confidence
) : VadEngine {
  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession
  private val lock = Any()

  // LSTM state carried across frames [2,1,128] = 256 floats, flattened row-major.
  private val state = FloatArray(2 * 128)
  private val sr = longArrayOf(SAMPLE_RATE)

  init {
    val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
    session = env.createSession(modelBytes, OrtSession.SessionOptions())
  }

  override fun isSpeech(window: ShortArray): Boolean {
    synchronized(lock) {
      val n = window.size
      val input = FloatArray(n)
      for (i in 0 until n) input[i] = window[i] / 32768f

      val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, n.toLong()))
      val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
      val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(sr), longArrayOf())
      try {
        val inputs = mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)
        session.run(inputs).use { results ->
          val prob = (results.get("output").get() as OnnxTensor).floatBuffer.get(0)
          // Feed the new LSTM state back for the next frame (raw buffer copy — no nested-array parse).
          (results.get("stateN").get() as OnnxTensor).floatBuffer.get(state, 0, state.size)
          return prob >= threshold
        }
      } finally {
        inputTensor.close()
        stateTensor.close()
        srTensor.close()
      }
    }
  }

  override fun reset() {
    synchronized(lock) { state.fill(0f) }
  }

  override fun release() {
    synchronized(lock) {
      try {
        session.close()
      } catch (_: Throwable) {
      }
    }
  }

  companion object {
    private const val MODEL_ASSET = "silero_vad.onnx"
    private const val SAMPLE_RATE = 16000L
  }
}
