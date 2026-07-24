package expo.modules.zaurelinkaudio

/**
 * TRD §1.2 DspFilterEngine / §3. Noise-suppression stage operating on fixed 20ms frames (320
 * samples @ 16kHz), ahead of the VAD gate. Interface-backed so the real RNNoise/WebRTC-NS engine
 * drops in behind the same call the pipeline already makes.
 */
interface DspFilter {
  /** Returns a denoised frame the same length as the input. */
  fun process(frame: ShortArray): ShortArray

  fun release()

  /** True if real noise suppression is active; false if this stage is a passthrough (no-op) —
   * surfaced to JS/UI so a native-lib load failure on a given device is visible, not silent. */
  val isActive: Boolean
}

/**
 * Day-1 baseline: no-op passthrough. Establishes the DSP stage in the pipeline (TRD §1.1) without
 * pretending to denoise. This is a defensible default: TRD §3.3 explicitly warns against
 * over-relying on DSP under demo conditions and keeps the manual "speak closer/louder" UX prompt
 * as the deterministic safety net — so real DSP is a quality upgrade, not a pipeline blocker.
 */
class PassthroughDspFilter : DspFilter {
  override fun process(frame: ShortArray): ShortArray = frame

  override fun release() {}

  override val isActive: Boolean = false
}

/**
 * TRD §3.2 DSP stage — real WebRTC software Noise Suppressor (org.webrtc.NoiseSuppressor from the
 * prebuilt libwebrtc-android JNI wrapper). Chosen over RNNoise because WebRTC NS natively supports
 * 16kHz, whereas RNNoise is hardcoded to 48kHz and would force resampling that adds latency against
 * NFR-01. Per TRD §3.3 this is a real quality upgrade, not a pipeline blocker: it degrades to
 * passthrough if the native suppressor can't be created.
 *
 * Frame bridging: WebRTC NS requires exactly 160 samples (10ms @ 16kHz) per call, but the pipeline
 * runs 320-sample (20ms) DSP frames — so each frame is processed as two consecutive 160-sample
 * sub-frames and recombined. The native handle is released on session teardown / Bluetooth
 * disconnect (release()), avoiding the memory-leak-on-route-switch the TRD §3.2 names.
 */
class WebRtcNsDspFilter(
  /** 0 = mild, 1 = medium, 2 = aggressive. Field-tunable; market noise likely wants 2. */
  mode: Int = 2,
) : DspFilter {
  private var suppressor: org.webrtc.NoiseSuppressor? =
    try {
      org.webrtc.NoiseSuppressor(16000, mode)
    } catch (_: Throwable) {
      // Missing native lib / unsupported ABI — fall back to passthrough rather than crash capture.
      null
    }

  private val sub = ShortArray(SUB_FRAME)
  private val subOut = ShortArray(SUB_FRAME)

  override val isActive: Boolean = suppressor != null

  override fun process(frame: ShortArray): ShortArray {
    val ns = suppressor ?: return frame
    // Only whole 160-sample sub-frames can be denoised; a non-multiple tail passes through as-is.
    val usable = frame.size - (frame.size % SUB_FRAME)
    if (usable == 0) return frame
    val out = frame.copyOf() // tail (if any) stays as the original samples
    var offset = 0
    while (offset + SUB_FRAME <= usable) {
      System.arraycopy(frame, offset, sub, 0, SUB_FRAME)
      try {
        ns.processAudioFrame(sub, subOut, SUB_FRAME.toShort())
        System.arraycopy(subOut, 0, out, offset, SUB_FRAME)
      } catch (_: Throwable) {
        System.arraycopy(sub, 0, out, offset, SUB_FRAME) // leave this sub-frame untouched on error
      }
      offset += SUB_FRAME
    }
    return out
  }

  override fun release() {
    try {
      suppressor?.close()
    } catch (_: Throwable) {
    }
    suppressor = null
  }

  companion object {
    const val SUB_FRAME = 160 // WebRTC NS strict frame size at 16kHz (10ms)
  }
}
