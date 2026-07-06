package com.wildodds.gymtracker.data.parser

import android.content.Context

/**
 * Process-wide cache for the bundled block catalogue. The asset is a few MB of JSON, and both the
 * Home and Library view-models want the same list, so it is parsed once and reused.
 */
object DefaultProgramCatalogue {

  @Volatile
  private var cache: List<ParsedProgram>? = null

  /** Parsed catalogue blocks, loaded on first call and cached thereafter. */
  fun load(context: Context): List<ParsedProgram> {
    cache?.let { return it }
    return synchronized(this) {
      cache ?: DefaultProgramJsonParser(context.applicationContext).parse().also { cache = it }
    }
  }
}
