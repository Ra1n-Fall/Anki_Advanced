package com.example.anki_advanced.completion

import com.example.anki_advanced.CardEntity
import com.example.anki_advanced.CARD_REVIEW
import com.example.anki_advanced.sm2Q
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

// ── 상수 ──────────────────────────────────────────────────────────────────────

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

/** LEARNING 기본 스텝 (ms) */
private val LEARNING_STEPS_BASE = listOf(
    1L  * 60L * 1000L,   // 1분
    10L * 60L * 1000L    // 10분
)

/** LEARNING 스텝 압축 가속도 (spec §7) */
private const val STEP_ACCELERATOR = 0.5

/** compressionRatio 허용 범위 */
private const val RATIO_MIN = 0.01
private const val RATIO_MAX = 10.0

/** 모드 반영 파라미터 (spec §11) */
private const val ALPHA = 0.5
private const val BETA  = 0.2

// ── 결과 타입 ────────────────────────────────────────────────────────────────

data class CompletionSm2Result(
    val repetition: Int,
    val baseInterval: Long,   // ms (jitter 적용, 압축 전)
    val easeFactor: Double,
    val nextReviewAt: Long    // addWindowTime(now, baseInterval × ratio, window)
)

enum class CardPriority { URGENT, HIGH, NORMAL }

data class DueCardInfo(val card: CardEntity, val priority: CardPriority)

data class SessionInfo(
    val cardsPerSession: Int,
    val requiredSessions: Int
)

// ── compressionRatio ─────────────────────────────────────────────────────────

/**
 * 목표 기간 / 현재 최대 간격 비율 계산 (spec §6)
 *
 * @param targetPeriodMs      사용자가 설정한 총 학습 기간 (ms)
 * @param currentMaxBaseInterval 덱 내 REVIEW 카드의 최대 baseInterval (ms). 0이면 ratio=1.0
 * @param window              허용 윈도우
 */
fun calculateCompressionRatio(
    targetPeriodMs: Long,
    currentMaxBaseInterval: Long,
    window: AllowedWindow
): Double {
    // REVIEW 카드가 없으면 비교 기준이 없으므로 압축 없음
    if (currentMaxBaseInterval <= 0L) return 1.0

    // 절대 시간이 아닌 윈도우 유효 시간 기준으로 비교한다.
    // 예) 목표 7일, 윈도우 09~22시(13h/일) → targetWindowTime = 7 × 13h = 91h
    val targetWindowTime = calculateWindowTime(0L, targetPeriodMs, window)
    // 덱에서 가장 긴 baseInterval 카드의 윈도우 유효 시간 = "자연 속도로 학습하면 걸리는 시간"
    val maxWindowTime    = calculateWindowTime(0L, currentMaxBaseInterval, window)

    // 윈도우 내 유효 시간이 0이면 (윈도우 설정 오류 등) 압축 없음
    if (maxWindowTime <= 0L) return 1.0

    // ratio = 목표 윈도우 시간 / 자연 학습 윈도우 시간
    // ratio < 1.0 → 목표 기간이 자연 학습보다 짧음 → 간격 압축(더 자주 복습)
    // ratio > 1.0 → 목표 기간이 더 길음 → 간격 확장(덜 자주 복습)
    // RATIO_MIN(0.01)~RATIO_MAX(10.0) 범위로 클램핑해 극단값 방지
    return (targetWindowTime.toDouble() / maxWindowTime).coerceIn(RATIO_MIN, RATIO_MAX)
}

// ── LEARNING 스텝 압축 ───────────────────────────────────────────────────────

/**
 * compressionRatio를 반영한 LEARNING 스텝 목록 반환 (spec §7.2)
 *
 * compressed = step × ratio × ACCELERATOR
 * 최솟값: step[0] → 10초, step[1] → 1분
 */
fun getCompressedSteps(compressionRatio: Double): List<Long> {
    val minValues = listOf(10_000L, 60_000L)
    return LEARNING_STEPS_BASE.mapIndexed { i, step ->
        val compressed = (step * compressionRatio * STEP_ACCELERATOR).toLong()
        compressed.coerceAtLeast(minValues[i])
    }
}

// ── SM-2 (ms 기반) ───────────────────────────────────────────────────────────

