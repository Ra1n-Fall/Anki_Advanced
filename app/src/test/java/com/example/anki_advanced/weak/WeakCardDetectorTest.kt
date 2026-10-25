package com.example.anki_advanced.weak

import com.example.anki_advanced.ReviewScoreRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakCardDetectorTest {

    // 최신순 점수 목록으로 카드 한 장의 로그를 만든다. newFlags는 같은 순서의 isNewAtReview 값.
    private fun rows(cardId: Long, scores: List<Int>, newFlags: List<Int> = scores.map { 0 }) =
        scores.zip(newFlags).map { (s, n) -> ReviewScoreRow(cardId, s, n) }

    @Test
    fun lapseScore_capsAtEight() {
        assertEquals(0.0, lapseScore(0), 1e-9)
        assertEquals(0.5, lapseScore(4), 1e-9)
        assertEquals(1.0, lapseScore(12), 1e-9)
    }

    @Test
    fun recentFailRate_weightsRecentMore() {
        assertEquals(1.0, recentFailRate(listOf(0, 0, 0)), 1e-9)
        assertEquals(0.0, recentFailRate(listOf(2, 3, 2)), 1e-9)
        // 최근에 틀린 쪽이 예전에 틀린 쪽보다 점수가 높아야 한다
        assertTrue(recentFailRate(listOf(0, 2, 2)) > recentFailRate(listOf(2, 2, 0)))
    }

    @Test
    fun slideExample_isWeak() {
        // 다시 틀린 5회, 최근 8회 A A G A H A G A → weakScore ≈ 0.68
        val stats = CardReviewStats(1, reviewCount = 12, lapseCount = 5, recentScores = listOf(0, 0, 2, 0, 1, 0, 2, 0))
        assertEquals(0.68, weakScore(stats), 0.01)
        assertTrue(isWeakCard(stats))
    }

    @Test
    fun failuresOnlyWhileNew_areNotWeak() {
        // 처음 배울 때 3번 틀리고(isNewAtReview=1) 그 뒤로 계속 맞힘
        val r = rows(1, listOf(2, 2, 2, 2, 0, 0, 0), listOf(0, 0, 0, 0, 1, 1, 1))
        assertEquals(emptyList<Long>(), findWeakCardIds(r))
    }

    @Test
    fun tooFewReviews_notJudged() {
        val stats = buildCardStats(rows(1, listOf(0, 0))).single()
        assertFalse(isWeakCard(stats))
    }

    @Test
    fun leech_isWeakEvenWhenRecentlyCorrect() {
        // 최근 8회는 전부 Good이지만 그 이전에 복습 단계에서 8번 틀림
        val r = rows(1, List(8) { 2 } + List(8) { 0 })
        val stats = buildCardStats(r).single()
        assertEquals(8, stats.lapseCount)
        assertTrue(isWeakCard(stats))
    }

    @Test
    fun findWeakCardIds_sortsWeakestFirst() {
        val r = rows(1, listOf(0, 0, 2, 0, 1, 0, 2, 0)) + rows(2, List(8) { 0 }) + rows(3, List(8) { 2 })
        assertEquals(listOf(2L, 1L), findWeakCardIds(r))
    }
}
