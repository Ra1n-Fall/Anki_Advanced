package com.example.anki_advanced.completion

import com.example.anki_advanced.CardEntity
import com.example.anki_advanced.CARD_REVIEW
import com.example.anki_advanced.sm2Q
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

// 완주 모드(목표 기간 안에 덱을 끝내기)의 핵심 계산 로직을 모아둔 파일.
// Android 클래스에 의존하지 않는 순수 계산 함수들이라 단위 테스트하기 좋다.

// ── 상수 ──────────────────────────────────────────────────────────────────────

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

// LEARNING(학습 중) 단계의 기본 대기 시간. 압축 전 "원래" 값.
private val LEARNING_STEPS_BASE = listOf(
    1L  * 60L * 1000L,   // 1분
    10L * 60L * 1000L    // 10분
)

// LEARNING 스텝을 얼마나 더 빨리 줄일지의 가속 계수.
private const val STEP_ACCELERATOR = 0.5

// compressionRatio(압축 비율) 값이 너무 극단적으로 튀지 않도록 잡아두는 허용 범위.
private const val RATIO_MIN = 0.01
private const val RATIO_MAX = 10.0

// 완주 모드 종료 후 간격을 보정할 때 쓰는 파라미터.
private const val ALPHA = 0.5
private const val BETA  = 0.2

// ── 결과를 담는 타입들 ──────────────────────────────────────────────────────

data class CompletionSm2Result(
    val repetition: Int,
    val baseInterval: Long,   // ms 단위. 무작위 흔들림(jitter)까지 적용된, "압축 적용 전" 순수 간격
    val easeFactor: Double,
    val nextReviewAt: Long    // 실제로 다음에 복습할 절대 시각
)

// [문법] enum class CardPriority { URGENT, HIGH, NORMAL }
//   "이 셋 중 하나만 될 수 있다"를 타입으로 강제하는 열거형. 오타가 나거나
//   범위 밖의 값이 들어올 걱정 없이 when(우선순위) { URGENT -> ... } 처럼 안전하게 분기할 수 있다.
enum class CardPriority { URGENT, HIGH, NORMAL }

data class DueCardInfo(val card: CardEntity, val priority: CardPriority)

data class SessionInfo(
    val cardsPerSession: Int,   // 이번 세션에 볼 카드 수
    val requiredSessions: Int   // 마감까지 몇 번의 세션이 남았는지
)

// ── compressionRatio(압축 비율) 계산 ────────────────────────────────────────

// 완주 모드의 핵심 수치: "원래 SM-2대로면 걸릴 시간" 대비 "사용자가 정한 목표 기간"의 비율.
// 이 비율을 SM-2가 계산한 간격에 곱해서, 간격을 줄이거나(압축) 늘린다(확장).
//
// targetPeriodMs           사용자가 정한 총 학습 기간 (ms)
// currentMaxBaseInterval   덱 안에서 가장 긴 REVIEW 간격 (ms). 0이면 비교 기준이 없다는 뜻
// window                   학습 허용 시간대
fun calculateCompressionRatio(
    targetPeriodMs: Long,
    currentMaxBaseInterval: Long,
    window: AllowedWindow
): Double {
    // REVIEW 카드가 아직 없으면(막 시작한 덱) 비교할 기준이 없으므로 압축하지 않는다.
    if (currentMaxBaseInterval <= 0L) return 1.0

    // 달력상의 절대 시간이 아니라, "허용 시간대 안에서 흐르는 시간" 기준으로 비교한다.
    // 예) 목표 7일, 윈도우가 09~22시(하루 13시간)라면 targetWindowTime = 7 × 13h = 91h
    val targetWindowTime = calculateWindowTime(0L, targetPeriodMs, window)
    // 덱에서 제일 오래 걸리는 카드가 "압축 없이 자연스럽게" 걸리는 윈도우 기준 시간
    val maxWindowTime    = calculateWindowTime(0L, currentMaxBaseInterval, window)

    // 윈도우 유효 시간이 0이면(설정 오류 등) 안전하게 압축 없음으로 처리.
    if (maxWindowTime <= 0L) return 1.0

    // ratio = 목표 기간 / 자연스럽게 걸리는 기간
    //   ratio < 1.0 → 목표가 더 촉박함 → 간격을 압축해서 더 자주 복습
    //   ratio > 1.0 → 목표에 여유가 있음 → 간격을 늘려서 덜 자주 복습
    // [문법] .coerceIn(min, max) → 값이 min보다 작으면 min, max보다 크면 max로 잘라내는 범위 제한.
    //   극단적으로 크거나 작은 비율이 나오는 걸 막는 안전장치.
    return (targetWindowTime.toDouble() / maxWindowTime).coerceIn(RATIO_MIN, RATIO_MAX)
}

