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

// ── 색상 (StudyScreen 팔레트 공유) ───────────────────────────────────────────
private val CtPrimary      = Color(0xFF4A3AFF)
private val CtOnSurfaceVar = Color(0xFF5A5B5F)
private val CtModeChip     = Color(0xFFEDE9FF)

// ── ViewModel 진입점 ──────────────────────────────────────────────────────────

/**
 * 기간 완주형 SRS 학습 화면
 *
 * [StudyScreenContent]를 그대로 재사용하고, 상단에 완주 모드 배지와
 * 세션 정보만 추가한다.
 *
 * 카드 플립 / 채점 버튼 / 되돌리기 / 완료 화면은 [StudyScreenContent]가 처리한다.
 */
@Composable
fun CompletionStudyScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    viewModel: CompletionStudyViewModel = viewModel()
) {
    val uiState     by viewModel.uiState.collectAsState()
    val currentCard by viewModel.currentCard.collectAsState()
    val undoSize    by viewModel.undoStackSize.collectAsState()
    val isLoading   by viewModel.isLoading.collectAsState()
    val session       by viewModel.sessionInfo.collectAsState()
    val sessionDone   by viewModel.sessionDone.collectAsState()
    val sessionTarget by viewModel.sessionTarget.collectAsState()
    val config        by viewModel.modeConfig.collectAsState()

    var isFlipped by remember { mutableStateOf(false) }
    LaunchedEffect(currentCard) { isFlipped = false }
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

// ── 순수 UI — ViewModel 없음, Preview 가능 ───────────────────────────────────

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
    // LEARNING 카드가 세션 중 반복 등장하면 sessionDone이 sessionTarget을 초과할 수 있으므로 상한 보정
    val displayDone = sessionDone.coerceAtMost(sessionTarget)

    // 완주 모드 배지 + 기간 잔여 정보를 Scaffold 위에 오버레이하는 대신,
    // Column으로 감싸서 배지 → StudyScreenContent 순으로 배치한다.
    // StudyScreenContent는 자체 Scaffold를 포함하므로, 배지는 그 위에 독립 Row로 표시.
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 완주 모드 배지 ────────────────────────────────────────────────────
        CompletionModeBanner(modeEndAt = modeEndAt)

        // ── 핵심 학습 UI (기존 StudyScreenContent 그대로) ────────────────────
        // Modifier.weight(1f)로 배지 아래 남은 공간을 전부 차지하게 함
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

// ── 완주 모드 배지 ─────────────────────────────────────────────────────────────

@Composable
private fun CompletionModeBanner(modeEndAt: Long) {
    val now = System.currentTimeMillis()
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
        Text(
            text = if (remainingDays > 0) "D-$remainingDays" else "오늘 마감",
            color = CtPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Preview ──────────────────────────────────────────────────────────────────

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
