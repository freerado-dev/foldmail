package com.foldmail.domain.threading

/** [fresh] is what the sender newly wrote; [quoted] is the collapsed history below it. */
data class SplitBody(val fresh: String, val quoted: String)

/**
 * Separates new text from quoted history so chat bubbles show only the new part
 * and offer the rest behind an expander. Heuristic, so [fresh] falls back to the
 * whole text when nothing precedes the quote marker.
 */
object QuoteSplitter {

    private val markers = listOf(
        Regex("""^\s*-{2,}\s*(Původní e-mail|Původní zpráva|Pôvodná správa|Original Message|Forwarded message)\s*-{2,}\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*On .+ wrote:\s*$"""),
        Regex("""^\s*Dne .+ napsal(a)?.*$"""),
        Regex("""^\s*(Dňa|Dňa) .+ napísal(a)?.*$"""),
        Regex("""^\s*>"""),
    )

    fun split(text: String): SplitBody {
        val lines = text.lines()
        val idx = lines.indexOfFirst { line -> markers.any { it.containsMatchIn(line) } }
        if (idx < 0) return SplitBody(text.trim(), "")
        val fresh = lines.take(idx).joinToString("\n").trim()
        if (fresh.isEmpty()) return SplitBody(text.trim(), "")
        return SplitBody(fresh, lines.drop(idx).joinToString("\n").trim())
    }
}
