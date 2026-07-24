package expo.modules.zaurelinkaudio

enum class VadEvent {
  NONE,
  SPEECH_START,
  SPEECH_END,
}

/**
 * TRD §3.1 "Speech buffer commit rule": commit an utterance only after N consecutive speech
 * frames (confirmed onset) AND M consecutive silence frames (confirmed end-of-utterance). This
 * prevents fragment-triggering on short noise bursts (a slammed car door, a shout) and avoids
 * cutting a sentence at a natural mid-utterance pause.
 *
 * Pure decision logic over per-window VAD booleans — no audio, no native deps — so it is fully
 * unit-testable and independent of which VadEngine (energy baseline vs Silero) produced the
 * boolean. Windows are 32ms each (512 samples @ 16kHz, TRD §3.1), so frame counts map to time:
 * N=4 ≈ 128ms onset, M=20 ≈ 640ms trailing silence.
 *
 * Counts are @Volatile so the dev-settings tunables (TRD §3.1: "do not hardcode blind") can be
 * adjusted live from JS while the capture thread reads them.
 */
class SpeechCommitGate(
  @Volatile var minSpeechFrames: Int = 4,
  @Volatile var minSilenceFrames: Int = 20,
) {
  private var inSpeech = false
  private var speechRun = 0
  private var silenceRun = 0

  val isInSpeech: Boolean
    get() = inSpeech

  fun reset() {
    inSpeech = false
    speechRun = 0
    silenceRun = 0
  }

  /** Feed one window's VAD decision; returns a boundary event when the commit rule fires. */
  fun accept(isSpeech: Boolean): VadEvent {
    if (!inSpeech) {
      if (isSpeech) {
        speechRun++
        if (speechRun >= minSpeechFrames) {
          inSpeech = true
          silenceRun = 0
          return VadEvent.SPEECH_START
        }
      } else {
        speechRun = 0
      }
      return VadEvent.NONE
    }

    if (!isSpeech) {
      silenceRun++
      if (silenceRun >= minSilenceFrames) {
        inSpeech = false
        speechRun = 0
        return VadEvent.SPEECH_END
      }
    } else {
      silenceRun = 0
    }
    return VadEvent.NONE
  }
}
