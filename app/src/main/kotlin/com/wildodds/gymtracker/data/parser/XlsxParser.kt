package com.wildodds.gymtracker.data.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

class XlsxParser {

  fun parse(inputStream: InputStream): ParsedProgram {
  val entries = mutableMapOf<String, ByteArray>()
  ZipInputStream(inputStream.buffered()).use { zip ->
  var entry = zip.nextEntry
  while (entry != null) {
  entries[entry.name] = zip.readBytes()
  entry = zip.nextEntry
  }
  }

  val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
  val sheetMap = parseWorkbook(entries["xl/workbook.xml"] ?: error("Missing workbook.xml"))
  val relMap = parseWorkbookRels(entries["xl/_rels/workbook.xml.rels"] ?: byteArrayOf())

  // sheetMap: sheetName -> rId, relMap: rId -> target path
  val sheets = mutableMapOf<String, ByteArray>()
  for ((name, rId) in sheetMap) {
  val target = relMap[rId] ?: continue
  val path = if (target.startsWith("worksheets/")) "xl/$target" else "xl/worksheets/$target"
  val data = entries[path] ?: entries[target] ?: continue
  sheets[name] = data
  }

  var programName = "Unnamed Program"
  var totalWeeks = 1
  var category = ""

  val programInfoData = sheets["Program Info"]
  if (programInfoData != null) {
  val cells = parseSheet(programInfoData, sharedStrings)
  programName = cells["C3"] ?: programName
  totalWeeks = cells["C4"]?.toIntOrNull() ?: totalWeeks
  category = cells["C5"] ?: ""
  }

  val sessionPattern = Regex("^W(\\d+)D(\\d+)$")
  val sessions = mutableListOf<ParsedSession>()

  for ((sheetName, data) in sheets) {
  val match = sessionPattern.matchEntire(sheetName) ?: continue
  val weekNumber = match.groupValues[1].toInt()
  val dayNumber = match.groupValues[2].toInt()

  val cells = parseSheet(data, sharedStrings)

  val sessionName = cells["A1"] ?: "Week $weekNumber Day $dayNumber"
  val muscleGroupsRaw = cells["A2"] ?: ""
  val muscleGroups = muscleGroupsRaw.removePrefix("Muscle Groups: ").trim()

  val exercises = mutableListOf<ParsedExercise>()
  var row = 5
  var orderIndex = 0
  while (true) {
  val nameCell = cells["A$row"]
  if (nameCell.isNullOrBlank() || nameCell == "← add exercise here") break

  val setsStr = cells["B$row"]
  val sets = setsStr?.toIntOrNull() ?: 3
  val repsTarget = cells["C$row"] ?: "8-12"
  val notes = cells["D$row"] ?: ""

  exercises.add(ParsedExercise(nameCell.trim(), sets, repsTarget, notes, orderIndex))
  orderIndex++
  row++
  }

  if (exercises.isNotEmpty()) {
  sessions.add(ParsedSession(weekNumber, dayNumber, sessionName, muscleGroups, exercises))
  }
  }

  return ParsedProgram(programName, totalWeeks, sessions, category = category)
  }

  private fun parseSharedStrings(data: ByteArray?): List<String> {
  if (data == null) return emptyList()
  val strings = mutableListOf<String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var inT = false
  val currentSb = StringBuilder()
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> {
  if (parser.name == "t") { inT = true; currentSb.clear() }
  else if (parser.name == "si") currentSb.clear()
  }
  XmlPullParser.TEXT -> if (inT) currentSb.append(parser.text)
  XmlPullParser.END_TAG -> {
  if (parser.name == "t") inT = false
  else if (parser.name == "si") strings.add(currentSb.toString())
  }
  }
  eventType = parser.next()
  }
  return strings
  }

  private fun parseWorkbook(data: ByteArray): Map<String, String> {
  val map = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
  val name = parser.getAttributeValue(null, "name") ?: ""
  val rId = parser.getAttributeValue(null, "r:id")
  ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
  ?: ""
  if (name.isNotEmpty() && rId.isNotEmpty()) map[name] = rId
  }
  eventType = parser.next()
  }
  return map
  }

  private fun parseWorkbookRels(data: ByteArray): Map<String, String> {
  if (data.isEmpty()) return emptyMap()
  val map = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
  val id = parser.getAttributeValue(null, "Id") ?: ""
  val target = parser.getAttributeValue(null, "Target") ?: ""
  if (id.isNotEmpty()) map[id] = target
  }
  eventType = parser.next()
  }
  return map
  }

  private fun parseSheet(data: ByteArray, sharedStrings: List<String>): Map<String, String> {
  val cells = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")

  var currentCell = ""
  var currentType = ""
  var inV = false
  var inIs = false
  var isText = StringBuilder()
  var eventType = parser.eventType

  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> when (parser.name) {
  "c" -> {
  currentCell = parser.getAttributeValue(null, "r") ?: ""
  currentType = parser.getAttributeValue(null, "t") ?: ""
  inV = false
  }
  "v" -> { inV = true; isText.clear() }
  "is" -> { inIs = true; isText.clear() }
  "t" -> if (inIs) isText.clear()
  }
  XmlPullParser.TEXT -> {
  if (inV || inIs) isText.append(parser.text)
  }
  XmlPullParser.END_TAG -> when (parser.name) {
  "v" -> {
  inV = false
  val raw = isText.toString().trim()
  if (currentCell.isNotEmpty()) {
  cells[currentCell] = when (currentType) {
  "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
  "str", "inlineStr" -> raw
  else -> raw
  }
  }
  }
  "is" -> {
  inIs = false
  if (currentCell.isNotEmpty()) cells[currentCell] = isText.toString().trim()
  }
  }
  }
  eventType = parser.next()
  }
  return cells
  }
}
