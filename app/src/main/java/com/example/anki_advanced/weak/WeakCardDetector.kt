package com.example.anki_advanced.weak

import com.example.anki_advanced.ReviewScoreRow
import kotlin.math.min
import kotlin.math.pow

// 약점 카드 자동 감지.
// 카드별 채점 기록(review_logs)만 보고 0~1 사이의 "약점 점수"를 매긴다. AI/모델 없이 규칙만 쓴다.
//
// ① lapseScore     = min(lapse, 8) / 8                       (외웠다가 다시 틀린 횟수)
// ② recentFailRate = Σ w(i)·f(i) / Σ w(i)                    (최근 8회 실패율, 최신일수록 가중치 큼)
//      f: Again 1 · Hard 0.5 · Good/Easy 0,  w(i) = 0.8^i  (i=0이 가장 최근)
// weakScore        = 0.5 × ① + 0.5 × ②
//
// 판정 (위에서부터 차례로):
//   채점 4회 미만   → 판정 안 함 (기록이 너무 적음)
//   lapse 8회 이상  → 약점 (리치)
//   weakScore ≥ 0.6 → 약점
//   그 외           → 정상

const val WEAK_MIN_REVIEWS    = 4     // 최소 표본: 이보다 적게 채점한 카드는 판정하지 않음
const val WEAK_LAPSE_CAP      = 8     // lapseScore가 1이 되는 횟수 = 리치 기준
const val WEAK_RECENT_N       = 8     // 최근 몇 회의 채점을 볼지
const val WEAK_DECAY          = 0.8   // 한 회 오래될 때마다 가중치에 곱해지는 비율
const val WEAK_W_LAPSE        = 0.5   // 최종 점수에서 ①의 비중
const val WEAK_W_RECENT       = 0.5   // 최종 점수에서 ②의 비중
const val WEAK_THRESHOLD      = 0.6   // 이 점수 이상이면 약점

// 카드 한 장의 채점 통계. recentScores는 최신순(0번이 가장 최근)이며 최대 WEAK_RECENT_N개.
data class CardReviewStats(
    val cardId: Long,
    val reviewCount: Int,
    val lapseCount: Int,
    val recentScores: List<Int>
)

// 채점 점수(0=Again, 1=Hard, 2=Good, 3=Easy)를 실패 값으로 변환.
private fun failValue(score: Int): Double = when (score) {
    0    -> 1.0
    1    -> 0.5
    else -> 0.0
}

fun lapseScore(lapseCount: Int): Double =
    min(lapseCount, WEAK_LAPSE_CAP).toDouble() / WEAK_LAPSE_CAP

fun recentFailRate(recentScores: List<Int>): Double {
    if (recentScores.isEmpty()) return 0.0
    var weighted = 0.0
    var weightSum = 0.0
    recentScores.take(WEAK_RECENT_N).forEachIndexed { i, score ->
        val w = WEAK_DECAY.pow(i)
        weighted += w * failValue(score)
        weightSum += w
    }
    return weighted / weightSum
}

fun weakScore(stats: CardReviewStats): Double =
    WEAK_W_LAPSE * lapseScore(stats.lapseCount) + WEAK_W_RECENT * recentFailRate(stats.recentScores)

fun isWeakCard(stats: CardReviewStats): Boolean = when {
    stats.reviewCount < WEAK_MIN_REVIEWS  -> false
    stats.lapseCount >= WEAK_LAPSE_CAP    -> true
    else                                  -> weakScore(stats) >= WEAK_THRESHOLD
}

// getScoreHistory()의 결과(카드별 → 최신순 정렬)를 카드 한 장씩의 통계로 묶는다.
// lapse = 복습 단계(isNewAtReview = 0)에서 Again(score = 0)을 누른 횟수.
fun buildCardStats(rows: List<ReviewScoreRow>): List<CardReviewStats> =
    rows.groupBy { it.cardId }.map { (cardId, logs) ->
        CardReviewStats(
            cardId       = cardId,
            reviewCount  = logs.size,
            lapseCount   = logs.count { it.isNewAtReview == 0 && it.score == 0 },
            recentScores = logs.take(WEAK_RECENT_N).map { it.score }
        )
    }

// 카드 한 장의 채점 기록(최신순)만으로 약점인지 판정. 기록이 없으면 false.
fun isWeakCard(cardRows: List<ReviewScoreRow>): Boolean =
    buildCardStats(cardRows).singleOrNull()?.let { isWeakCard(it) } ?: false

// 약점 카드 id 목록. 점수가 높은(더 약한) 카드부터 정렬한다.
fun findWeakCardIds(rows: List<ReviewScoreRow>): List<Long> =
    buildCardStats(rows)
        .filter { isWeakCard(it) }
        .sortedByDescending { weakScore(it) }
        .map { it.cardId }
