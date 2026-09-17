package com.example.anki_advanced

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// "decks" 테이블에 접근하는 창구(DAO = Data Access Object).
// 여기 적힌 함수들을 호출하면 Room이 내부적으로 SQL을 실행해준다.
//
// [문법] @Dao + interface
//   함수 몸통(구현부)을 우리가 직접 안 짜도 된다. @Query, @Insert 같은 어노테이션만
//   붙여두면 Room이 컴파일 시점에 진짜 구현 클래스를 자동으로 만들어준다.
//
// [문법] suspend fun
//   "이 함수는 코루틴 안에서만 호출할 수 있고, 오래 걸릴 수 있는 작업(DB I/O)을
//   메인 스레드를 막지 않고 처리한다"는 표시. Room은 suspend 함수를 만나면
//   자동으로 백그라운드 스레드에서 쿼리를 실행해준다.
@Dao
interface DeckDao {

    // 모든 덱을 최신(id 큰 순) 순서로 가져온다.
    @Query("SELECT * FROM decks ORDER BY id DESC")
    suspend fun getAll(): List<DeckEntity>

    // 새 덱 한 개를 추가한다. 반환값(Long)은 새로 생긴 행의 id.
    @Insert
    suspend fun insert(deck: DeckEntity): Long

    // id로 덱 하나를 삭제한다.
    // [문법] :id 처럼 콜론(:)이 붙은 이름은 "쿼리 파라미터".
    //   아래 함수 매개변수 id 값이 SQL의 :id 자리에 안전하게 바인딩된다(SQL 인젝션 방지).
    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteById(id: Long)

    // 특정 덱의 하루 학습 한도(새 카드/복습 카드 몇 장까지)를 조회.
    @Query("""
        SELECT dailyNewLimit, dailyReviewLimit
        FROM decks
        WHERE id = :deckId
    """)
    suspend fun getStudyLimits(
        deckId: Long
    ): DeckStudyLimit

    // 덱 설정 화면에서 사용자가 한도를 바꾸면 이 함수로 DB 값을 갱신.
    @Query("""
    UPDATE decks
    SET dailyNewLimit = :newLimit,
        dailyReviewLimit = :reviewLimit
    WHERE id = :deckId
""")
    suspend fun updateStudyLimits(deckId: Long, newLimit: Int, reviewLimit: Int)
}
