package com.example.anki_advanced

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

// ── 색상 ──
private val StBg            = Color(0xFFF6F6FA)
private val StSurfaceLow    = Color(0xFFF0F0F5)
private val StSurfaceHigh   = Color(0xFFE1E2E7)
private val StSurfaceHighest= Color(0xFFDBDDE2)
private val StCard          = Color(0xFFFFFFFF)
private val StPrimary       = Color(0xFF4A3AFF)
private val StPrimaryDim    = Color(0xFF3517EE)
private val StOnPrimary     = Color(0xFFF1EDFF)
private val StOnSurface     = Color(0xFF2D2F32)
private val StOnSurfaceVar  = Color(0xFF5A5B5F)
private val StAgain         = Color(0xFFE91E63)
private val StHard          = Color(0xFFFF9800)
private val StGood          = Color(0xFF2196F3)
private val StEasy          = Color(0xFF4CAF50)

// ─────────────────────────────────────────────
// ViewModel 진입점 — NavController, ViewModel 여기서만
// [변경] StudyActivity → StudyScreen + StudyScreenContent 분리
// 기존: Activity 한 클래스 안에 UI 코드(binding.*)와 로직(applyGrade 등) 혼재
// 변경: ViewModel 진입점(StudyScreen)과 순수 UI(StudyScreenContent)로 분리
// 이유: StudyScreenContent는 ViewModel 없이 Preview 가능
// ─────────────────────────────────────────────
@Composable
fun StudyScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    deckName: String = "학습",
    viewModel: StudyViewModel = viewModel()
) {
    val uiState       by viewModel.uiState.collectAsState()
    val currentCard   by viewModel.currentCard.collectAsState()
    // [변경] binding.btnUndo.visibility 직접 제어 → undoStackSize StateFlow 구독
    // 기존: undoStack.isEmpty() 확인 후 binding.btnUndo.visibility = VISIBLE/GONE 직접 설정
    // 변경: undoStackSize를 StateFlow로 노출 → Screen이 구독해서 조건부 렌더링
    //       undoStackSize > 0 이면 StudyTopBar에 언두 버튼(↩) 표시
    val undoStackSize by viewModel.undoStackSize.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()

    var isFlipped by remember { mutableStateOf(false) }
    // [변경] refreshCardText() 제거 → 카드 바뀔 때 isFlipped 자동 리셋
    // 기존: currentCard 교체 후 refreshCardText()로 binding.tvFront/tvBack.text 직접 갱신
    // 변경: currentCard StateFlow 변경 시 Compose가 자동 재구성, 플립 상태만 초기화
    LaunchedEffect(currentCard) { isFlipped = false }
    // [변경] Activity onCreate 직접 호출 → LaunchedEffect로 최초 1회 startStudy() 호출
    LaunchedEffect(deckId)      { viewModel.startStudy(deckId) }

    StudyScreenContent(
        uiState       = uiState,
        currentCard   = currentCard,
        undoStackSize = undoStackSize,  // 언두 버튼 표시 여부 결정용
        isLoading     = isLoading,
        isFlipped     = isFlipped,
        onShowAnswer  = { isFlipped = true; viewModel.showAnswer() },
        onGrade       = { viewModel.applyGrade(it) },
        // [변경] binding.btnUndo.setOnClickListener { undoLast() } → onUndo 람다 콜백
        // 기존: Activity에서 binding.btnUndo.setOnClickListener로 직접 등록
        // 변경: 람다로 주입 → StudyTopBar까지 onUndo로 전달되어 ↩ 버튼 클릭 시 호출
        onUndo        = { viewModel.undoLast() },
        onBack        = { navController?.popBackStack() }
    )
}

