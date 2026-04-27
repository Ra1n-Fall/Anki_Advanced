package com.example.anki_advanced.completion

/**
 * 학습 허용 시간대 (윈도우)
 *
 * @param startHour 시작 시각 (0~23)
 * @param endHour   종료 시각 (1~24)
 *
 * 예) startHour=9, endHour=22 → 09:00~22:00만 학습 가능
 *     startHour=0, endHour=24 → 24시간 전부 학습 가능 (isFullDay = true)
 */
data class AllowedWindow(
    val startHour: Int = 0,
    val endHour: Int = 24
) {
    /** 24시간 전체 허용이면 true — 윈도우 계산을 단순 차이로 단락 처리 */
    val isFullDay: Boolean get() = startHour == 0 && endHour == 24

    /** 하루 중 허용 시간 (ms) */
    val dailyWindowMs: Long get() = (endHour - startHour) * 60L * 60L * 1000L
}
