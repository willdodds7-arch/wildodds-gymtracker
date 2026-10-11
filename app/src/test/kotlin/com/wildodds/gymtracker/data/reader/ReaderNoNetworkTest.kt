package com.wildodds.gymtracker.data.reader

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * **The app is fully offline, and this test is what keeps it that way.**
 *
 * The constraint is not a preference: import must work in airplane mode, with no account, no key and
 * no server, on first launch. A single well-meaning `URL(...)` added later — to fetch an exercise
 * icon, to look up a name, to report a parse failure — would break that silently, and nobody would
 * notice until a user on a plane found the feature dead.
 *
 * So this reads the actual COMPILED CLASS FILES of the whole app and scans their constant
 * pools for any reference to a networking type. Scanning bytecode rather than source is deliberate:
 * source-level greps miss a network call reached through a helper, an import alias, or a string
 * passed to reflection, and the constant pool records every type a class actually references.
 *
 * There is no networked path at all: the manifest does not request INTERNET (the second test), so
 * even a library that tried would be refused by Android. "No cloud, no tracking" is enforced by the
 * platform, not just promised.
 */
class ReaderNoNetworkTest {

  /** The whole app. */
  private val readerPackages = listOf("com/wildodds/gymtracker")

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
    "java/nio/channels/SocketChannel"
  )

  @Test fun `no class in the app references a networking API`() {
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
        "The app must work fully offline. These classes reference a networking API:\n  " +
        offences.distinct().sorted().joinToString("\n  ") +
        "\n\nThe app is offline by design (no cloud, no tracking). Remove the network use."
      )
    }
  }

  @Test fun `the manifest strips the INTERNET permission`() {
    // Libraries (ML Kit's telemetry transport) ask for INTERNET in their own manifests, so the app's
    // manifest must actively remove it — not merely not ask for it.
    val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
    val internet = Regex("<uses-permission[^>]*android\\.permission\\.INTERNET[^>]*>").findAll(manifest).toList()
    assertTrue(
      "AndroidManifest.xml must contain exactly one INTERNET entry, marked tools:node=\"remove\". " +
        "Without it Android lets libraries reach the network; the app is offline by design.",
      internet.size == 1 && internet.single().value.contains("tools:node=\"remove\"")
    )
  }

  @Test fun `the merged manifest has no INTERNET permission`() {
    // The merged manifest is what actually ships. Only checked when a build has produced one.
    val merged = File(moduleDir(), "build/intermediates/merged_manifest").walkTopDown()
      .filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
    for (m in merged) {
      assertTrue(
        "${m.path} still grants android.permission.INTERNET — a library's request got through.",
        !Regex("<uses-permission[^>]*android\\.permission\\.INTERNET").containsMatchIn(m.readText())
      )
    }
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