// ── LEARNING 스텝 압축 ───────────────────────────────────────────────────────

// compressionRatio를 반영해서 LEARNING 단계의 대기 시간(1분, 10분)을 줄여준다.
// compressed = 원래 스텝 × ratio × 가속계수
// 다만 너무 짧아지지 않도록 최솟값(10초, 1분)은 보장한다.
fun getCompressedSteps(compressionRatio: Double): List<Long> {
    val minValues = listOf(10_000L, 60_000L)
    // [문법] list.mapIndexed { i, step -> ... }
    //   map과 비슷하지만, 각 원소의 "인덱스(순번) i"도 같이 받을 수 있다.
    //   여기서는 i번째 스텝을 i번째 최솟값(minValues[i])과 짝지어 비교해야 해서 필요.
    return LEARNING_STEPS_BASE.mapIndexed { i, step ->
        val compressed = (step * compressionRatio * STEP_ACCELERATOR).toLong()
        // [문법] .coerceAtLeast(x) → 값이 x보다 작으면 x로 올림(하한선 고정)
        compressed.coerceAtLeast(minValues[i])
    }
}

// ── SM-2 알고리즘 (ms 단위로 계산) ───────────────────────────────────────────

// 채점 결과를 받아 SM-2(간격 반복) 알고리즘으로 다음 복습 간격을 계산한다.
// 표준 SM-2와 다른 점: 결과를 "일(day)"이 아니라 "밀리초(ms)"로 다루고,
// 매번 약간의 무작위성(jitter)을 섞어 같은 날 등록한 카드들이 전부 같은 날 몰리는 걸 방지한다.
//
// card                현재 카드
// score               채점 버튼 (0=Again, 1=Hard, 2=Good, 3=Easy)
// compressionRatio    지금 적용 중인 압축 비율
// window              허용 윈도우
// now                 현재 시각 (ms)
fun applySm2(
    card: CardEntity,
    score: Int,
    compressionRatio: Double,
    window: AllowedWindow,
    now: Long
): CompletionSm2Result {
    val q  = sm2Q(score)  // 채점 버튼(0~3)을 SM-2가 쓰는 품질 점수(q)로 변환
    var ef = card.easeFactor
    var rep = card.repetition
    // baseInterval(ms)이 이미 기록돼 있으면 그걸 일(day) 단위로 역산해서 쓰고,
    // 없으면(완주 모드로 처음 전환된 카드 등) 기존 intervalDays를 그대로 쓴다.
    var intervalDays = if (card.baseInterval > 0L) {
        (card.baseInterval / MS_PER_DAY).toInt().coerceAtLeast(1)
    } else {
        card.intervalDays.coerceAtLeast(1)
    }

    // [문법] when (q) { 0 -> {...} 3 -> {...} 4 -> {...} else -> {...} }
    //   자바의 switch와 비슷하지만 더 강력한 분기문. 각 case에서 여러 줄({ })을 실행할 수 있고,
    //   else는 "나머지 모든 경우"를 담당한다(여기서는 q=5, 즉 Easy).
    when (q) {
        0 -> { // Again: 완전히 틀림 → 처음부터 다시
            rep = 0
            intervalDays = 1
            ef = (ef - 0.20).coerceAtLeast(1.3)  // 난이도 계수를 낮추되 1.3 밑으로는 안 내려가게
        }
        3 -> { // Hard: 겨우 맞춤
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> (intervalDays * 1.2).toInt().coerceAtLeast(1)
            }
            ef = (ef - 0.15).coerceAtLeast(1.3)
        }
        4 -> { // Good: 무난하게 맞춤
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> kotlin.math.round(intervalDays * ef).toInt().coerceAtLeast(1)
            }
        }
        else -> { // Easy: 아주 쉬웠음
            rep += 1
            intervalDays = when (rep) {
                1    -> 1
                2    -> 6
                else -> kotlin.math.round(intervalDays * ef * 1.3).toInt().coerceAtLeast(1)
            }
            ef = (ef + 0.15).coerceAtLeast(1.3)
        }
    }

    // baseInterval(ms) 계산: 계산된 일수에 ±10% 무작위 흔들림(jitter)을 한 번만 곱해준다.
    // [문법] Random.nextDouble(0.9, 1.1) → 0.9 이상 1.1 미만의 무작위 실수 하나를 뽑는다.
    val baseInterval = (intervalDays * MS_PER_DAY * Random.nextDouble(0.9, 1.1)).toLong()

    // 실제로 화면/DB에 쓰이는 간격은 여기에 압축 비율까지 곱한 값.
    val effectiveInterval = (baseInterval * compressionRatio).toLong()
    val nextReviewAt = addWindowTime(now, effectiveInterval, window)

    return CompletionSm2Result(
        repetition   = rep,
        baseInterval = baseInterval,
        easeFactor   = ef,
        nextReviewAt = nextReviewAt
    )
}

