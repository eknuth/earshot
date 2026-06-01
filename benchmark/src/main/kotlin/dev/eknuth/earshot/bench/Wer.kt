package dev.eknuth.earshot.bench

/**
 * Word error rate, the standard ASR accuracy metric: the edit distance between the reference
 * and hypothesis word sequences, divided by the number of reference words.
 *
 * Both sides are normalized the same way before scoring so casing and punctuation do not count
 * as errors. The reference clips (LibriSpeech) are already upper-case with no punctuation;
 * Whisper output carries both, so normalization is what makes the comparison fair rather than
 * a casing contest.
 */
data class WerResult(
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val referenceWords: Int,
) {
    val errors: Int get() = substitutions + deletions + insertions

    /** Errors per reference word. Can exceed 1.0 when the hypothesis inserts many spurious words. */
    val rate: Double get() = if (referenceWords == 0) {
        if (insertions == 0) 0.0 else 1.0
    } else {
        errors.toDouble() / referenceWords
    }
}

/** Lower-case, drop everything that is not a letter, digit or apostrophe, collapse whitespace. */
fun normalizeForWer(text: String): List<String> =
    text.lowercase()
        .map { c -> if (c.isLetterOrDigit() || c == '\'') c else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }

/**
 * Word-level Levenshtein with unit costs, the textbook WER alignment. Runs in O(R x H) time and
 * O(H) space, tracking how each edit decomposes into substitutions, deletions and insertions so
 * the breakdown can be reported, not just the rate.
 */
fun wer(reference: String, hypothesis: String): WerResult {
    val ref = normalizeForWer(reference)
    val hyp = normalizeForWer(hypothesis)

    // Each cell carries the running (cost, subs, dels, ins) for aligning ref[0..i) with hyp[0..j).
    data class Cell(val cost: Int, val sub: Int, val del: Int, val ins: Int)

    var prev = Array(hyp.size + 1) { j -> Cell(j, 0, 0, j) } // empty reference: all insertions
    for (i in 1..ref.size) {
        val curr = arrayOfNulls<Cell>(hyp.size + 1)
        curr[0] = Cell(i, 0, i, 0) // empty hypothesis: all deletions
        for (j in 1..hyp.size) {
            val match = ref[i - 1] == hyp[j - 1]
            val subCell = prev[j - 1]!!.let {
                Cell(it.cost + if (match) 0 else 1, it.sub + if (match) 0 else 1, it.del, it.ins)
            }
            val delCell = prev[j]!!.let { Cell(it.cost + 1, it.sub, it.del + 1, it.ins) }
            val insCell = curr[j - 1]!!.let { Cell(it.cost + 1, it.sub, it.del, it.ins + 1) }
            curr[j] = minOf(subCell, delCell, insCell, compareBy { it.cost })
        }
        @Suppress("UNCHECKED_CAST")
        prev = curr as Array<Cell>
    }
    val end = prev[hyp.size]!!
    return WerResult(end.sub, end.del, end.ins, ref.size)
}

/**
 * Corpus word error rate: pool every clip's edits and reference words, then divide once. This is
 * the correct aggregate. Averaging per-clip rates would over-weight short clips, where a single
 * wrong word is a large percentage.
 */
fun aggregateWer(results: List<WerResult>): WerResult = WerResult(
    substitutions = results.sumOf { it.substitutions },
    deletions = results.sumOf { it.deletions },
    insertions = results.sumOf { it.insertions },
    referenceWords = results.sumOf { it.referenceWords },
)
