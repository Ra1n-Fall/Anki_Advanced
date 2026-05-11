package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao // 쿼리들을 모아놓은 인터페이스라는 것을 명시
interface CardDao {

    @Query("SELECT * FROM cards ORDER BY id DESC") // 모든 row를 id순으로 선택
    suspend fun getAll(): List<CardEntity> // 선택 결과를 CardEntity 타입의 리스트로 반환
    // suspend 키워드로 작업스레드에서 동작할 수 있는 함수로 선언

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY id ASC")
    suspend fun getByDeck(deckId: Long): List<CardEntity>

    @Insert // insert함수임을 명시
    suspend fun insert(card: CardEntity): Long
    // 인수로 CardEntity 타입을 하나 받아서 테이블에 row 단위로 insert 및 Long타입으로 insert한 row의 id를 반환
    // suspend 키워드로 작업스레드에서 동작할 수 있는 함수로 선언

    @Query("DELETE FROM cards WHERE id = :id")
    // :id는 코틀린의 id 변수와 같은 의미
    suspend fun deleteById(id: Long)

    @Update
    suspend fun update(card: CardEntity)

    @Query("UPDATE cards SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: Int)
    // 카드 상태를 업데이트하는 쿼리

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
    // sm2 관련 값들을 바탕으로 카드를 업데이트하는 쿼리

    // [변경] updateLearningStep 추가
    // 이유: learningStep은 SM2 값과 별개로 관리되므로 별도 쿼리로 업데이트
    @Query("UPDATE cards SET learningStep = :step WHERE id = :id")
    suspend fun updateLearningStep(id: Long, step: Int)

    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
          AND nextReviewAt <= :now
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextLearningCard(deckId: Long, now: Long): CardEntity?

    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 1
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextPendingLearningCard(deckId: Long): CardEntity?
    // 시간 조건 없이 nextReviewAt 가장 이른 LEARNING 카드 1장
    // 사용자가 1분/10분 안에 모든 카드를 소진했을 때
    // 시간 조건 무시하고 대기 중인 LEARNING 카드를 조기 등장시킴
    // → "학습 완료"로 잘못 끝나는 문제 방지

    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :todayStart
        ORDER BY nextReviewAt ASC
        LIMIT 1
    """)
    suspend fun getNextReviewCard(deckId: Long, todayStart: Long): CardEntity?
    //오늘 복습 카드 전체 로딩 쿼리

    @Query("""
        SELECT * FROM cards
        WHERE deckId = :deckId
          AND status = 0
        ORDER BY id ASC
        LIMIT 1
    """)
    suspend fun getNextNewCard(deckId: Long): CardEntity?
    //새 카드 전체 로딩

    // 홈 화면 덱 카운트용 쿼리 3개 추가
    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 0")
    suspend fun countNewCards(deckId: Long): Int
    // new 카드 수: status = 0인 카드 전체

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND status = 1")
    suspend fun countLearningCards(deckId: Long): Int
    // learn 카드 수: 학습 중인 카드는 시간 조건 없이 LEARNING 상태인 카드 전체

    @Query("""
        SELECT COUNT(*) FROM cards
        WHERE deckId = :deckId
          AND status = 2
          AND nextReviewAt <= :todayStart
    """)
    suspend fun countReviewCards(deckId: Long, todayStart: Long): Int
    // review 카드 수: 오늘 자정 이전이 nextReviewAt인 카드만

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId")
    suspend fun countAllCards(deckId: Long): Int
}