// ─────────────────────────────────────────────
// 순수 UI — ViewModel 없음, Preview 가능
// [변경] applyUiState() 제거 → when(uiState) 분기로 Compose가 자동 처리
// 기존: applyUiState()에서 모든 View의 visibility를 직접 제어
//       (tvFront, tvBack, btnShowAnswer, layoutGrade, btnUndo, layoutDone)
// 변경: uiState 값에 따라 Compose의 when 분기가 자동으로 UI 구성
// ─────────────────────────────────────────────
@Composable
fun StudyScreenContent(
    uiState: StudyUiState,
    currentCard: CardEntity?,
    undoStackSize: Int,
    isLoading: Boolean,
    isFlipped: Boolean,
    onShowAnswer: () -> Unit,
    onGrade: (Int) -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit
) {
//
//    isFlipped가 false → true로 변경되면
//    targetValue가 0f → 180f로 변경
//    400ms 동안 targetValue 값이 0에서 180으로 부드럽게 증가
//    리컴포지션마다 by키워드로 state.value 즉, rotation = targetValue
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "cardFlip"
    )

    Scaffold(
        topBar = {
            StudyTopBar(
                undoStackSize = undoStackSize,
                uiState = uiState,
                onUndo = onUndo,
                onBack = onBack
            )
        },
        containerColor = StBg
    ) { innerPadding ->
        when (uiState) {
            StudyUiState.DONE -> DoneContent(//학습완료 화면 띄우기
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onBack = onBack
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StudyProgressBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                Spacer(Modifier.weight(1f))

                // 플래시카드 (플립)
                // [변경] binding.tvFront/tvBack.visibility 토글 → Y축 회전 플립 애니메이션으로 교체
                // 기존: showCard()에서 binding.tvBack.visibility = VISIBLE
                // 변경: graphicsLayer { rotationY }로 카드 플립 구현
                //       rotation <= 90f 이면 앞면(FrontFace), 초과 시 뒷면(BackFace) 렌더링
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(0.8f)
                        .graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }
                ) {
                    if (rotation <= 90f) {
                        FrontFace(card = currentCard)   // 0° ~ 90° : 앞면 표시
                    } else {                            // 90° ~ 180° : 뒷면 표시
                        BackFace(
                            card = currentCard,
                            modifier = Modifier.graphicsLayer { rotationY = 180f }
                            // 뒷면은 이미 180° 회전된 상태에서 렌더링되므로
                            // 추가로 180° 더 돌려서 텍스트가 거울 반전되지 않도록 보정
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                when (uiState) {
                    StudyUiState.QUESTION -> {
                        Button(
                            onClick = onShowAnswer,
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .height(54.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StPrimary,
                                contentColor = StOnPrimary
                            ),
                            shape = CircleShape
                        ) {
                            Text("정답 보기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    StudyUiState.ANSWER -> {
                        GradeButtonRow(
                            isLoading = isLoading,
                            onGrade = onGrade,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, StBg),
                                        startY = 0f, endY = 60f
                                    )
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }
                    else -> {}
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = StPrimary,
                        trackColor = StSurfaceHigh
                    )
                }
            }
        }
    }
}

// ── 상단 바 ──
// [변경] 언두 버튼 처리 방식 전면 변경
// 기존: binding.btnUndo 는 레이아웃 XML에 별도 버튼으로 존재
//       applyUiState() 안에서 상태별로 visibility를 직접 설정:
//         QUESTION → undoStack.isEmpty() 이면 GONE, 아니면 VISIBLE
//         ANSWER   → undoStack.isEmpty() 이면 GONE, 아니면 VISIBLE
//         DONE     → 항상 GONE (완료 화면에서 언두 버튼 숨기기)
// 변경: StudyTopBar 안에 언두 버튼을 포함
//       undoStackSize > 0 && uiState != DONE 조건을 if문 하나로 처리
//       → 기존의 3가지 상태 × 2가지 조건 분기를 단순화
@Composable
private fun StudyTopBar(
    undoStackSize: Int,
    uiState: StudyUiState,
    onUndo: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(StBg.copy(alpha = 0.85f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFFD2D4DA), CircleShape)
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }

        Text(
            text = "Anki Advanced",
            color = StPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )

        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            // 언두 버튼: 스택에 항목이 있고 학습 완료 상태가 아닐 때만 표시
            // undoStackSize = 0 이면 아직 채점한 카드 없음 → 버튼 숨김
            // uiState = DONE 이면 학습 완료 화면 → 버튼 숨김 (되돌아갈 카드 없음)
            if (undoStackSize > 0 && uiState != StudyUiState.DONE) {
                // ↩ 버튼 클릭 → onUndo() → StudyScreen의 viewModel.undoLast() 호출
                // → DB 복구 + 이전 카드 화면에 표시
                TextButton(onClick = onUndo) {
                    Text("↩", color = StOnSurfaceVar, fontSize = 20.sp)
                }
            }
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "닫기", tint = StOnSurfaceVar)
            }
        }
    }
}

// ── 진행 바 ──
@Composable
private fun StudyProgressBar(modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = 0.166f,
        animationSpec = tween(600),
        label = "progress"
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("오늘의 학습", color = StOnSurfaceVar, fontSize = 11.sp)
                Text("20 / 120", color = StPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.width(120.dp).height(6.dp),
                color = StPrimary,
                trackColor = StSurfaceHighest
            )
        }
    }
}