/**
 * SM-2 계산 (spec §5) — baseInterval은 ms 단위, jitter 1회 적용
 *
 * @param card              현재 카드
 * @param score             채점 (0=Again, 1=Hard, 2=Good, 3=Easy)
 * @param compressionRatio  현재 유효 압축 비율
 * @param window            허용 윈도우
 * @param now               현재 시각 (ms)
 */
fun applySm2(
    card: CardEntity,
    score: Int,
    compressionRatio: Double,
    window: AllowedWindow,
    now: Long
): CompletionSm2Result {
    val q  = sm2Q(score)
    var ef = card.easeFactor
    var rep = card.repetition
    // baseInterval이 이미 있으면 ms → days 역산, 없으면 intervalDays 사용
    var intervalDays = if (card.baseInterval > 0L) {
        (card.baseInterval / MS_PER_DAY).toInt().coerceAtLeast(1)
    } else {
        card.intervalDays.coerceAtLeast(1)
    }

    when (q) {
        0 -> { // Again
            rep = 0
            intervalDays = 1
            ef = (ef - 0.20).coerceAtLeast(1.3)
        }
        3 -> { // Hard
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> (intervalDays * 1.2).toInt().coerceAtLeast(1)
            }
            ef = (ef - 0.15).coerceAtLeast(1.3)
        }
        4 -> { // Good
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> kotlin.math.round(intervalDays * ef).toInt().coerceAtLeast(1)
            }
        }
        else -> { // Easy
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> kotlin.math.round(intervalDays * ef * 1.3).toInt().coerceAtLeast(1)
            }
            ef = (ef + 0.15).coerceAtLeast(1.3)
        }
    }

    // baseInterval (ms) — jitter 1회만 적용 (spec §5.2)
    val baseInterval = (intervalDays * MS_PER_DAY * Random.nextDouble(0.9, 1.1)).toLong()

    val effectiveInterval = (baseInterval * compressionRatio).toLong()
    val nextReviewAt = addWindowTime(now, effectiveInterval, window)

    return CompletionSm2Result(
        repetition   = rep,
        baseInterval = baseInterval,
        easeFactor   = ef,
        nextReviewAt = nextReviewAt
    )
}

// ── progress 계산 ─────────────────────────────────────────────────────────────

/**
 * 카드의 현재 진행도 계산 (spec §8.1)
 *
 * progress = windowTime(lastReviewAt, now) / windowTime(lastReviewAt, lastReviewAt + baseInterval)
 * clamp: 0.0 ~ 1.5
 */
fun calculateProgress(card: CardEntity, now: Long, window: AllowedWindow): Double {
    if (card.baseInterval <= 0L || card.lastReviewAt <= 0L) return 0.0

    val elapsed = calculateWindowTime(card.lastReviewAt, now, window).toDouble()
    val total   = calculateWindowTime(
        card.lastReviewAt,
        card.lastReviewAt + card.baseInterval,
        window
    ).toDouble()

    if (total <= 0.0) return 0.0
    return (elapsed / total).coerceIn(0.0, 1.5)
}

// ── 모드 전환 — nextReviewAt 재계산 ─────────────────────────────────────────

/**
 * 윈도우 또는 압축 비율 변경 시 카드의 nextReviewAt을 재계산 (spec §8.2)
 *
 * 1. 구 윈도우 기준 progress 계산
 * 2. 새 effectiveInterval = baseInterval × newCompressionRatio
 * 3. 새 윈도우 기준 totalWindowTime × progress → elapsedNewWindowTime
 * 4. nextReviewAt = addWindowTime(lastReviewAt, elapsedNewWindowTime, newWindow)
 *
 * progress >= 1.0이면 즉시 due (nextReviewAt = now)
 */
fun recalculateNextReviewAt(
    card: CardEntity,
    oldWindow: AllowedWindow,
    newWindow: AllowedWindow,
    newCompressionRatio: Double,
    now: Long
): Long {
    val progress = calculateProgress(card, now, oldWindow)

    if (progress >= 1.0) return now

    val newEffectiveInterval = (card.baseInterval * newCompressionRatio).toLong()
    val newTotalWindowTime   = calculateWindowTime(
        card.lastReviewAt,
        card.lastReviewAt + newEffectiveInterval,
        newWindow
    )
    val elapsedNewWindowTime = (newTotalWindowTime * progress).toLong()

    return addWindowTime(card.lastReviewAt, elapsedNewWindowTime, newWindow)
}

