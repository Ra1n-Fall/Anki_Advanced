package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// "review_logs"(채점 기록) 테이블에 접근하는 창구.
// 주로 홈 화면 통계(오늘 몇 개 공부했는지, 연속 학습일 등)를 계산할 때 쓰인다.
@Dao
interface ReviewLogDao {

    // 채점 기록 한 줄을 저장.
    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    // 특정 id의 로그 삭제 (예: 되돌리기/Undo 기능에서 방금 쌓은 로그를 취소할 때 사용)
    @Query("DELETE FROM review_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    // 특정 기간(보통 "오늘") 동안 이 덱에서 채점한 카드 수를 센다.
    // [문법] BETWEEN :start AND :end → SQL에서 "start 이상 end 이하" 범위 조건.
    @Query("""
        SELECT COUNT(*) FROM review_logs
        WHERE deckId = :deckId AND reviewedAt BETWEEN :start AND :end
    """)
    suspend fun countToday(deckId: Long, start: Long, end: Long): Int

    // 위와 비슷하지만, 점수(score)별로 몇 개씩 채점했는지 묶어서 센다.
    // [문법] GROUP BY score → score 값이 같은 행들을 하나로 묶어서 COUNT(*) 계산.
    //   결과 예시:
    //   | score | cnt |
    //   | ----- | --- |
    //   | 0     | 5   |   (Again 5번)
    //   | 1     | 3   |   (Hard 3번)
    //   | 2     | 10  |   (Good 10번)
    //   | 3     | 7   |   (Easy 7번)
    @Query("""
        SELECT score, COUNT(*) as cnt FROM review_logs
        WHERE deckId = :deckId AND reviewedAt BETWEEN :start AND :end
        GROUP BY score
    """)
    suspend fun countByScoreToday(deckId: Long, start: Long, end: Long): List<ScoreCountRow>

    // 오늘 채점한 것 중 "새 카드(NEW)였던 것"만 센다.
    @Query("""
    SELECT COUNT(*) FROM review_logs
    WHERE deckId = :deckId
      AND reviewedAt >= :todayStart
      AND isNewAtReview = 1
    """)
    suspend fun countNewCardsToday(deckId: Long, todayStart: Long): Int

    // 오늘 채점한 것 중 "복습(REVIEW) 카드였던 것"만 센다.
    @Query("""
    SELECT COUNT(*) FROM review_logs
    WHERE deckId = :deckId
      AND reviewedAt >= :todayStart
      AND isNewAtReview = 0
    """)
    suspend fun countReviewCardsToday(deckId: Long, todayStart: Long): Int

    // 이 덱을 마지막으로 공부한 시각. 한 번도 공부한 적 없으면 null.
    // [문법] MAX(reviewedAt) → 여러 값 중 가장 큰(가장 최근) 값 하나만 뽑는 집계 함수.
    @Query("SELECT MAX(reviewedAt) FROM review_logs WHERE deckId = :deckId")
    suspend fun getLastStudied(deckId: Long): Long?

    // 전체 덱을 통틀어 지금까지 채점한 총 횟수.
    @Query("SELECT COUNT(*) FROM review_logs")
    suspend fun countAll(): Int

    // 연속 학습일(스트릭) 계산에 쓰이는 "공부한 날짜 목록"을 구한다.
    // [문법] reviewedAt / 86400000
    //   reviewedAt은 밀리초 단위 시각. 하루는 86,400,000ms이므로, 이 나눗셈 결과는
    //   "1970-01-01부터 며칠째인지"를 나타내는 정수(day key)가 된다. 같은 날 찍힌 기록들은
    //   전부 같은 dayKey를 갖게 되므로, 이걸로 "며칠에 공부했는지"를 구분할 수 있다.
    // [문법] SELECT DISTINCT → 중복 제거하고 서로 다른 값만 가져온다.
    @Query("""
        SELECT DISTINCT (reviewedAt / 86400000) as dayKey
        FROM review_logs
        ORDER BY dayKey DESC
    """)
    suspend fun getAllStudyDayKeys(): List<Long>
}

// countByScoreToday() 쿼리 결과 한 줄을 담는 그릇.
// [문법] Room은 SELECT 결과의 컬럼 이름(score, cnt)과 이 data class의 프로퍼티 이름을
//   자동으로 맞춰서 값을 채워준다. 그래서 컬럼 이름 ↔ 프로퍼티 이름이 일치해야 한다.
data class ScoreCountRow(
    val score: Int,
    val cnt: Int
)