// ── 진행도(progress) 계산 ────────────────────────────────────────────────────

// 카드 하나가 "다음 복습까지 얼마나 왔는지"를 0.0~1.5 사이 값으로 계산.
// progress = (지난 시간) / (원래 간격) — 윈도우 유효 시간 기준으로 계산한다.
// 1.0이면 정확히 복습 시점 도달, 1.5까지 허용해서 "많이 늦었다"도 표현 가능.
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

// ── 모드 전환 시 nextReviewAt 재계산 ─────────────────────────────────────────

// 완주 모드 설정(윈도우나 압축 비율)이 바뀌었을 때, 기존 카드들의 "다음 복습 시각"을
// 새 설정 기준으로 다시 계산해준다. 카드가 "몇 % 진행됐는지"는 유지하면서 시간축만 바꾼다.
//
// 절차:
//  1. 옛 윈도우 기준으로 지금까지의 진행도(progress)를 구한다
//  2. 새 압축 비율로 "새 유효 간격"을 계산한다
//  3. 새 윈도우 기준 새 유효 간격에 progress를 곱해 "이미 흐른 것으로 칠 시간"을 구한다
//  4. 마지막 복습 시각 + 그 시간 = 새로운 다음 복습 시각
// 진행도가 이미 1.0을 넘었으면(원래도 늦은 카드) 그냥 지금 즉시 복습 대상으로 만든다.
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

// ── 카드 우선순위 분류 ───────────────────────────────────────────────────────

// 복습 시점이 지난(due) 카드가 "얼마나 많이 밀렸는지"를 계산해서 우선순위를 매긴다.
// realDelay  = 원래 예정 시각부터 지금까지 윈도우 기준으로 흐른 시간
// delayRatio = realDelay / baseInterval (원래 간격 대비 얼마나 밀렸는지 비율)
//   1.0 초과 → URGENT (원래 간격만큼, 혹은 그 이상 밀림)
//   0.5 초과 → HIGH
//   그 외    → NORMAL
fun classifyDueCard(card: CardEntity, now: Long, window: AllowedWindow): DueCardInfo {
    val realDelay  = calculateWindowTime(card.nextReviewAt, now, window)
    // [문법] card.baseInterval.takeIf { it > 0L } ?: MS_PER_DAY
    //   baseInterval이 0보다 크면 그대로 쓰고, 아니면(0 이하) 기본값으로 하루(MS_PER_DAY)를 대신 쓴다.
    //   나눗셈에서 0으로 나누는 사고를 막기 위한 안전장치.
    val baseInterval = card.baseInterval.takeIf { it > 0L } ?: MS_PER_DAY
    val delayRatio = realDelay.toDouble() / baseInterval

    // [문법] when { 조건1 -> A; 조건2 -> B; else -> C }
    //   괄호 안에 비교할 값을 안 넣고, 각 분기에 직접 조건식을 쓰는 형태.
    //   위에서부터 순서대로 검사해서 처음 true인 분기를 실행한다.
    val priority = when {
        delayRatio > 1.0 -> CardPriority.URGENT
        delayRatio > 0.5 -> CardPriority.HIGH
        else             -> CardPriority.NORMAL
    }
    return DueCardInfo(card, priority)
}

