package com.wildodds.gymtracker.data.reader

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * **The reader is fully offline, and this test is what keeps it that way.**
 *
 * The constraint is not a preference: import must work in airplane mode, with no account, no key and
 * no server, on first launch. A single well-meaning `URL(...)` added later — to fetch an exercise
 * icon, to look up a name, to report a parse failure — would break that silently, and nobody would
 * notice until a user on a plane found the feature dead.
 *
 * So this reads the actual COMPILED CLASS FILES of the reader packages and scans their constant
 * pools for any reference to a networking type. Scanning bytecode rather than source is deliberate:
 * source-level greps miss a network call reached through a helper, an import alias, or a string
 * passed to reflection, and the constant pool records every type a class actually references.
 *
 * `ClaudeApiClient` is the one permitted networked path in the app. It lives in `data/ai/`, is
 * opt-in behind a user-supplied key, is off by default, and is deliberately NOT in the scanned
 * packages — and nothing in the scanned packages is allowed to reference it either, which is the
 * second assertion here.
 */
class ReaderNoNetworkTest {

  /** Every package that makes up the Reader. Adding a new one without adding it here is the bug. */
  private val readerPackages = listOf(
    "com/wildodds/gymtracker/data/reader",
    "com/wildodds/gymtracker/data/importer",
    "com/wildodds/gymtracker/data/parser"
  )

  /**
   * Networking types, as they appear in a constant pool. Deliberately broad: it is far better to
   * force a future author to justify an exception here than to let one slip through.
   */
  private val forbidden = listOf(
    "java/net/URL",
    "java/net/URI",
    "java/net/Socket",
    "java/net/HttpURLConnection",
    "java/net/URLConnection",
    "java/net/InetAddress",
    "java/net/DatagramSocket",
    "java/net/Proxy",
    "javax/net/ssl",
    "okhttp3/",
    "okio/",
    "retrofit2/",
    "io/ktor/client",
    "android/net/ConnectivityManager",
    "android/webkit/WebView",
    "java/nio/channels/SocketChannel",
    // The one permitted networked path must not be reachable from the reader.
    "com/wildodds/gymtracker/data/ai/ClaudeApiClient"
  )

  @Test fun `no class in the reader packages references a networking API`() {
    val roots = classRoots()
    assertTrue(
      "No compiled classes were found. This test is worthless if it silently finds nothing, so it " +
      "fails instead. Looked in: ${candidateRoots().joinToString()}",
      roots.isNotEmpty()
    )

    val offences = mutableListOf<String>()
    var scanned = 0
    for (root in roots) {
      for (pkg in readerPackages) {
        val dir = File(root, pkg)
        if (!dir.isDirectory) continue
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
          scanned++
          val bytes = f.readBytes()
          for (needle in forbidden) {
            if (bytes.contains(needle.toByteArray(Charsets.UTF_8))) {
              offences += "${f.relativeTo(root).path.replace('\\', '/')} references $needle"
            }
          }
        }
      }
    }

    assertTrue("No reader classes were scanned — check the package list.", scanned > 0)
    if (offences.isNotEmpty()) {
      fail(
        "The Reader must work fully offline. These classes reference a networking API:\n  " +
        offences.distinct().sorted().joinToString("\n  ") +
        "\n\nIf a networked feature is genuinely wanted, it belongs behind the existing opt-in " +
        "ClaudeApiClient path in data/ai/, not inside the reader."
      )
    }
  }

  @Test fun `the permitted networked path still exists and is still the only one`() {
    // Guards the other direction: if ClaudeApiClient is ever moved INTO a reader package, the scan
    // above would start failing for the right reason — but if it is deleted, the exemption in this
    // test becomes a stale comment nobody notices. So the exemption is pinned to a real file.
    val src = File(moduleDir(), "src/main/kotlin/com/wildodds/gymtracker/data/ai/ClaudeApiClient.kt")
    assertTrue(
      "ClaudeApiClient.kt has moved or gone. Update this test and the reader docs together.",
      src.isFile
    )
  }

  // ── locating the compiled output ─────────────────────────────────────────────

  private fun moduleDir(): File {
    // Gradle runs unit tests with the module directory as the working directory, but Robolectric and
    // IDE runners have both been known to use the root. Handle both rather than assume.
    val cwd = File(".").absoluteFile.normalize()
    return if (File(cwd, "src/main/kotlin").isDirectory) cwd else File(cwd, "app")
  }

  private fun candidateRoots(): List<File> {
    val module = moduleDir()
    val kotlinClasses = File(module, "build/tmp/kotlin-classes")
    // PRODUCTION variants only. This test file lives in a reader package and necessarily contains
    // every forbidden string as a literal, so scanning the unit-test output would fail on itself
    // and prove nothing about the app.
    val variants = kotlinClasses.listFiles()
      ?.filter { it.isDirectory && !it.name.contains("Test", ignoreCase = true) }
      .orEmpty()
    return variants + listOf(File(module, "build/intermediates/javac"))
  }

  private fun classRoots(): List<File> = candidateRoots().filter { root ->
    root.isDirectory && readerPackages.any { File(root, it).isDirectory }
  }
}

/** Naive but sufficient: constant-pool strings are stored as plain UTF-8 runs. */
private fun ByteArray.contains(needle: ByteArray): Boolean {
  if (needle.isEmpty() || needle.size > size) return false
  outer@ for (i in 0..size - needle.size) {
    for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
    return true
  }
  return false
}
