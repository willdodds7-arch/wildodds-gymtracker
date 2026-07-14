package com.wildodds.gymtracker.ui.legal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.ui.settings.SettingControl
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 6 gate: every bundled legal doc renders OFFLINE (loads from assets + parses to real
 * content) and is reachable via Settings search. Also guards Rule 8 (honesty): the docs must not
 * claim the app doesn't collect data / is fully offline / doesn't sync.
 */
@RunWith(RobolectricTestRunner::class)
class LegalDocsAssetTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun read(doc: LegalDoc): String =
    context.assets.open(doc.asset).bufferedReader().use { it.readText() }

  @Test
  fun everyBundledDocLoadsAndParsesToContent() {
    LegalDoc.entries.forEach { doc ->
      val text = read(doc)
      assertTrue("${doc.asset} should be non-trivial", text.length > 100)
      val blocks = Markdown.parse(text)
      assertTrue("${doc.asset} should parse to blocks", blocks.isNotEmpty())
      // First block is the document's H1 title.
      val h1 = blocks.filterIsInstance<Markdown.Block.Heading>().firstOrNull { it.level == 1 }
      assertTrue("${doc.asset} needs an H1", h1 != null)
    }
  }

  @Test
  fun everyLegalDocIsReachableViaSettingsSearch() {
    LegalDoc.entries.forEach { doc ->
      // A term from each doc's title finds its Settings row.
      val term = doc.title.substringBefore(" ").lowercase()
      val hits = SettingsRegistry.search(term)
      assertTrue(
        "expected a Legal & privacy row for '${doc.title}' searching '$term'",
        hits.any { it.control == SettingControl.LEGAL_DOC && it.key == doc.key }
      )
    }
  }

  @Test
  fun settingsRegistryHasALegalRowForEveryDoc() {
    val legalKeys = SettingsRegistry.entries
      .filter { it.control == SettingControl.LEGAL_DOC }.map { it.key }.toSet()
    assertEquals(LegalDoc.entries.map { it.key }.toSet(), legalKeys)
  }

  @Test
  fun docsDoNotMakeDishonestNoCollectionClaims() {
    // Rule 8: no doc may claim no data collection / fully offline / no tracking.
    val banned = listOf("no data collection", "fully offline", "no tracking", "does not collect")
    LegalDoc.entries.forEach { doc ->
      val text = read(doc).lowercase()
      banned.forEach { phrase ->
        assertFalse("${doc.asset} must not claim '$phrase'", text.contains(phrase))
      }
    }
  }

  @Test
  fun privacyPolicyDescribesTheRealPipeline() {
    val privacy = read(LegalDoc.PRIVACY).lowercase()
    assertTrue("names the processor", privacy.contains("supabase"))
    assertTrue("states retention", privacy.contains("18 months"))
    assertTrue("covers deletion", privacy.contains("delete"))
    assertTrue("covers export", privacy.contains("export"))
    assertTrue("13+ statement", privacy.contains("13"))
  }
}
