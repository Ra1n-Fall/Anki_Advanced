package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    @Query("DELETE FROM review_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
    //해당 아이디를 가진 로그 삭제

    @Query("""
        SELECT COUNT(*) FROM review_logs
        WHERE deckId = :deckId AND reviewedAt BETWEEN :start AND :end
    """)
    suspend fun countToday(deckId: Long, start: Long, end: Long): Int
    //특정 기간(오늘) 학습한 카드의 수를 반환하는 쿼리

    @Query("""
        SELECT score, COUNT(*) as cnt FROM review_logs
        WHERE deckId = :deckId AND reviewedAt BETWEEN :start AND :end
        GROUP BY score
    """)
    suspend fun countByScoreToday(deckId: Long, start: Long, end: Long): List<ScoreCountRow>
    //특정 기간(오늘) 학습한 카드의 수를 반환하되 점수에 따라 그룹화해서 반환하는 쿼리
    //| score | cnt |
    //| ----- | --- |
    //| 0     | 5   |
    //| 1     | 3   |
    //| 2     | 10  |
    //| 3     | 7   |

    @Query("""
    SELECT COUNT(*) FROM review_logs
    WHERE deckId = :deckId
      AND reviewedAt >= :todayStart
      AND isNewAtReview = 1
    """)
    suspend fun countNewCardsToday(deckId: Long, todayStart: Long): Int
    // 오늘 학습한 new 카드 수

    @Query("""
    SELECT COUNT(*) FROM review_logs
    WHERE deckId = :deckId
      AND reviewedAt >= :todayStart
      AND isNewAtReview = 0
    """)
    suspend fun countReviewCardsToday(deckId: Long, todayStart: Long): Int
    // 오늘 학습한 review 카드 수

}



data class ScoreCountRow(
    val score: Int,
    val cnt: Int
)
