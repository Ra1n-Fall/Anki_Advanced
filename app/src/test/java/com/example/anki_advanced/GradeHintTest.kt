package com.example.anki_advanced

import org.junit.Assert.assertEquals
import org.junit.Test

// 채점 버튼에 표시하는 간격 문자열이 알맞은 단위로 반올림되는지 확인한다.
class GradeHintTest {
    @Test fun seconds() = assertEquals("30초", formatGradeInterval(30_000))
    @Test fun minutes() {
        assertEquals("1분", formatGradeInterval(60_000))
        assertEquals("10분", formatGradeInterval(600_000))
    }
    @Test fun hours() = assertEquals("2시간", formatGradeInterval(2 * 3_600_000L))
    @Test fun days() {
        assertEquals("1일", formatGradeInterval(86_400_000L))
        assertEquals("4일", formatGradeInterval(4 * 86_400_000L - 3_600_000L))
    }
    @Test fun negativeIsZero() = assertEquals("0초", formatGradeInterval(-5_000))
}
