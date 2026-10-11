package com.wildodds.gymtracker.data.reader.ingest

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns bytes into text without guessing wrong.
 *
 * The old extractor did `readText(UTF_8)` unconditionally, which silently produced replacement
 * characters for the two things users actually hand over: a Windows-exported CSV in Latin-1 or
 * Windows-1252 (a coach's spreadsheet with `°` or `×` in it), and a UTF-16 file from Notes or
 * TextEdit. Both look like a working import right up until the exercise names are garbage.
 *
 * Order: explicit BOM → strict UTF-8 → UTF-16 heuristics → Windows-1252. Windows-1252 is the last
 * resort rather than Latin-1 proper because it is a strict superset in the range that matters and
 * decodes every byte, so it can never fail — which makes it a safe terminator for the chain.
 */
object TextDecoding {

  data class Decoded(val text: String, val charset: Charset, val hadBom: Boolean)

  private val WINDOWS_1252: Charset =
    runCatching { Charset.forName("windows-1252") }.getOrElse { Charsets.ISO_8859_1 }

  fun decode(bytes: ByteArray, limit: Int = Int.MAX_VALUE): Decoded {
    val slice = if (bytes.size > limit) bytes.copyOf(limit) else bytes
    if (slice.isEmpty()) return Decoded("", Charsets.UTF_8, false)

    bom(slice)?.let { (cs, len) ->
      return Decoded(normalise(String(slice, len, slice.size - len, cs)), cs, true)
    }

    // UTF-16 without a BOM is common from Windows tooling. The tell is a regular pattern of NUL
    // bytes in one parity — ASCII text encoded as UTF-16 is every other byte zero.
    utf16WithoutBom(slice)?.let { cs ->
      return Decoded(normalise(String(slice, cs)), cs, false)
    }

    strictUtf8(slice)?.let { return Decoded(normalise(it), Charsets.UTF_8, false) }

    return Decoded(normalise(String(slice, WINDOWS_1252)), WINDOWS_1252, false)
  }

  private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
    b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
      Charsets.UTF_8 to 3
    b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
    b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
    else -> null
  }

  /**
   * Decode as UTF-8 with errors REPORTED rather than replaced, so a Latin-1 file fails here instead
   * of arriving downstream full of U+FFFD. That failure is the whole point of the strict decode.
   */
  private fun strictUtf8(b: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(b)).toString()
  } catch (_: CharacterCodingException) {
    null
  }

  private fun utf16WithoutBom(b: ByteArray): Charset? {
    val n = minOf(b.size, 4096)
    if (n < 8 || n % 2 != 0) return null
    var zeroEven = 0
    var zeroOdd = 0
    for (i in 0 until n) if (b[i] == 0.toByte()) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
    val half = n / 2
    // Three quarters of one parity being NUL, and almost none of the other, is not a coincidence.
    return when {
      zeroEven > half * 3 / 4 && zeroOdd < half / 8 -> Charsets.UTF_16BE
      zeroOdd > half * 3 / 4 && zeroEven < half / 8 -> Charsets.UTF_16LE
      else -> null
    }
  }

  /** CRLF and lone CR both become LF, and a stray BOM mid-stream is dropped. */
  fun normalise(s: String): String =
    s.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "")
}

/** Short alias used by [Sniffer], which needs only a decoded prefix. */
internal object TextDecoder {
  fun decode(bytes: ByteArray, limit: Int = Int.MAX_VALUE) = TextDecoding.decode(bytes, limit)
}
