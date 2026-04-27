package com.example.anki_advanced.completion

/**
 * 윈도우 기반 시간 계산 유틸리티
 *
 * Android 의존성 없음 → JVM 단위 테스트 가능
 *
 * 핵심 원칙:
 *   윈도우 밖 시간은 "흐르지 않은 시간"으로 간주한다.
 *   새벽 3시(윈도우 9~22시 기준) = 시간 정지 상태.
 */

private const val MS_PER_HOUR = 60L * 60L * 1000L
private const val MS_PER_DAY  = 24L * MS_PER_HOUR

/**
 * [start, end) 구간 중 [window] 허용 시간대에 해당하는 ms만 반환.
 *
 * 예) start=03:00, end=12:00, window=09:00~22:00
 *   → 03:00~09:00 = 0ms (윈도우 밖)
 *     09:00~12:00 = 3시간
 *   → 반환값: 10_800_000ms
 */
fun calculateWindowTime(startMs: Long, endMs: Long, window: AllowedWindow): Long {
    if (endMs <= startMs) return 0L
    if (window.isFullDay) return endMs - startMs

    var total = 0L
    // startMs가 속한 날의 자정(로컬 기준이 아닌 UTC epoch 기반 자정)
    var dayStart = floorToDay(startMs)

    while (dayStart < endMs) {
        val windowStart = dayStart + window.startHour * MS_PER_HOUR
        val windowEnd   = dayStart + window.endHour   * MS_PER_HOUR
        val nextDay     = dayStart + MS_PER_DAY

        val effectiveStart = maxOf(startMs, windowStart)
        val effectiveEnd   = minOf(endMs, windowEnd, nextDay)

        if (effectiveStart < effectiveEnd) {
            total += effectiveEnd - effectiveStart
        }

        dayStart = nextDay
    }
    return total
}

/**
 * [from] 시각에서 윈도우 기준 [windowDurationMs]만큼 경과한 절대 시각(ms)을 반환.
 *
 * 예) from=10:00, windowDuration=39h, window=09:00~22:00 (13h/일)
 *   1일: 10:00~22:00 = 12h (누적 12)
 *   2일: 09:00~22:00 = 13h (누적 25)
 *   3일: 09:00~22:00 = 13h (누적 38)
 *   4일: 09:00~10:00 = 1h  (누적 39)
 *   → 4일 10:00 반환
 */
fun addWindowTime(fromMs: Long, windowDurationMs: Long, window: AllowedWindow): Long {
    if (windowDurationMs <= 0L) return fromMs
    if (window.isFullDay) return fromMs + windowDurationMs

    var accumulated = 0L
    var current = fromMs

    while (accumulated < windowDurationMs) {
        val dayStart    = floorToDay(current)
        val windowStart = dayStart + window.startHour * MS_PER_HOUR
        val windowEnd   = dayStart + window.endHour   * MS_PER_HOUR

        // current가 윈도우 시작 전이면 윈도우 시작으로 이동
        if (current < windowStart) {
            current = windowStart
            continue
        }

        // current가 윈도우 종료 이후면 다음 날 윈도우 시작으로 이동
        if (current >= windowEnd) {
            current = floorToDay(current) + MS_PER_DAY + window.startHour * MS_PER_HOUR
            continue
        }

        val remaining      = windowDurationMs - accumulated
        val availableToday = windowEnd - current

        if (remaining <= availableToday) {
            return current + remaining
        } else {
            accumulated += availableToday
            // 다음 날 윈도우 시작으로
            current = floorToDay(current) + MS_PER_DAY + window.startHour * MS_PER_HOUR
        }
    }

    return current
}

/** epoch ms를 UTC 자정(day floor)으로 내림 */
private fun floorToDay(ms: Long): Long = (ms / MS_PER_DAY) * MS_PER_DAY
