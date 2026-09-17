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

// 일반 모드(완주 모드가 아닌 기본) 학습 화면. 카드 플립 애니메이션, 채점 버튼, 되돌리기,
// 오늘의 진행률 바, 학습 완료 화면까지 전부 이 파일 안에 있다.

// ── 색상 팔레트 ──
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

// ── ViewModel과 연결되는 "진입점" 컴포저블 ────────────────────────────────────
// 이 함수만 ViewModel을 알고 있고, 실제 화면을 그리는 StudyScreenContent는
// ViewModel을 전혀 모르는 "순수 UI"로 분리돼 있어서 Preview로 바로 확인할 수 있다.
@Composable
fun StudyScreen(
    navController: NavController? = null,
    deckId: Long = -1L,
    deckName: String = "학습",
    viewModel: StudyViewModel = viewModel()
) {
    val uiState       by viewModel.uiState.collectAsState()
    val currentCard   by viewModel.currentCard.collectAsState()
    // undoStackSize가 0보다 크면(채점한 카드가 있으면) "↩ 되돌리기" 버튼을 보여준다.
    val undoStackSize by viewModel.undoStackSize.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val progress      by viewModel.progress.collectAsState()  // 오늘 완료 수 / 전체 카드 수

    var isFlipped by remember { mutableStateOf(false) }
    // currentCard가 바뀔 때마다(새 카드로 넘어갈 때마다) 카드가 뒷면으로 뒤집힌 채 시작하지 않게 초기화.
    LaunchedEffect(currentCard) { isFlipped = false }
    // deckId가 처음 정해질 때 딱 한 번 학습 세션을 시작.
    LaunchedEffect(deckId)      { viewModel.startStudy(deckId) }

    StudyScreenContent(
        uiState       = uiState,
        currentCard   = currentCard,
        undoStackSize = undoStackSize,  // 되돌리기 버튼 표시 여부 결정용
        isLoading     = isLoading,
        isFlipped     = isFlipped,
        doneToday     = progress.done,   // 오늘 완료한 채점 수
        totalToday    = progress.total,  // 완료 + 남은 카드 수 (학습 중 계속 갱신됨)
        onShowAnswer  = { isFlipped = true; viewModel.showAnswer() },
        onGrade       = { viewModel.applyGrade(it) },
        onUndo        = { viewModel.undoLast() },
        onBack        = { navController?.popBackStack() }
    )
}

