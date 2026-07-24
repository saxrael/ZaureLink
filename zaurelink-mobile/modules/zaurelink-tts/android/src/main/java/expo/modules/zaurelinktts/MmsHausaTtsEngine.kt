package expo.modules.zaurelinktts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
  modelPath: String,
  private val onEvent: (String, Map<String, Any?>) -> Unit,
) {
  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
  private val session: OrtSession
  private val executor = Executors.newSingleThreadExecutor()

  @Volatile private var currentTrack: AudioTrack? = null
  private var counter = 0L

  init {
    // Load from the file path (ORT can mmap) rather than reading 109MB into a Java byte array.
    require(File(modelPath).exists()) { "MMS voice model not found at $modelPath" }
    session = env.createSession(modelPath, OrtSession.SessionOptions())
  }

  /** Synthesize + play on a background thread. Returns true if there was speakable text to say. */
  fun speak(text: String): Boolean {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) return false
    val id = "mms-${++counter}"
    executor.submit {
      try {
        onEvent("onTtsStart", mapOf("id" to id))
        play(synthesize(tokens))
        onEvent("onTtsDone", mapOf("id" to id))
      } catch (t: Throwable) {
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

  private fun play(pcm: ShortArray) {
    stopCurrent()
    val minBuf =
      AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
    currentTrack = track
    track.play()

    var offset = 0
    while (offset < pcm.size) {
      val written = track.write(pcm, offset, pcm.size - offset)
      if (written <= 0) break
      offset += written
    }
    // Let the buffered audio drain before releasing, unless a newer utterance replaced us.
    while (currentTrack === track &&
      track.playState == AudioTrack.PLAYSTATE_PLAYING &&
      track.playbackHeadPosition < pcm.size) {
      Thread.sleep(20)
    }
    if (currentTrack === track) {
      releaseTrack(track)
      currentTrack = null
    }
  }

  private fun stopCurrent() {
    currentTrack?.let { releaseTrack(it) }
    currentTrack = null
  }

  private fun releaseTrack(track: AudioTrack) {
    try {
      track.pause()
      track.flush()
      track.stop()
    } catch (_: Exception) {
    }
    track.release()
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

  /** facebook/mms-tts-hau tokenization: case-fold, keep only vocab chars, map to ids, then
   * intersperse the blank/pad id (0) → [0, t1, 0, …, 0, tn, 0]. Matches HF VitsTokenizer add_blank. */
  private fun tokenize(text: String): LongArray {
    val lower = text.lowercase()
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

    // facebook/mms-tts-hau vocab.json (34 entries). Blank/pad = id 0 (coincides with 'd', as in MMS).
    private val VOCAB: Map<Char, Int> =
      mapOf(
        ' ' to 33, '\'' to 26, '-' to 20, '6' to 21, '_' to 1,
        'a' to 9, 'b' to 17, 'c' to 14, 'd' to 0, 'e' to 22, 'f' to 24, 'g' to 4, 'h' to 28,
        'i' to 12, 'j' to 16, 'k' to 23, 'l' to 19, 'm' to 30, 'n' to 15, 'o' to 6, 'r' to 8,
        's' to 3, 't' to 5, 'u' to 10, 'w' to 27, 'y' to 7, 'z' to 29,
        'ā' to 11, 'ă' to 25, 'ū' to 13, 'ƙ' to 31, 'ɓ' to 18, 'ɗ' to 2, 'ˈ' to 32,
      )
  }
}
