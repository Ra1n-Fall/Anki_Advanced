package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.PrimaryKey

data class CardUi(
    val id: Long,
    val front: String,
    val back: String,
    val tags: String,
    var state: Int,
    var status: Int
)

const val CARD_NEW = 0
const val CARD_LEARNING = 1
const val CARD_REVIEW = 2
@Entity(tableName = "cards")
// 이 클래스를 SQLite 테이블로 만들라는 표시
// tableName = "cards" → 테이블 이름을 cards로 지정

data class CardEntity(

    @PrimaryKey(autoGenerate = true)
    // id 컬럼을 기본키(Primary Key)로 지정
    // autoGenerate = true → DB가 id를 자동으로 1,2,3... 생성

    val id: Long = 0L,
    // 카드의 고유 번호(기본키)
// 기본값 0L은 "아직 DB에 저장되기 전"이라는 의미로 흔히 둡니다.
// insert 시에는 0L로 넣어도 DB가 실제 id를 자동으로 만들어 줍니다.
    val deckId: Long,
    val front: String,
    val back: String,
    val tags: String,

    val status: Int,          // NEW / LEARNING / REVIEW
    val state: Int = 0,//카드 점수(again, easy, normal, hard)

    val repetition: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val nextReviewAt: Long = 0L,
    // SM2 알고리즘 계산 위한 변수

    // steps = [1분, 10분], step 0→1분 후, step 1→10분 후, step 끝→REVIEW 졸업
    val learningStep: Int = 0,

    // completion mode 전용
    val baseInterval: Long = 0L,  // SM-2 ms 기반 원본 간격 (압축 전)
    val lastReviewAt: Long = 0L   // 마지막 채점 시각 (progress 계산에 필요)

)
