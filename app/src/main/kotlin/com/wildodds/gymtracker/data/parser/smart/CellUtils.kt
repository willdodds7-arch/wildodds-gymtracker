package com.wildodds.gymtracker.data.parser.smart

// ─── Cell address helpers ─────────────────────────────────────────────────────

object CellUtils {

  /** "B3" → 1 (0-based column index) */
  fun colIndex(addr: String): Int {
  val col = addr.takeWhile { it.isLetter() }.uppercase()
  return col.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
  }

  /** "B3" → 2 (0-based row index) */
  fun rowIndex(addr: String): Int =
  addr.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: -1

  /** col=1, row=2 → "B3" */
  fun addr(col: Int, row: Int): String = "${colLetter(col)}${row + 1}"

  fun colLetter(index: Int): String {
  var result = ""
  var n = index + 1
  while (n > 0) {
  n--
  result = ('A' + n % 26).toString() + result
  n /= 26
  }
  return result
  }
}

// ─── 2-D grid view over a raw cells map ──────────────────────────────────────

class SheetGrid(val cells: Map<String, String>) {

  val maxRow: Int = cells.keys.maxOfOrNull { CellUtils.rowIndex(it) } ?: -1
  val maxCol: Int = cells.keys.maxOfOrNull { CellUtils.colIndex(it) } ?: -1

  operator fun get(row: Int, col: Int): String? =
  cells[CellUtils.addr(col, row)]?.takeIf { it.isNotBlank() }

  fun row(r: Int): List<String?> = (0..maxCol).map { get(r, it) }
  fun col(c: Int): List<String?> = (0..maxRow).map { get(it, c) }

  /** Non-blank cells in a row */
  fun rowValues(r: Int): List<String> = row(r).filterNotNull()

  /** All non-blank cell values (for scanning) */
  fun allValues(): Sequence<Pair<String, String>> =
  cells.asSequence().filter { it.value.isNotBlank() }.map { it.key to it.value }
}
