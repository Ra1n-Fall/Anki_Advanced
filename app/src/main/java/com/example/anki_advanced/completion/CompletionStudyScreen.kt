package com.example.anki_advanced.completion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.anki_advanced.CARD_NEW
import com.example.anki_advanced.CardEntity
import com.example.anki_advanced.StudyScreenContent
import com.example.anki_advanced.StudyUiState

// 완주 모드 학습 화면. StudyScreen(일반 모드)의 UI를 거의 그대로 재사용하고,
// 위에 "완주 모드 배지 + 남은 기간" 한 줄만 얹은 구조다.

// ── 색상 팔레트 (StudyScreen과 값 공유) ───────────────────────────────────────
// [문법] Color(0xFF4A3AFF) → 0xAARRGGBB 형식의 16진수 색상값.
//   FF(불투명) + 4A3AFF(보라색 계열).
private val CtPrimary      = Color(0xFF4A3AFF)
private val CtOnSurfaceVar = Color(0xFF5A5B5F)
private val CtModeChip     = Color(0xFFEDE9FF)

// ── ViewModel과 연결되는 "진입점" 컴포저블 ────────────────────────────────────

// [문법] @Composable
//   이 함수가 "화면을 그리는 함수"라는 표시. 일반 함수와 달리, 안에서 사용한 상태값이
//   바뀌면 Compose가 이 함수를 알아서 다시 호출해 화면을 새로 그려준다(recomposition).
//
// [문법] viewModel: CompletionStudyViewModel = viewModel()
//   파라미터 기본값으로 viewModel() 컴포저블 함수를 호출. 이 함수는 Compose가
//   자동으로 이 화면에 맞는 CompletionStudyViewModel 인스턴스를 찾아서/새로 만들어서 넘겨준다.
//   호출하는 쪽에서 굳이 viewModel = ... 을 안 넘겨도 알아서 채워짐.
@Composable
fun CompletionStudyScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    viewModel: CompletionStudyViewModel = viewModel()
) {
    // [문법] val x by viewModel.y.collectAsState()
    //   viewModel의 StateFlow(y)를 "구독"해서 지금 값을 x에 담는다.
    //   by 키워드(위임) 덕분에 x는 매번 최신 값을 갖고, y가 바뀌면 이 컴포저블이 자동으로 재실행된다.
    //   즉 "이 값이 바뀌면 화면도 같이 바뀐다"를 한 줄로 표현하는 문법.
    val uiState     by viewModel.uiState.collectAsState()
    val currentCard by viewModel.currentCard.collectAsState()
    val undoSize    by viewModel.undoStackSize.collectAsState()
    val isLoading   by viewModel.isLoading.collectAsState()
    val session       by viewModel.sessionInfo.collectAsState()
    val sessionDone   by viewModel.sessionDone.collectAsState()
    val sessionTarget by viewModel.sessionTarget.collectAsState()
    val config        by viewModel.modeConfig.collectAsState()

    // [문법] var x by remember { mutableStateOf(false) }
    //   Compose 안에서만 쓰는 "화면 로컬 상태". remember{}가 없으면 화면이 다시 그려질 때마다
    //   변수가 초기값(false)으로 리셋돼버린다. remember는 "재구성돼도 이 값을 기억해둬"라는 뜻.
    var isFlipped by remember { mutableStateOf(false) }

    // [문법] LaunchedEffect(key) { ... }
    //   key 값이 바뀔 때마다(처음 화면에 나타날 때 포함) 딱 한 번 안의 코드를 실행하는 컴포저블.
    //   일반 함수 호출과 달리, 화면이 매번 다시 그려져도 key가 그대로면 다시 실행되지 않는다.
    //   아래 줄: currentCard가 바뀔 때마다(=새 카드로 넘어갈 때마다) 카드 뒤집힘 상태를 초기화.
    LaunchedEffect(currentCard) { isFlipped = false }
    // 아래 줄: deckId가 처음 정해질 때 한 번 학습 세션을 시작.
    LaunchedEffect(deckId)      { viewModel.startStudy(deckId) }

    CompletionStudyScreenContent(
        uiState        = uiState,
        currentCard    = currentCard,
        undoStackSize  = undoSize,
        isLoading      = isLoading,
        isFlipped      = isFlipped,
        sessionInfo    = session,
        sessionDone    = sessionDone,
        sessionTarget  = sessionTarget,
        modeEndAt      = config?.modeEndAt ?: 0L,
        onShowAnswer   = { isFlipped = true; viewModel.showAnswer() },
        onGrade        = { viewModel.applyGrade(it) },
        onUndo         = { viewModel.undoLast() },
        onBack         = { navController?.popBackStack() }
    )
}