// ── 앞면 ──
@Composable
private fun FrontFace(card: CardEntity?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = StCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth().height(4.dp)
                    .background(Brush.horizontalGradient(
                        listOf(StPrimary.copy(alpha = 0.2f), StPrimaryDim.copy(alpha = 0.2f))
                    ))
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (card?.tags?.isNotBlank() == true) card.tags.uppercase() else "VOCABULARY",
                    color = StOnSurfaceVar.copy(alpha = 0.6f),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = card?.front ?: "",
                    color = StOnSurface,
                    fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, lineHeight = 48.sp
                )
            }
        }
    }
}

// ── 뒷면 ──
@Composable
private fun BackFace(card: CardEntity?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = StCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth().height(4.dp)
                    .background(Brush.horizontalGradient(
                        listOf(StPrimary.copy(alpha = 0.2f), StPrimaryDim.copy(alpha = 0.2f))
                    ))
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (card?.tags?.isNotBlank() == true) {
                    Text(card.tags.uppercase(), color = StOnSurfaceVar.copy(alpha = 0.6f),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(12.dp))
                }
                Text(card?.front ?: "", color = StOnSurface,
                    fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, lineHeight = 40.sp)
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.width(48.dp).height(1.dp).background(StSurfaceHigh))
                Spacer(Modifier.height(16.dp))
                Text(card?.back ?: "", color = StPrimary,
                    fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, lineHeight = 30.sp)
            }
        }
    }
}

// ── 채점 버튼 행 ──
// [변경] setButtonsEnabled() 제거 → isLoading으로 버튼 비활성화
// 기존: setButtonsEnabled(false/true)로 btnAgain/btnHard/btnGood/btnEasy/btnShowAnswer/btnUndo 6개 직접 제어
// 변경: isLoading StateFlow를 각 버튼의 enabled 파라미터에 연결
@Composable
private fun GradeButtonRow(isLoading: Boolean, onGrade: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradeButton("다시",   "1분", StAgain, isLoading, Modifier.weight(1f)) { onGrade(0) }
        GradeButton("어려움", "2일", StHard,  isLoading, Modifier.weight(1f)) { onGrade(1) }
        GradeButton("좋음",   "4일", StGood,  isLoading, Modifier.weight(1f)) { onGrade(2) }
        GradeButton("쉬움",   "7일", StEasy,  isLoading, Modifier.weight(1f)) { onGrade(3) }
    }
}

@Composable
private fun GradeButton(
    label: String, timeHint: String, color: Color,
    disabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Button(
        onClick = onClick, enabled = !disabled,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StSurfaceLow, contentColor = color,
            disabledContainerColor = StSurfaceLow.copy(alpha = 0.5f),
            disabledContentColor = color.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(4.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(timeHint, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StOnSurfaceVar.copy(alpha = 0.6f))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.size(6.dp).background(color.copy(alpha = 0.25f), CircleShape))
        }
    }
}

// ── 학습 완료 ──
@Composable
private fun DoneContent(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("학습 완료!", color = StPrimary, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        Text("오늘의 학습을 모두 마쳤습니다.", color = StOnSurfaceVar, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onBack, modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StPrimary, contentColor = StOnPrimary),
            shape = CircleShape
        ) { Text("홈으로", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ─────────────────────────────────────────────
// Preview — ViewModel 없음
// ─────────────────────────────────────────────
@Preview(showBackground = true, backgroundColor = 0xFFF6F6FA)
@Composable
fun StudyScreenQuestionPreview() {
    StudyScreenContent(
        uiState = StudyUiState.QUESTION,
        currentCard = CardEntity(id = 1, deckId = 1, front = "Serendipity", back = "뜻밖의 행운", tags = "VOCABULARY",
            status = CARD_NEW, state = 0),
        undoStackSize = 0,
        isLoading = false,
        isFlipped = false,
        onShowAnswer = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F6FA)
@Composable
fun StudyScreenAnswerPreview() {
    StudyScreenContent(
        uiState = StudyUiState.ANSWER,
        currentCard = CardEntity(id = 1, deckId = 1, front = "Serendipity", back = "뜻밖의 행운", tags = "VOCABULARY",
            status = CARD_NEW, state = 0),
        undoStackSize = 1,
        isLoading = false,
        isFlipped = true,
        onShowAnswer = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F6FA)
@Composable
fun StudyScreenDonePreview() {
    StudyScreenContent(
        uiState = StudyUiState.DONE,
        currentCard = null,
        undoStackSize = 0,
        isLoading = false,
        isFlipped = false,
        onShowAnswer = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}
