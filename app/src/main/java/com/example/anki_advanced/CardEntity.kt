package com.example.anki_advanced

import androidx.room.Entity
import androidx.room.PrimaryKey

// 화면에 카드를 그릴 때 쓰는 "UI 전용" 모양. (DB의 CardEntity와는 별개)
data class CardUi(
    val id: Long,
    val front: String,   // 카드 앞면 (질문)
    val back: String,    // 카드 뒷면 (답)
    val tags: String,
    var state: Int,       // 마지막 채점 점수
    var status: Int       // NEW / LEARNING / REVIEW 상태
)

// [문법] const val
//   컴파일 시점에 값이 고정되는 상수. "0", "1", "2" 같은 매직 넘버를 여기저기 흩뿌리지 않고
//   CARD_NEW, CARD_LEARNING 처럼 이름을 붙여 코드 가독성을 높이는 용도.
const val CARD_NEW = 0       // 아직 한 번도 안 본 카드
const val CARD_LEARNING = 1  // 학습 단계(짧은 간격으로 반복 노출) 중인 카드
const val CARD_REVIEW = 2    // 학습을 졸업하고 SM-2 간격으로 복습 중인 카드

// [문법] @Entity(tableName = "cards") → 이 클래스가 "cards" SQLite 테이블이 된다.
@Entity(tableName = "cards")
data class CardEntity(

    // 기본키. autoGenerate = true라서 새로 insert할 때 0L을 넣어도 DB가 실제 id를 자동 채번한다.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val deckId: Long,   // 이 카드가 속한 덱의 id
    val front: String,  // 카드 앞면
    val back: String,   // 카드 뒷면
    val tags: String,   // 태그 (분류용 텍스트)

    val status: Int,          // NEW(0) / LEARNING(1) / REVIEW(2) 중 하나
    val state: Int = 0,       // 마지막 채점 점수 (0=Again, 1=Hard, 2=Good, 3=Easy)

    // ↓↓↓ 여기부터 SM-2 간격 반복 알고리즘 계산에 쓰이는 값들
    val repetition: Int = 0,        // 연속으로 몇 번 "합격(Good 이상)" 했는지
    val intervalDays: Int = 0,      // 다음 복습까지 며칠 기다릴지
    val easeFactor: Double = 2.5,   // 난이도 계수. 쉬울수록 커지고, 어려울수록 작아짐 (간격에 곱해짐)
    val nextReviewAt: Long = 0L,    // 다음 복습 예정 시각(ms)

    // 학습(LEARNING) 단계에서 몇 번째 스텝인지.
    // steps 배열이 [1분, 10분]이라면: step 0 → 1분 뒤 재노출, step 1 → 10분 뒤 재노출,
    // 마지막 스텝까지 통과하면 REVIEW 상태로 "졸업".
    val learningStep: Int = 0,

    // ↓↓↓ 완주 모드(기간 내 끝내기) 전용 필드
    val baseInterval: Long = 0L,  // 완주 모드로 압축하기 "전"의 원래 SM-2 간격(ms). 압축 비율 계산의 기준값
    val lastReviewAt: Long = 0L   // 마지막으로 채점한 시각(ms). 진행률(progress) 계산에 사용
)
