package expo.modules.zaurelinktts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors

/**
 * TRD §5 Hausa audio output via Meta MMS (facebook/mms-tts-hau) — a 36M-param VITS model run on
 * ONNX Runtime. This is the eSpeak replacement (eSpeak has no Hausa); Gemma still does all the
 * translation, this only speaks the Hausa text it produced.
 *
 * Everything here was verified against the real artifacts (same rigor as the Silero/LiteRT work):
 *   - Model I/O (inspected): inputs x INT64[1,L], x_length INT64[1], noise_scale/length_scale/
 *     noise_scale_w FLOAT[1]; output y FLOAT[1,1,samples] at 16kHz.
 *   - Tokenizer (facebook/mms-tts-hau: is_uroman=false, phonemize=false, add_blank=true): raw Hausa
 *     text → case-folded char→id via the model's own 34-entry vocab → blank(0) interspersed. No
 *     romanization/phonemization needed, so it runs fully on-device with no extra data files.
 *
 * The 109MB model is loaded from a downloaded file path (not bundled — it exceeds the APK budget),
 * so construction throws if the file is absent; the module treats that as "Hausa voice unavailable"
 * and degrades to text (NFR-06), exactly like a missing system voice.
 */
class MmsHausaTtsEngine(
  context: Context,
  modelPath: String,
  private val onEvent: (String, Map<String, Any?>) -> Unit,
) {
  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession
  private val executor = Executors.newSingleThreadExecutor()
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  /** Guards the handoff of AudioTrack ownership between the caller's thread and the play thread. */
  private val trackLock = Any()

  @Volatile private var currentTrack: AudioTrack? = null
  private var counter = 0L

  init {
    // Load from the file path (ORT can mmap) rather than reading 109MB into a Java byte array.
    require(File(modelPath).exists()) { "MMS voice model not found at $modelPath" }
    session = env.createSession(modelPath, OrtSession.SessionOptions())
  }

  /**
   * Synthesize + play on a background thread. Returns true if there was speakable text to say.
   *
   * @param toLoudspeaker forces playback out of the phone's built-in speaker instead of following
   *   Android's default routing. Required whenever this audio is meant for the OTHER party: with a
   *   Bluetooth earpiece attached, the system sends all media audio to it, so the translation
   *   intended for the person standing in front of the phone plays privately into the app user's ear
   *   and the other party hears nothing at all.
   */
  fun speak(text: String, toLoudspeaker: Boolean = false): Boolean {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) return false
    val id = "mms-${++counter}"
    executor.submit {
      try {
        onEvent("onTtsStart", mapOf("id" to id))
        play(synthesize(tokens), toLoudspeaker)
        onEvent("onTtsDone", mapOf("id" to id))
      } catch (t: Throwable) {
        Log.w(TAG, "MMS synthesis/playback failed: ${t.message}")
        onEvent("onTtsError", mapOf("id" to id))
      }
    }
    return true
  }

  private fun synthesize(x: LongArray): ShortArray {
    val len = x.size.toLong()
    val xT = OnnxTensor.createTensor(env, LongBuffer.wrap(x), longArrayOf(1, len))
    val xLenT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
    val nsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(NOISE_SCALE)), longArrayOf(1))
    val lsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(LENGTH_SCALE)), longArrayOf(1))
    val nswT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(NOISE_SCALE_W)), longArrayOf(1))
    try {
      val inputs =
        mapOf(
          "x" to xT,
          "x_length" to xLenT,
          "noise_scale" to nsT,
          "length_scale" to lsT,
          "noise_scale_w" to nswT,
        )
      session.run(inputs).use { results ->
        val y = results.get("y").get() as OnnxTensor
        val fb = y.floatBuffer
        val n = fb.remaining()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
          val s = fb.get(i) * 32767f
          pcm[i] = s.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return pcm
      }
    } finally {
      xT.close()
      xLenT.close()
      nsT.close()
      lsT.close()
      nswT.close()
    }
  }

  private fun play(pcm: ShortArray, toLoudspeaker: Boolean) {
    stopCurrent()
    val minBuf =
      AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            // USAGE_MEDIA follows the system's media route, which lands on a connected Bluetooth
            // sink. For the public side of the conversation that is exactly wrong, so it is declared
            // as guidance audio, which setPreferredDevice below can then pin to the built-in speaker.
            .setUsage(
              if (toLoudspeaker) {
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
              } else {
                AudioAttributes.USAGE_MEDIA
              }
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    if (toLoudspeaker) forceBuiltinSpeaker(track)

    // This thread owns `track` for its whole lifetime and is the ONLY thread that releases it.
    // Previously stop() released from the caller's thread while this one was still inside
    // track.write(), which is a use-after-release in native AudioTrack — an app-level crash no
    // Kotlin catch would see. stop() now only pauses/flushes, which is safe against a live track.
    try {
      synchronized(trackLock) { currentTrack = track }
      track.play()

      var offset = 0
      while (offset < pcm.size) {
        val written = track.write(pcm, offset, pcm.size - offset)
        if (written <= 0) break
        offset += written
      }

      // Let the buffered audio drain, unless a newer utterance replaced us.
      // The deadline is not belt-and-braces: playbackHeadPosition is a 32-bit wrapping counter that
      // on some HALs sits at 0 until hardware buffers start draining, so the position test alone can
      // never become false and this loop would pin the single TTS thread forever, silently ending
      // all future speech. Bound it by how long the audio actually is, plus slack.
      val deadline = System.currentTimeMillis() + (pcm.size * 1000L / SAMPLE_RATE) + DRAIN_SLACK_MS
      while (currentTrack === track &&
        track.playState == AudioTrack.PLAYSTATE_PLAYING &&
        track.playbackHeadPosition < pcm.size &&
        System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }
    } finally {
      synchronized(trackLock) { if (currentTrack === track) currentTrack = null }
      releaseTrack(track)
    }
  }

  /**
   * Pins output to the phone's own speaker so the other party hears it.
   *
   * setPreferredDevice is the only mechanism that overrides Bluetooth routing for a specific
   * AudioTrack without disturbing global audio state — flipping isSpeakerphoneOn would fight the
   * capture side's routing, which is simultaneously trying to hold the private channel open.
   */
  private fun forceBuiltinSpeaker(track: AudioTrack) {
    try {
      val speaker =
        audioManager
          .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
          .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
      if (speaker == null) {
        Log.w(TAG, "No built-in speaker reported; output may follow Bluetooth")
      } else if (!track.setPreferredDevice(speaker)) {
        Log.w(TAG, "setPreferredDevice(builtin speaker) refused; output may follow Bluetooth")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Could not pin output to the built-in speaker: ${t.message}")
    }
  }

  /** Silences the current utterance without releasing it — the play thread owns the release. */
  private fun stopCurrent() {
    synchronized(trackLock) {
      currentTrack?.let {
        try {
          it.pause()
          it.flush()
        } catch (_: Exception) {
        }
      }
      currentTrack = null
    }
  }

  private fun releaseTrack(track: AudioTrack) {
    try {
      track.pause()
      track.flush()
      track.stop()
    } catch (_: Exception) {
    }
    try {
      track.release()
    } catch (_: Exception) {
    }
  }

  fun stop() = stopCurrent()

  fun release() {
    stopCurrent()
    executor.shutdownNow()
    try {
      session.close()
    } catch (_: Throwable) {
    }
  }

  /** facebook/mms-tts-hau tokenization: expand digits to words, case-fold, keep only vocab chars,
   * map to ids, then intersperse the blank/pad id (0) → [0, t1, 0, …, 0, tn, 0]. Matches HF
   * VitsTokenizer add_blank. */
  private fun tokenize(text: String): LongArray {
    val lower = spellOutNumbers(text).lowercase()
    val ids = ArrayList<Int>(lower.length)
    for (ch in lower) VOCAB[ch]?.let { ids.add(it) }
    if (ids.isEmpty()) return LongArray(0)
    val out = LongArray(ids.size * 2 + 1) // all zeros = blanks; real ids land on odd indices
    for (i in ids.indices) out[i * 2 + 1] = ids[i].toLong()
    return out
  }

  companion object {
    const val SAMPLE_RATE = 16000 // facebook/mms-tts-hau config sampling_rate
    private const val NOISE_SCALE = 0.667f // VITS config defaults
    private const val NOISE_SCALE_W = 0.8f
    private const val LENGTH_SCALE = 1.0f // 1/speaking_rate; >1 slower, <1 faster
    private const val DRAIN_SLACK_MS = 2000L
    private const val TAG = "ZaurelinkMmsTts"

    // facebook/mms-tts-hau vocab.json (34 entries), transcribed verbatim from the published file.
    // 'd' really is id 0 and really does coincide with the pad/blank id: the model's own
    // tokenizer_config.json declares "pad_token": "d", so HuggingFace's VitsTokenizer produces the
    // exact same collision. It is a property of the upstream model, not a transcription slip —
    // giving 'd' a distinct id here would desynchronise us from the weights.
    private val VOCAB: Map<Char, Int> =
      mapOf(
        ' ' to 33, '\'' to 26, '-' to 20, '6' to 21, '_' to 1,
        'a' to 9, 'b' to 17, 'c' to 14, 'd' to 0, 'e' to 22, 'f' to 24, 'g' to 4, 'h' to 28,
        'i' to 12, 'j' to 16, 'k' to 23, 'l' to 19, 'm' to 30, 'n' to 15, 'o' to 6, 'r' to 8,
        's' to 3, 't' to 5, 'u' to 10, 'w' to 27, 'y' to 7, 'z' to 29,
        'ā' to 11, 'ă' to 25, 'ū' to 13, 'ƙ' to 31, 'ɓ' to 18, 'ɗ' to 2, 'ˈ' to 32,
      )

    private val NUMBER = Regex("\\d[\\d,]*")

    private val ONES =
      arrayOf("sifili", "ɗaya", "biyu", "uku", "huɗu", "biyar", "shida", "bakwai", "takwas", "tara")
    private val TENS =
      arrayOf(
        "", "goma", "ashirin", "talatin", "arba'in",
        "hamsin", "sittin", "saba'in", "tamanin", "casa'in",
      )

    /**
     * Replaces digit runs with Hausa number words before tokenization.
     *
     * The vocab contains exactly one digit — '6' — so every other numeral is silently dropped by the
     * char filter. That is not cosmetic: the system prompt explicitly instructs Gemma to resolve
     * currency into figures ("dari biyar" -> "500 naira"), so in Market Mode the price is the part
     * most likely to be a numeral, and "500 naira" was being spoken as " naira". A trader hearing
     * the unit but never the amount is worse than no audio at all.
     */
    internal fun spellOutNumbers(text: String): String =
      NUMBER.replace(text) { m ->
        val digits = m.value.replace(",", "")
        digits.toLongOrNull()?.let { hausaNumber(it) } ?: digits.map { d ->
          ONES.getOrElse(d - '0') { d.toString() }
        }.joinToString(" ")
      }

    /** Hausa cardinals. Beyond a million, reading digit-by-digit is likelier to be understood than
     * an invented compound, and no market or fare figure reaches that range anyway. */
    internal fun hausaNumber(n: Long): String =
      when {
        n < 0 -> "korau ${hausaNumber(-n)}"
        n < 10 -> ONES[n.toInt()]
        n < 20 -> if (n == 10L) TENS[1] else "${TENS[1]} sha ${ONES[(n % 10).toInt()]}"
        n < 100 -> joinRemainder(TENS[(n / 10).toInt()], n % 10)
        n < 1_000 -> joinRemainder(unitPhrase("ɗari", n / 100), n % 100)
        n < 1_000_000 -> joinRemainder(unitPhrase("dubu", n / 1_000), n % 1_000)
        n < 1_000_000_000 -> joinRemainder(unitPhrase("miliyan", n / 1_000_000), n % 1_000_000)
        else -> n.toString().map { ONES[it - '0'] }.joinToString(" ")
      }

    /** "ɗari" for exactly one hundred, "ɗari biyu" for two — Hausa omits the multiplier at one. */
    private fun unitPhrase(unit: String, count: Long): String =
      if (count == 1L) unit else "$unit ${hausaNumber(count)}"

    private fun joinRemainder(head: String, remainder: Long): String =
      if (remainder == 0L) head else "$head da ${hausaNumber(remainder)}"
  }
}
