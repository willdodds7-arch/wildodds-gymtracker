package com.wildodds.gymtracker.data.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

object AiFileExtractor {

    fun extract(context: Context, uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = getFileName(context, uri) ?: ""
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            when {
                ext == "pdf" || mimeType == "application/pdf" ->
                    extractPdf(context, stream)
                ext == "xlsx" || ext == "xls" ||
                mimeType.contains("spreadsheet") || mimeType.contains("excel") ||
                mimeType.contains("openxmlformats") ->
                    extractXlsx(stream)
                else ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } ?: throw Exception("Could not open file")
    }

    private fun extractPdf(context: Context, stream: java.io.InputStream): String {
        PDFBoxResourceLoader.init(context)
        val doc = PDDocument.load(stream)
        return try {
            PDFTextStripper().getText(doc)
        } finally {
            doc.close()
        }
    }

    private fun extractXlsx(stream: java.io.InputStream): String {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        // Parse shared strings table
        val sharedStrings = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { data ->
            val parser = Xml.newPullParser()
            parser.setInput(data.inputStream(), "UTF-8")
            var inT = false
            var ev = parser.next()
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> inT = parser.name == "t"
                    XmlPullParser.TEXT -> if (inT) sharedStrings.add(parser.text ?: "")
                    XmlPullParser.END_TAG -> if (parser.name == "t") inT = false
                }
                ev = parser.next()
            }
        }

        val sb = StringBuilder()
        entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") }
            .sorted()
            .take(10)
            .forEach { sheetKey ->
                entries[sheetKey]?.let { data ->
                    val parser = Xml.newPullParser()
                    parser.setInput(data.inputStream(), "UTF-8")
                    var inV = false
                    var inIs = false
                    var cellType = ""
                    var ev = parser.next()
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "c" -> { cellType = parser.getAttributeValue(null, "t") ?: "" }
                                "v" -> inV = true
                                "is" -> inIs = true
                                "t" -> if (inIs) inV = true
                            }
                            XmlPullParser.TEXT -> if (inV) {
                                val raw = parser.text ?: ""
                                val value = if (cellType == "s")
                                    sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw }
                                else raw
                                if (value.isNotBlank()) sb.append(value).append('\t')
                            }
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "v", "t" -> inV = false
                                "is" -> inIs = false
                                "row" -> sb.append('\n')
                            }
                        }
                        ev = parser.next()
                    }
                }
                sb.append("\n---\n")
            }
        return sb.toString()
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }
}