// ── 순수 UI 부분 — ViewModel 없이 uiState 값만 보고 화면 구성을 분기함 ─────────
//
// 콜백들이 실제로 눌렸을 때 어떤 일이 일어나는지 한눈에 보기:
//
// "정답 보기" 버튼 → onShowAnswer() 호출
//   → { isFlipped = true; viewModel.showAnswer() }  (StudyScreen에서 연결)
//     → isFlipped = true → 카드 플립 애니메이션 시작
//     → viewModel.showAnswer() → uiState가 ANSWER로 바뀜 → 채점 버튼들이 나타남
//
// "↩ 되돌리기" 버튼 → onUndo() 호출
//   → { viewModel.undoLast() }
//     → DB에서 방금 채점 로그를 지우고 카드를 이전 상태로 복구
//     → currentCard, uiState가 그 결과로 자동 갱신됨
@Composable
fun StudyScreenContent(
    uiState: StudyUiState,
    currentCard: CardEntity?,
    undoStackSize: Int,
    isLoading: Boolean,
    isFlipped: Boolean,
    doneToday: Int,   // 진행률 바의 분자 (오늘 완료한 채점 수)
    totalToday: Int,  // 진행률 바의 분모 (완료 + 남은 카드 수)
    onShowAnswer: () -> Unit,
    onGrade: (Int) -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit
) {
    // [문법] val rotation by animateFloatAsState(targetValue = ..., animationSpec = tween(400))
    //   "목표값이 바뀌면 그 값까지 애니메이션으로 부드럽게 이동하는 Float 상태"를 만들어주는 함수.
    //   isFlipped가 false→true로 바뀌면 targetValue가 0f→180f로 바뀌고, rotation 값이
    //   400ms 동안 0에서 180까지 서서히 올라간다. 이 값을 그대로 카드 회전각으로 쓴다.
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "cardFlip"
    )

    // [문법] Scaffold(topBar = { ... }) { innerPadding -> ... }
    //   화면의 기본 뼈대(상단바, 본문 등)를 잡아주는 Material Design 컴포저블.
    //   본문 블록이 받는 innerPadding은 "상단바 높이만큼은 겹치지 않게 비워둬야 하는 여백"이다.
    Scaffold(
        topBar = {
            StudyTopBar(onBack = onBack)
        },
        containerColor = StBg
    ) { innerPadding ->
        when (uiState) {
            StudyUiState.DONE -> DoneContent(  // 학습 완료 화면
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onBack = onBack
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StudyProgressBar(
                    doneToday  = doneToday,
                    totalToday = totalToday,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                Spacer(Modifier.weight(1f))

                // 플래시카드 (앞면 ↔ 뒷면 플립 애니메이션)
                // [문법] Modifier.graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }
                //   graphicsLayer는 회전·확대·그림자 같은 그래픽 효과를 이 컴포저블에 적용하는 Modifier.
                //   rotationY는 Y축(세로축) 기준 회전각. cameraDistance를 키우면 회전할 때
                //   원근감(입체적으로 보이는 정도)이 더 자연스러워진다.
                //   rotation이 90도를 기준으로 앞면/뒷면을 바꿔 그린다 (실제로 두 면을 다 그려두고
                //   각도에 따라 어느 쪽을 보여줄지만 코드로 스위칭하는 방식).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(0.8f)
                        .graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }
                ) {
                    if (rotation <= 90f) {
                        FrontFace(card = currentCard)   // 0°~90° 구간: 앞면 표시
                    } else {                            // 90°~180° 구간: 뒷면 표시
                        BackFace(
                            card = currentCard,
                            // 뒷면은 부모가 이미 180도 돌아간 상태로 그려지므로, 여기서 다시 180도를
                            // 더 돌려 "거울에 비친 것처럼 글자가 뒤집혀 보이는" 문제를 상쇄시킨다.
                            modifier = Modifier.graphicsLayer { rotationY = 180f }
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                when (uiState) {
                    StudyUiState.QUESTION -> {
                        // 채점한 카드가 하나도 없으면(undoStackSize=0) 되돌리기 버튼 자체를 안 그림
                        if (undoStackSize > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            ) {
                                TextButton(
                                    onClick = onUndo,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Text("↩ 되돌리기", color = StOnSurfaceVar, fontSize = 25.sp)
                                }
                            }
                        }
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
                        // 정답을 본 뒤, 채점하기 전에도 되돌릴 수 있다 — QUESTION과 같은 onUndo 사용
                        if (undoStackSize > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            ) {
                                TextButton(
                                    onClick = onUndo,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Text("↩ 되돌리기", color = StOnSurfaceVar, fontSize = 25.sp)
                                }
                            }
                        }
                        GradeButtonRow(
                            isLoading = isLoading,
                            onGrade = onGrade,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    // [문법] Brush.verticalGradient(...)
                                    //   단색이 아니라 "위에서 아래로 점점 색이 바뀌는" 배경을 그리는 붓.
                                    //   여기서는 투명 → 배경색으로 자연스럽게 섞이도록 만든다.
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

// ── 상단 바: 왼쪽 프로필 아이콘 + 가운데 앱 이름 + 오른쪽 닫기(X) 버튼 ─────────
@Composable
private fun StudyTopBar(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // [문법] color.copy(alpha = 0.85f) → 같은 색인데 투명도(alpha)만 바꾼 새 Color를 만듦.
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
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "닫기", tint = StOnSurfaceVar)
            }
        }
    }
}

// ── 진행률 바: "오늘의 학습 · N / M" + 막대 그래프 ─────────────────────────────
@Composable
private fun StudyProgressBar(doneToday: Int, totalToday: Int, modifier: Modifier = Modifier) {
    // totalToday가 0이면(아직 로딩 전) 0으로 나누는 사고를 피하려고 0f로 고정.
    val fraction = if (totalToday > 0) doneToday.toFloat() / totalToday.toFloat() else 0f
    // fraction이 바뀌면 600ms 동안 이전 값에서 새 값으로 부드럽게 이어지는 애니메이션.
    val progress by animateFloatAsState(
        targetValue = fraction,
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
                Text("$doneToday / $totalToday", color = StPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

// ── 카드 앞면 ──
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
                    // [문법] card?.tags?.isNotBlank() == true
                    //   card가 null이거나 tags가 비어있으면 이 비교 전체가 false가 되어 else 문구를 씀.
                    //   ?. 체인 끝에 == true를 붙여 "null이 아니고 조건도 참일 때만"을 한 줄로 표현하는 관용구.
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

// ── 카드 뒷면 ──
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

// ── 채점 버튼 4개를 한 줄로 배치 ──
// 각 버튼 클릭 → { onGrade(n) } 람다 실행 → onGrade(Int) 콜백 호출
//   → StudyScreen에서 { viewModel.applyGrade(it) }로 연결돼 있어 최종적으로 ViewModel까지 전달된다.
// 이 컴포저블들은 ViewModel을 전혀 모르고 "콜백 람다"만 받아서 위로 전달하기 때문에
// ViewModel 없이도 Preview로 미리보기가 가능하다.
@Composable
private fun GradeButtonRow(isLoading: Boolean, onGrade: (Int) -> Unit, modifier: Modifier = Modifier) {
    // weight(1f)를 넷 다 줘서 Row 너비를 4등분, 각 버튼이 똑같은 폭을 차지하게 한다.
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradeButton("다시",   "1분", StAgain, isLoading, Modifier.weight(1f)) { onGrade(0) }
        GradeButton("어려움", "2일", StHard,  isLoading, Modifier.weight(1f)) { onGrade(1) }
        GradeButton("좋음",   "4일", StGood,  isLoading, Modifier.weight(1f)) { onGrade(2) }
        GradeButton("쉬움",   "7일", StEasy,  isLoading, Modifier.weight(1f)) { onGrade(3) }
    }
}

// 채점 버튼 하나. disabled가 true면(로딩 중이면) 클릭이 막힌다.
@Composable
private fun GradeButton(
    label: String, timeHint: String, color: Color,
    disabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !disabled,
        // 바깥에서 받은 modifier(weight 등)에 이어서 height를 체이닝. 체이닝 순서가
        // "먼저 weight로 폭을 잡고, 그다음 height로 높이를 고정"이라는 의미가 된다.
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StSurfaceLow,                            // 활성 상태 배경 (연한 회색)
            contentColor   = color,                                   // 활성 상태 텍스트 색 (난이도별 고유 색)
            disabledContainerColor = StSurfaceLow.copy(alpha = 0.5f), // 비활성 상태 배경 (반투명)
            disabledContentColor   = color.copy(alpha = 0.4f)         // 비활성 상태 텍스트 (흐리게)
            // enabled = false가 되면 Button이 자동으로 disabled~ 쪽 색상을 사용한다.
        ),
        shape = RoundedCornerShape(24.dp),
        // [문법] PaddingValues(4.dp) → 버튼 내부 콘텐츠와 버튼 테두리 사이의 여백을 지정하는 객체.
        contentPadding = PaddingValues(4.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // alpha를 낮춰서 보조 정보(예상 간격)임을 시각적으로 흐리게 표현
            Text(timeHint, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StOnSurfaceVar.copy(alpha = 0.6f))
            Spacer(Modifier.height(2.dp))
            // 난이도 고유 색을 그대로 써서 버튼끼리 색으로 구분되게 함
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.size(6.dp).background(color.copy(alpha = 0.25f), CircleShape))
        }
    }
}

// ── 학습 완료 화면 ──
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

// ── Preview: ViewModel 없이 세 가지 상태(질문/답/완료)를 각각 미리 확인 ──
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
        doneToday = 20, totalToday = 120,
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
        doneToday = 20, totalToday = 120,
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
        doneToday = 120, totalToday = 120,
        onShowAnswer = {}, onGrade = {}, onUndo = {}, onBack = {}
    )
}
