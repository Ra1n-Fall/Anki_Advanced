package com.example.anki_advanced.completion

// 완주 모드의 "허용 시간대(윈도우)"를 고려해서 시간을 계산해주는 순수 계산 함수 모음.
// Android 클래스에 전혀 의존하지 않는 순수 코틀린 파일 → 안드로이드 없이도 JVM에서 단위 테스트 가능.
//
// 핵심 아이디어: 윈도우 밖의 시간은 "흐르지 않은 시간"으로 친다.
//   예) 윈도우가 09:00~22:00이면, 새벽 3시는 "학습 세계관"에서는 시간이 멈춰있는 시점으로 취급.

// [문법] private const val
//   이 파일 안에서만 쓰는 상수. private라서 다른 파일에서는 못 본다.
private const val MS_PER_HOUR = 60L * 60L * 1000L   // 1시간 = 3,600,000ms
private const val MS_PER_DAY  = 24L * MS_PER_HOUR   // 1일 = 86,400,000ms

// [startMs, endMs) 구간 중에서 윈도우(허용 시간대) 안에 들어가는 시간만 ms로 합산.
//
// 예) start=03:00, end=12:00, window=09:00~22:00
//   03:00~09:00 = 0ms (윈도우 밖이라 시간 정지 취급)
//   09:00~12:00 = 3시간 실제로 카운트
//   → 반환값: 10,800,000ms (=3시간)
fun calculateWindowTime(startMs: Long, endMs: Long, window: AllowedWindow): Long {
    if (endMs <= startMs) return 0L
    if (window.isFullDay) return endMs - startMs  // 24시간 전부 허용이면 그냥 통째로 차이값 반환

    var total = 0L
    // startMs가 속한 날짜의 "자정" 시각 (UTC epoch 기준 자정. 기기 로컬 시간대와는 무관)
    var dayStart = floorToDay(startMs)

    // [문법] while (조건) { ... }  → 조건이 true인 동안 계속 반복하는 반복문.
    //   여기서는 "하루씩" 건너뛰면서, 각 날짜의 윈도우와 [startMs, endMs] 구간이
    //   겹치는 부분만 골라 더해나간다.
    while (dayStart < endMs) {
        val windowStart = dayStart + window.startHour * MS_PER_HOUR  // 이 날의 윈도우 시작 시각
        val windowEnd   = dayStart + window.endHour   * MS_PER_HOUR  // 이 날의 윈도우 종료 시각
        val nextDay     = dayStart + MS_PER_DAY

        // [문법] maxOf(a, b) / minOf(a, b, c)
        //   여러 값 중 가장 큰 값 / 가장 작은 값을 구하는 표준 함수.
        //   여기서는 "구간 A와 구간 B가 겹치는 부분"을 구하는 흔한 패턴:
        //   겹치는 시작 = 두 구간 시작 중 더 늦은 쪽(max), 겹치는 끝 = 두 구간 끝 중 더 이른 쪽(min).
        val effectiveStart = maxOf(startMs, windowStart)
        val effectiveEnd   = minOf(endMs, windowEnd, nextDay)

        if (effectiveStart < effectiveEnd) {
            total += effectiveEnd - effectiveStart
        }

        dayStart = nextDay // 다음 날로 이동
    }
    return total
}

// from 시각으로부터 "윈도우 기준으로" windowDurationMs만큼 시간이 흐른 뒤의 절대 시각을 구한다.
// (윈도우 밖 시간은 안 세므로, 달력상으로는 그보다 더 많은 시간이 지나야 할 수 있다)
//
// 예) from=10:00, windowDuration=39시간, window=09:00~22:00 (하루 13시간 허용)
//   1일차: 10:00~22:00 = 12시간 사용 (누적 12)
//   2일차: 09:00~22:00 = 13시간 사용 (누적 25)
//   3일차: 09:00~22:00 = 13시간 사용 (누적 38)
//   4일차: 09:00~10:00 = 1시간 사용  (누적 39, 목표 도달)
//   → 4일째 10:00을 반환
fun addWindowTime(fromMs: Long, windowDurationMs: Long, window: AllowedWindow): Long {
    if (windowDurationMs <= 0L) return fromMs
    if (window.isFullDay) return fromMs + windowDurationMs

    var accumulated = 0L   // 지금까지 "윈도우 안에서" 누적된 시간
    var current = fromMs   // 현재 계산 중인 시각 (계속 앞으로 이동시켜 나간다)

    while (accumulated < windowDurationMs) {
        val dayStart    = floorToDay(current)
        val windowStart = dayStart + window.startHour * MS_PER_HOUR
        val windowEnd   = dayStart + window.endHour   * MS_PER_HOUR

        // current가 오늘 윈도우 시작 전(예: 새벽)이면, 윈도우 시작 시각으로 점프.
        // [문법] continue → 반복문의 나머지 코드를 건너뛰고 while 조건 검사로 바로 되돌아감.
        if (current < windowStart) {
            current = windowStart
            continue
        }

        // current가 오늘 윈도우가 이미 끝난 뒤(예: 밤)라면, 다음날 윈도우 시작으로 점프.
        if (current >= windowEnd) {
            current = floorToDay(current) + MS_PER_DAY + window.startHour * MS_PER_HOUR
            continue
        }

        val remaining      = windowDurationMs - accumulated  // 앞으로 더 채워야 할 시간
        val availableToday = windowEnd - current              // 오늘 윈도우 안에서 쓸 수 있는 남은 시간

        if (remaining <= availableToday) {
            // 오늘 안에 목표를 다 채울 수 있으면, 그 지점을 바로 반환하고 끝.
            return current + remaining
        } else {
            // 오늘 몫을 다 쓰고도 모자라면, 오늘 쓴 만큼만 누적하고 다음날로 이동해서 계속.
            accumulated += availableToday
            current = floorToDay(current) + MS_PER_DAY + window.startHour * MS_PER_HOUR
        }
    }

    return current
}

// 주어진 시각(epoch ms)이 속한 날짜의 자정(00:00, UTC 기준) 시각으로 내림.
// [문법] (ms / MS_PER_DAY) * MS_PER_DAY
//   정수 나눗셈은 나머지를 버린다(내림). 하루 단위로 나눴다가 다시 곱하면
//   "그날 자정"으로 딱 떨어지는 값이 된다. 예: 13:30분의 시각도 이 계산을 거치면 00:00이 됨.
private fun floorToDay(ms: Long): Long = (ms / MS_PER_DAY) * MS_PER_DAY