// ── 세션 계산 ────────────────────────────────────────────────────────────────

// 지금 시점 기준으로 "이번 세션에 몇 장을 봐야 하는지"를 계산한다.
//
// remainingWindowTime = 지금부터 마감까지, 윈도우 기준으로 남은 시간
// requiredSessions    = ceil(remainingWindowTime / 세션 간격)  → 마감까지 세션이 몇 번 남았는지
// missedSessions      = floor((지금 - 마지막 세션 시각) / 세션 간격)  → 건너뛴 세션이 있으면 그만큼 보정
// cardsPerSession     = ceil(남은 카드 수 / requiredSessions)
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

    // [문법] ceil(x) / floor(x)
    //   ceil은 올림(예: 2.1 → 3), floor는 내림(예: 2.9 → 2). "세션 수는 소수로 나올 수 없으니
    //   최소 그만큼은 필요하다"는 의미에서 requiredSessions는 올림을 쓴다.
    var requiredSessions = ceil(remainingWindowTime.toDouble() / sessionIntervalMs).toInt()
        .coerceAtLeast(1)

    // 세션을 건너뛴 적이 있으면(며칠 안 들어옴), 남은 세션 수를 줄여서 한 세션에 더 많이 몰아본다.
    if (lastSessionTime > 0L && now > lastSessionTime) {
        val missed = floor((now - lastSessionTime).toDouble() / sessionIntervalMs).toInt()
        if (missed > 0) {
            requiredSessions = (requiredSessions - missed).coerceAtLeast(1)
        }
    }

    val cardsPerSession = ceil(remainingCards.toDouble() / requiredSessions).toInt()
    return SessionInfo(cardsPerSession, requiredSessions)
}

// ── 완주 모드 종료 후 반영 ───────────────────────────────────────────────────

// 완주 모드가 끝난 뒤, 그동안의 학습 밀도와 정답률을 반영해서 baseInterval을 보정한다.
// (짧은 기간에 몰아서 공부했으면 그만큼 간격이 실제 기억 강도보다 부풀려져 있을 수 있어서 조정)
//
// density     = 실제 복습 횟수 / 기대 복습 횟수  (얼마나 밀도 있게 공부했는지)
// decayFactor = 1 / (1 + α × (density - 1))      (density가 높을수록 간격을 더 깎음)
// bonus       = baseInterval × β × 정답률 × ln(1 + 복습횟수)  (많이, 정확히 맞출수록 보너스)
// final       = max(baseInterval의 절반, baseInterval × decayFactor + bonus)
//
// 반환값: 새로운 nextReviewAt (지금 + 보정된 간격)
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
    // [문법] ln(x) → 자연로그. 복습 횟수가 늘어날수록 보너스가 커지되, 무한정 커지지 않고
    //   점점 완만해지게(로그 형태로 증가율을 죽이는) 만드는 흔한 수학적 트릭.
    val bonus    = baseInterval * BETA * accuracy * ln(1.0 + modeReviewCount)

    val finalInterval = (baseInterval * decayFactor + bonus)
        .coerceAtLeast(baseInterval / 2.0)  // 아무리 깎여도 원래 간격의 절반 밑으로는 안 내려가게
        .toLong()

    return now + finalInterval
}
