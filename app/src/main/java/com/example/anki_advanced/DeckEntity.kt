package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.PrimaryKey

// 화면(홈 화면 덱 목록)에 그대로 뿌리기 위한 "화면 전용" 데이터 모양.
// DB에 저장되는 DeckEntity와 다르게, DB 값들을 계산/가공해서 화면에 보여줄 형태로 미리 정리해둔 것.
// 이런 걸 보통 "UI 모델" 또는 "UI State"라고 부른다. DB 구조가 바뀌어도 화면 코드는 안 바뀌게 분리하는 목적.
data class DeckUi(
    val id: Long,
    val name: String,
    val newCount: Int,                       // 아직 한 번도 안 본 카드 수
    val learnCount: Int,                     // 학습 중(LEARNING) 카드 수
    val reviewCount: Int,                    // 복습 대상(REVIEW) 카드 수
    val lastStudiedAt: Long? = null,         // 마지막으로 공부한 시각(ms). 공부 이력 없으면 null
    val completionModeEndAt: Long? = null,   // 완주 모드 목표 마감 시각. null이면 완주 모드 꺼짐
    val completionTargetDays: Int? = null    // 완주 모드 목표 기간(일). 덱 배지에 "D-7" 같이 표시할 때 씀
)
// [문법] Long? 처럼 타입 뒤에 물음표(?)가 붙으면 "널(null)이 될 수 있는 타입"이라는 뜻.
//   코틀린은 기본적으로 val x: Long = null 처럼 널을 넣는 게 컴파일 에러다.
//   물음표를 붙여야만 "이 값은 없을 수도 있다"를 타입 차원에서 허용해준다. (Null Safety)

// 덱마다 하루에 새 카드/복습 카드를 몇 개까지 볼지 정해둔 설정값.
data class DeckStudyLimit(
    val dailyNewLimit: Int,
    val dailyReviewLimit: Int
)

// [문법] @Entity(tableName = "decks")
//   이 클래스가 Room에게 "decks"라는 이름의 SQLite 테이블이 된다는 뜻.
@Entity(tableName = "decks")
data class DeckEntity(
    // @PrimaryKey(autoGenerate = true): 기본키이며, DB가 id를 1,2,3... 자동 채번.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val name: String,               // 덱 이름 (사용자가 짓는 이름, 예: "토익 단어")

    val dailyNewLimit: Int = 10,    // 하루에 새로 볼 카드 수 상한 (기본 10장)

    val dailyReviewLimit: Int = 50  // 하루에 복습할 카드 수 상한 (기본 50장)
)
