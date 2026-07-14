@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.legal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/** Renders a bundled legal document from assets — read once, offline, never fetched. */
@Composable
fun LegalDocScreen(navController: NavController, doc: LegalDoc) {
  val context = LocalContext.current
  val markdown by produceState(initialValue = null as String?, doc) {
    value = runCatching {
      context.assets.open(doc.asset).bufferedReader().use { it.readText() }
    }.getOrElse { "# ${doc.title}\n\nThis document could not be loaded." }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text(doc.title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    val md = markdown
    if (md == null) {
      Box(Modifier.fillMaxSize().padding(padding))
    } else {
      val blocks = Markdown.parse(md)
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("legal_doc_${doc.key}"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
      ) {
        items(blocks) { block -> MarkdownBlock(block) }
      }
    }
  }
}
