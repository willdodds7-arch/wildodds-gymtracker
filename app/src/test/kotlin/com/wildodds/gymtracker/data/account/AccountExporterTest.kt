package com.wildodds.gymtracker.data.account

import com.google.gson.Gson
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.sync.TrainingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** "Export validates" — the produced zip contains a single well-formed JSON entry that round-trips
 *  and carries the account + full training snapshot. */
class AccountExporterTest {

  private fun sampleExport() = AccountExport(
    exportedAt = 1_700_000_000_000,
    account = AccountExport.Account(userId = "u-1", email = "a@example.com", username = "willo"),
    training = TrainingSnapshot(
      exportedAt = 1_700_000_000_000,
      programs = listOf(Program(id = 1, name = "Wild Odds Split", totalWeeks = 4))
    ),
    analyticsNote = AccountExport.AnalyticsNote(analyticsConsent = "granted")
  )

  @Test
  fun zipContainsExactlyOneJsonEntry_thatParses() {
    val bytes = AccountExporter.toZipBytes(sampleExport())
    val entries = mutableMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
      var e = zin.nextEntry
      while (e != null) { entries[e.name] = zin.readBytes().toString(Charsets.UTF_8); e = zin.nextEntry }
    }
    assertEquals(setOf(AccountExporter.JSON_ENTRY_NAME), entries.keys)

    val parsed = Gson().fromJson(entries.values.single(), Map::class.java)
    assertEquals("wildodds-account-export/v1", parsed["schema"])
    assertNotNull("export must include account block", parsed["account"])
    assertNotNull("export must include training block", parsed["training"])
  }

  @Test
  fun exportIncludesAccountAndTrainingData() {
    val json = AccountExporter.toJson(sampleExport())
    assertTrue(json.contains("a@example.com"))
    assertTrue(json.contains("willo"))
    assertTrue("full training snapshot is included", json.contains("Wild Odds Split"))
    assertTrue("consent state is recorded", json.contains("granted"))
  }
}