// ── 카드 분류 ────────────────────────────────────────────────────────────────

/**
 * DUE 카드의 실질적 지연을 계산해 우선순위 부여 (spec §9.2)
 *
 * realDelay  = calculateWindowTime(nextReviewAt, now, window)
 * delayRatio = realDelay / baseInterval
 *
 * URGENT : delayRatio > 1.0
 * HIGH   : delayRatio > 0.5
 * NORMAL : 그 외
 */
fun classifyDueCard(card: CardEntity, now: Long, window: AllowedWindow): DueCardInfo {
    val realDelay  = calculateWindowTime(card.nextReviewAt, now, window)
    val baseInterval = card.baseInterval.takeIf { it > 0L } ?: MS_PER_DAY
    val delayRatio = realDelay.toDouble() / baseInterval

    val priority = when {
        delayRatio > 1.0 -> CardPriority.URGENT
        delayRatio > 0.5 -> CardPriority.HIGH
        else             -> CardPriority.NORMAL
    }
    return DueCardInfo(card, priority)
}

// ── 세션 계산 ────────────────────────────────────────────────────────────────

/**
 * 현재 시점 기준으로 세션당 학습 카드 수 계산 (spec §10)
 *
 * remainingTime   = calculateWindowTime(now, modeEndMs, window)
 * requiredSessions = ceil(remainingTime / sessionIntervalMs)
 * missedSessions  = floor((now - lastSessionTime) / sessionIntervalMs)  (> 0일 때만)
 * cardsPerSession = ceil(remainingCards / requiredSessions)
 */
fun calculateSession(
    remainingCards: Int,
    now: Long,
    modeEndMs: Long,
    sessionIntervalMs: Long,
    lastSessionTime: Long,
    window: AllowedWindow
): SessionInfo {
    if (remainingCards <= 0) return SessionInfo(0, 0)

    val remainingWindowTime = calculateWindowTime(now, modeEndMs, window)
    if (remainingWindowTime <= 0L) return SessionInfo(remainingCards, 1)

    var requiredSessions = ceil(remainingWindowTime.toDouble() / sessionIntervalMs).toInt()
        .coerceAtLeast(1)

    // 세션 누락 처리 (spec §10.3)
    if (lastSessionTime > 0L && now > lastSessionTime) {
        val missed = floor((now - lastSessionTime).toDouble() / sessionIntervalMs).toInt()
        if (missed > 0) {
            requiredSessions = (requiredSessions - missed).coerceAtLeast(1)
        }
    }

    val cardsPerSession = ceil(remainingCards.toDouble() / requiredSessions).toInt()
    return SessionInfo(cardsPerSession, requiredSessions)
}

// ── 모드 종료 반영 ───────────────────────────────────────────────────────────

/**
 * 기간 모드 종료 후 baseInterval을 학습 밀집도와 정확도로 보정 (spec §11)
 *
 * density     = actualReviews / expectedReviews
 * decayFactor = 1 / (1 + α × (density - 1))
 * bonus       = baseInterval × β × accuracy × ln(1 + modeReviewCount)
 * final       = max(baseInterval / 2, baseInterval × decayFactor + bonus)
 *
 * @return 새 nextReviewAt (now + finalInterval)
 */
fun applyModeReflection(
    card: CardEntity,
    modeReviewCount: Int,
    modeCorrectCount: Int,
    modeDurationMs: Long,
    now: Long
): Long {
    if (modeReviewCount <= 0 || card.baseInterval <= 0L) return now

    val baseInterval  = card.baseInterval.toDouble()
    val modeDurationDays = modeDurationMs.toDouble() / MS_PER_DAY
    val baseIntervalDays = baseInterval / MS_PER_DAY

    val expectedReviews = if (baseIntervalDays > 0) modeDurationDays / baseIntervalDays else 1.0
    val density         = modeReviewCount.toDouble() / expectedReviews.coerceAtLeast(0.001)
    val decayFactor     = 1.0 / (1.0 + ALPHA * (density - 1.0))

    val accuracy = modeCorrectCount.toDouble() / modeReviewCount
    val bonus    = baseInterval * BETA * accuracy * ln(1.0 + modeReviewCount)

    val finalInterval = (baseInterval * decayFactor + bonus)
        .coerceAtLeast(baseInterval / 2.0)
        .toLong()

    return now + finalInterval
}