// ── 순수 UI 부분 — ViewModel을 몰라서 Preview(미리보기)로 바로 그려볼 수 있음 ──
//
// [문법] 왜 굳이 위 함수(ViewModel 연결)와 이 함수(순수 UI)를 나눴을까?
//   이 함수는 파라미터로 "이미 계산된 값들"만 받고 ViewModel을 전혀 모른다.
//   그래서 실제 DB나 ViewModel 없이도 Android Studio의 @Preview 기능으로
//   화면 모양만 즉시 확인할 수 있다 (아래 Preview 함수들 참고).
@Composable
fun CompletionStudyScreenContent(
    uiState: StudyUiState,
    currentCard: CardEntity?,
    undoStackSize: Int,
    isLoading: Boolean,
    isFlipped: Boolean,
    sessionInfo: SessionInfo,
    sessionDone: Int,
    sessionTarget: Int,
    modeEndAt: Long,
    onShowAnswer: () -> Unit,
    onGrade: (Int) -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit
) {
    // LEARNING 카드가 세션 중 반복 등장하면 sessionDone이 sessionTarget을 넘어설 수 있어서,
    // 진행률 표시가 100%를 넘지 않도록 상한을 걸어준다.
    val displayDone = sessionDone.coerceAtMost(sessionTarget)

    // 완주 모드 배지를 화면 위에 겹쳐 그리는 대신, Column으로 세로 배치해서
    // "배지 → 학습 화면" 순서로 위아래에 나란히 둔다.
    //
    // [문법] Column(modifier = Modifier.fillMaxSize()) { ... }
    //   Column은 자식들을 위→아래로 세로로 쌓아주는 레이아웃.
    //   Modifier는 "이 컴포저블을 어떻게 꾸밀지"를 체이닝(.)으로 이어붙이는 설정 객체.
    //   fillMaxSize()는 "부모가 허용하는 한 가장 크게(화면 전체)"라는 뜻.
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 완주 모드 배지 ────────────────────────────────────────────────────
        CompletionModeBanner(modeEndAt = modeEndAt)

        // ── 핵심 학습 UI (일반 모드용 StudyScreenContent를 그대로 재사용) ──────
        // [문법] Modifier.weight(1f)
        //   Column/Row 안에서 "남은 공간을 다 차지해라"는 뜻. 배지가 위에서 자기 높이만큼만
        //   차지하고, 나머지 화면 전체를 이 Box가 가져가게 된다.
        Box(modifier = Modifier.weight(1f)) {
            StudyScreenContent(
                uiState       = uiState,
                currentCard   = currentCard,
                undoStackSize = undoStackSize,
                isLoading     = isLoading,
                isFlipped     = isFlipped,
                doneToday     = displayDone,
                totalToday    = sessionTarget,
                onShowAnswer  = onShowAnswer,
                onGrade       = onGrade,
                onUndo        = onUndo,
                onBack        = onBack
            )
        }
    }
}

// ── 완주 모드 배지 (상단에 "완주 모드 · D-7" 처럼 보여주는 작은 띠) ───────────

// [문법] private fun / private @Composable fun
//   private가 붙으면 이 파일 밖에서는 이 함수를 호출할 수 없다. 이 화면 전용 부품이라는 뜻.
@Composable
private fun CompletionModeBanner(modeEndAt: Long) {
    val now = System.currentTimeMillis()
    // coerceAtLeast(0L): 이미 마감이 지났어도 음수가 나오지 않게 0으로 바닥을 깔아준다.
    val remainingMs = (modeEndAt - now).coerceAtLeast(0L)
    val remainingDays = (remainingMs / (24L * 60L * 60L * 1000L)).toInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CtModeChip)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(CtPrimary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("완주 모드", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        // [문법] if (조건) A else B  → 코틀린의 if는 "식(expression)"으로도 쓸 수 있어서,
        //   삼항 연산자(? :) 없이 바로 값을 만들어낼 수 있다.
        Text(
            text = if (remainingDays > 0) "D-$remainingDays" else "오늘 마감",
            color = CtPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Preview: Android Studio 디자인 탭에서 실제 기기 없이 바로 화면을 볼 수 있게 해줌 ──
//
// [문법] @Preview(showBackground = true) + @Composable + 파라미터 없는 함수
//   이 조합이면 Android Studio가 코드 옆에 미리보기 이미지를 렌더링해준다.
//   ViewModel 없이 CompletionStudyScreenContent를 직접 부르면서, 보고 싶은 상태값을
//   전부 하드코딩으로 채워 넣어 "질문 화면일 때", "답 화면일 때" 각각을 확인하는 용도.

@Preview(showBackground = true)
@Composable
fun CompletionStudyScreenQuestionPreview() {
    CompletionStudyScreenContent(
        uiState       = StudyUiState.QUESTION,
        currentCard   = CardEntity(id = 1, deckId = 1, front = "Ephemeral", back = "일시적인", tags = "VOCABULARY",
            status = CARD_NEW, state = 0),
        undoStackSize = 0,
        isLoading     = false,
        isFlipped     = false,
        sessionInfo   = SessionInfo(cardsPerSession = 20, requiredSessions = 7),
        sessionDone   = 5,
        sessionTarget = 20,
        modeEndAt     = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000,
        onShowAnswer  = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}

@Preview(showBackground = true)
@Composable
fun CompletionStudyScreenAnswerPreview() {
    CompletionStudyScreenContent(
        uiState       = StudyUiState.ANSWER,
        currentCard   = CardEntity(id = 1, deckId = 1, front = "Ephemeral", back = "일시적인", tags = "VOCABULARY",
            status = CARD_NEW, state = 0),
        undoStackSize = 1,
        isLoading     = false,
        isFlipped     = true,
        sessionInfo   = SessionInfo(cardsPerSession = 20, requiredSessions = 7),
        sessionDone   = 12,
        sessionTarget = 20,
        modeEndAt     = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000,
        onShowAnswer  = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}
