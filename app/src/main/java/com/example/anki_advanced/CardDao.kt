package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

// "cards" 테이블에 접근하는 창구. 이 앱에서 가장 많이 쓰이는 DAO.
// [문법] @Dao + interface → Room이 이 인터페이스의 진짜 구현체를 자동 생성해준다 (직접 코드 안 짜도 됨).
@Dao
interface CardDao {

    // 덱 구분 없이 전체 카드를 id 역순(최신순)으로 가져온다.
    // [문법] suspend fun → 코루틴 안에서만 호출 가능. Room이 자동으로 백그라운드 스레드에서 실행해준다.
    @Query("SELECT * FROM cards ORDER BY id DESC")
    suspend fun getAll(): List<CardEntity>

    // 특정 덱의 카드만 id 오름차순으로 가져온다.
    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY id ASC")
    suspend fun getByDeck(deckId: Long): List<CardEntity>

    // 새 카드 한 장을 추가. 반환값은 새로 생긴 행의 id.
    @Insert
    suspend fun insert(card: CardEntity): Long

    // id로 카드 한 장 삭제.
    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteById(id: Long)

    // [문법] @Update
    //   전달받은 객체의 기본키(id)와 일치하는 행을 찾아서, 그 객체의 나머지 값들로 통째로 덮어쓴다.
    //   특정 컬럼만 바꾸고 싶으면 보통 아래처럼 @Query로 UPDATE문을 직접 쓴다.
    @Update
    suspend fun update(card: CardEntity)

    // 채점 점수(state)만 콕 집어 업데이트.
    @Query("UPDATE cards SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: Int)

    // SM-2 알고리즘 계산 결과(간격 반복 관련 값들)를 한 번에 업데이트.
    @Query("""
        UPDATE cards
        SET state = :state,
            status = :status,
            repetition = :repetition,
            intervalDays = :intervalDays,
            easeFactor = :easeFactor,
            nextReviewAt = :nextReviewAt
        WHERE id = :id
    """)
    suspend fun updateSm2(
        id: Long,
        state: Int,
        status: Int,
        repetition: Int,
        intervalDays: Int,
        easeFactor: Double,
        nextReviewAt: Long
    )

    // 학습(LEARNING) 단계의 진행 스텝만 별도로 업데이트.
    // (SM-2 값과는 독립적으로 관리되는 값이라 별도 쿼리로 분리해둠)
    @Query("UPDATE cards SET learningStep = :step WHERE id = :id")
    suspend fun updateLearningStep(id: Long, step: Int)

    // 지금 당장(now 이전) 재노출 시각이 된 LEARNING 카드 1장을 가져온다. (status=1은 LEARNING)
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextLearningCard(deckId: Long, now: Long): CardEntity?

    // 위와 비슷하지만 "시각 조건 없이" 가장 빨리 재노출될 LEARNING 카드 1장을 가져온다.
    // 사용자가 1분/10분 대기 카드까지 너무 빨리 다 소진해서 세션이 "학습 완료"로
    // 잘못 끝나버리는 걸 막기 위해, 대기 중인 카드를 시간 무시하고 조기 등장시키는 용도.
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextPendingLearningCard(deckId: Long): CardEntity?

    // 오늘 자정(todayStart) 이전이 재노출 시각인 REVIEW 카드(status=2) 1장을 가져온다.
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :todayStart
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextReviewCard(deckId: Long, todayStart: Long): CardEntity?

    // 한 번도 안 본 NEW 카드(status=0) 1장을 가져온다.
    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 0
        ORDER BY id ASC
        LIMIT 1
    """)
    suspend fun getNextNewCard(deckId: Long): CardEntity?

    // ↓↓↓ 홈 화면 덱 카드에 "새 3 / 학습 2 / 복습 5" 같은 숫자를 보여주기 위한 카운트 쿼리들.

    // NEW 카드 수: status = 0인 카드 전체 (시각 조건 없음)
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 0")
    suspend fun countNewCards(deckId: Long): Int

    // LEARNING 카드 수: 학습 중인 카드는 시각 조건 없이 status = 1 전체를 센다
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 1")
    suspend fun countLearningCards(deckId: Long): Int

    // REVIEW 카드 수: 오늘 자정 이전이 nextReviewAt인 카드만 (아직 안 온 미래 복습은 제외)
    @Query("""
        SELECT COUNT(*) FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :todayStart
    """)
    suspend fun countReviewCards(deckId: Long, todayStart: Long): Int

    // 이 덱의 전체 카드 수 (상태 무관)
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId")
    suspend fun countAllCards(deckId: Long): Int
}
