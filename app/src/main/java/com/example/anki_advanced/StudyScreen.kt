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
    val undoStackSize by viewModel.undoStackSize.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()

    var isFlipped by remember { mutableStateOf(false) }
    LaunchedEffect(currentCard) { isFlipped = false }
    LaunchedEffect(deckId)      { viewModel.startStudy(deckId) }

    StudyScreenContent(
        uiState       = uiState,
        currentCard   = currentCard,
        undoStackSize = undoStackSize,
        isLoading     = isLoading,
        isFlipped     = isFlipped,
        onShowAnswer  = { isFlipped = true; viewModel.showAnswer() },
        onGrade       = { viewModel.applyGrade(it) },
        onUndo        = { viewModel.undoLast() },
        onBack        = { navController?.popBackStack() }
    )
}

// ─────────────────────────────────────────────
// 순수 UI — ViewModel 없음, Preview 가능
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
            StudyUiState.DONE -> DoneContent(
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(0.8f)
                        .graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }
                ) {
                    if (rotation <= 90f) {
                        FrontFace(card = currentCard)
                    } else {
                        BackFace(
                            card = currentCard,
                            modifier = Modifier.graphicsLayer { rotationY = 180f }
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
            if (undoStackSize > 0 && uiState != StudyUiState.DONE) {
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
