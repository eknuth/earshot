package dev.eknuth.earshot.bench

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WerTest {

    @Test
    fun `identical strings score zero`() {
        val r = wer("the quick brown fox", "the quick brown fox")
        assertEquals(0, r.errors)
        assertEquals(0.0, r.rate)
    }

    @Test
    fun `casing and punctuation are normalized away`() {
        val r = wer("THE QUICK BROWN FOX", "The quick, brown fox.")
        assertEquals(0, r.errors)
    }

    @Test
    fun `one substitution over four words is 25 percent`() {
        val r = wer("the quick brown fox", "the quick green fox")
        assertEquals(1, r.substitutions)
        assertEquals(0.25, r.rate)
    }

    @Test
    fun `a deletion and an insertion are counted distinctly`() {
        // reference has 4 words, hypothesis drops "brown" (deletion)
        val del = wer("the quick brown fox", "the quick fox")
        assertEquals(1, del.deletions)
        assertEquals(0, del.insertions)

        // hypothesis adds "lazy" (insertion)
        val ins = wer("the quick brown fox", "the quick brown lazy fox")
        assertEquals(1, ins.insertions)
        assertEquals(0, ins.deletions)
    }

    @Test
    fun `empty hypothesis is total loss`() {
        val r = wer("the quick brown fox", "")
        assertEquals(4, r.deletions)
        assertEquals(1.0, r.rate)
    }

    @Test
    fun `corpus aggregation pools edits not rates`() {
        // clip A: 1 error / 1 word = 100%. clip B: 1 error / 99 words ~ 1%.
        // averaging rates gives ~50.5%; pooling gives 2/100 = 2%, which is correct.
        val a = wer("alpha", "beta")
        val longRef = (1..99).joinToString(" ") { "word$it" }
        val longHyp = "wrong " + (2..99).joinToString(" ") { "word$it" }
        val b = wer(longRef, longHyp)
        val agg = aggregateWer(listOf(a, b))
        assertEquals(100, agg.referenceWords)
        assertEquals(2, agg.errors)
        assertEquals(2.0, agg.rate * 100)
    }

    @Test
    fun `apostrophes are preserved as part of the word`() {
        val r = wer("dont stop", "don't stop")
        // "dont" vs "don't" is a substitution, not free, because the apostrophe is kept.
        assertEquals(1, r.substitutions)
        assertTrue(r.rate > 0)
    }
}
