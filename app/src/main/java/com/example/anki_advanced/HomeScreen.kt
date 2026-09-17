package com.example.anki_advanced

// 앱을 켜면 가장 먼저 보이는 홈 화면.
// - 덱 목록과 각 덱의 new/learn/review 카드 수 표시
// - 오늘의 학습 진행률 표시
// - 덱 추가/삭제, 완주 모드 설정
// - 학습 화면 · 카드 관리 화면 · 덱 설정 화면으로 이동하는 진입점

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.anki_advanced.completion.CompletionModeConfigEntity
import java.util.Calendar

// ── 색상 팔레트 ──
private val HomePrimary              = Color(0xFF4330F9)  // 주요 강조색 (보라)
private val HomePrimaryDim           = Color(0xFF3517EE)  // 주요 강조색 (어두운 보라)
private val HomeOnPrimary            = Color(0xFFF1EDFF)  // 주요색 위의 텍스트
private val HomeSurface              = Color(0xFFF6F6FA)  // 배경색
private val HomeSurfaceContainerLowest = Color(0xFFFFFFFF)  // 카드 배경 (가장 밝음)
private val HomeSurfaceContainerLow  = Color(0xFFF0F0F5)  // 카드 배경 (밝음)
private val HomeSurfaceContainer     = Color(0xFFE7E8ED)  // 카드 배경
private val HomeOnSurface            = Color(0xFF2D2F32)  // 표면 위의 주요 텍스트
private val HomeOnSurfaceVariant     = Color(0xFF5A5B5F)  // 표면 위의 보조 텍스트
private val HomeOutline              = Color(0xFF75777A)  // 외곽선
private val HomeOutlineVariant       = Color(0xFFACADB1)  // 외곽선 (연함)
private val HomeSecondaryContainer   = Color(0xFFD8DAFF)  // 보조 컨테이너 (연한 보라)
private val HomeOnSecondaryContainer = Color(0xFF494C6A)  // 보조 컨테이너 위의 텍스트
private val HomeTertiary             = Color(0xFF983772)  // 3차 강조색 (분홍)
private val HomeTertiaryContainer    = Color(0xFFFFD8EE)  // 3차 컨테이너 (연한 분홍)
private val HomeError                = Color(0xFFB41340)  // 에러/삭제 (빨강)
private val HomeErrorDim             = Color(0xFFA70138)  // 에러 (어두운 빨강, 드롭다운 삭제 항목)

// 덱 아이콘 배경색을 덱마다 돌아가며 다르게 써주기 위한 팔레트.
// Pair(배경색, 아이콘/글자색) 4세트를 순환시켜서 index % 4로 골라 쓴다.
private val deckIconColors = listOf(
    Pair(HomeSecondaryContainer, HomePrimary),       // 보라 계열
    Pair(HomeTertiaryContainer,  HomeTertiary),      // 분홍 계열
    Pair(Color(0xFFDCF2DC),      Color(0xFF2E7D32)), // 초록 계열
    Pair(Color(0xFFFFF3CD),      Color(0xFFF57F17)), // 노랑 계열
)

// ── 루트 화면 — ViewModel과 연결되는 진입점 ────────────────────────────────
// NavHost가 "home" 경로로 이동할 때 호출하는 함수. navController와 viewModel 같은
// 외부 의존성은 여기서만 받고, 실제 화면 조립은 아래의 여러 컴포저블에 나눠 위임한다.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController? = null,  // 화면 이동용. null이면 Preview 등 독립 실행 상황
    viewModel: HomeViewModel = viewModel()
) {
    // ViewModel의 StateFlow들을 구독. 값이 바뀌면 이 화면이 자동으로 다시 그려진다.
    val decks         by viewModel.decks.collectAsState()          // 덱 목록
    val todayProgress by viewModel.todayProgress.collectAsState()  // 오늘 진행률 (0.0 ~ 1.0)
    val todayStudied  by viewModel.todayStudied.collectAsState()   // 오늘 학습한 카드 수
    val totalCards    by viewModel.totalCards.collectAsState()     // 전체 누적 학습 카드 수
    val streakDays    by viewModel.streakDays.collectAsState()     // 연속 학습 스트릭 (일)

    // 다이얼로그 / 드롭다운 메뉴가 지금 열려 있는지, 어떤 덱을 대상으로 하는지를 기억해두는 상태들.
    var showAddDeckDialog         by remember { mutableStateOf(false) }
    var deckToDelete              by remember { mutableStateOf<DeckUi?>(null) }
    var expandedMenuDeckId        by remember { mutableStateOf<Long?>(null) }
    var deckForCompletionSetup    by remember { mutableStateOf<DeckUi?>(null) }  // 완주 모드 설정 대상
    var deckToDeactivate          by remember { mutableStateOf<DeckUi?>(null) }  // 일반 모드 전환 확인 대상

    // [문법] LocalContext.current
    //   지금 이 컴포저블이 실행 중인 Android Context를 얻는 방법. Intent를 만들어
    //   다른 Activity(DeckSettingActivity)를 띄울 때처럼, Compose 밖의 안드로이드 API를
    //   써야 할 때 필요하다.
    val context = LocalContext.current

    // ── 다른 화면(학습 화면)에서 돌아왔을 때 카운트를 다시 불러오기 ──
    //
    // [문법] DisposableEffect(key) { ... onDispose { ... } }
    //   이 컴포저블이 화면에 나타날 때 블록 안의 코드가 실행되고, 화면에서 사라질 때는
    //   onDispose { } 안의 정리(cleanup) 코드가 실행된다. "구독을 걸고, 나중에 반드시 해제한다"는
    //   패턴을 안전하게 표현하는 표준 도구.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // [문법] LifecycleEventObserver { _, event -> ... }
        //   화면(Activity)의 생명주기 이벤트(시작/재개/일시정지 등)를 감지하는 콜백.
        val observer = LifecycleEventObserver { _, event ->
            // ON_RESUME = 이 화면이 다시 눈에 보이게 될 때 (예: 학습 화면에서 뒤로가기로 복귀)
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadDecks()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // 이 컴포저블이 화면에서 사라질 때 옵저버를 반드시 해제 (안 하면 메모리 누수 위험)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── 덱 추가 다이얼로그 ──
    if (showAddDeckDialog) {
        AddDeckDialog(
            onConfirm = { name ->
                viewModel.addDeck(name)      // ViewModel에 덱 추가 요청
                showAddDeckDialog = false    // 다이얼로그 닫기
            },
            onDismiss = { showAddDeckDialog = false }
        )
    }

    // ── 덱 삭제 확인 다이얼로그 ──
    // [문법] deckToDelete?.let { deck -> ... }
    //   deckToDelete가 null이 아닐 때만 블록을 실행하고, 그 값을 deck이라는 이름으로 쓸 수 있게 해준다.
    //   "값이 있을 때만 이걸 그려라"를 if-null-check 없이 짧게 표현하는 관용구.
    deckToDelete?.let { deck ->
        DeleteDeckDialog(
            deckName = deck.name,
            onConfirm = {
                viewModel.deleteDeck(deck.id)
                deckToDelete = null
            },
            onDismiss = { deckToDelete = null }
        )
    }

    // ── 완주 모드 설정 다이얼로그 ──
    deckForCompletionSetup?.let { deck ->
        com.example.anki_advanced.completion.CompletionModeSetupDialog(
            deckId = deck.id,
            deckName = deck.name,
            onConfirm = { deckForCompletionSetup = null },
            onDismiss = { deckForCompletionSetup = null }
        )
    }

    // ── 일반 모드 전환 확인 다이얼로그 ──
    deckToDeactivate?.let { deck ->
        AlertDialog(
            onDismissRequest = { deckToDeactivate = null },
            title = { Text("일반 모드로 전환", fontWeight = FontWeight.Bold) },
            text  = { Text("완주 모드를 종료하고 일반 모드로 전환합니다.\n현재까지의 학습 일정은 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deactivateCompletionMode(deck.id)
                    deckToDeactivate = null
                }) {
                    Text("전환", color = HomePrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deckToDeactivate = null }) {
                    Text("취소")
                }
            }
        )
    }

    // [문법] Scaffold(topBar = {...}, bottomBar = {...}) { innerPadding -> ... }
    //   상단바/하단바/본문을 정해진 자리에 배치해주는 Material Design 기본 뼈대.
    //   본문 블록이 받는 innerPadding은 상단바·하단바가 차지하는 만큼의 여백이라,
    //   본문 콘텐츠에 이 패딩을 줘야 두 바에 안 가려진다.
    Scaffold(
        topBar = { HomeTopBar() },
        bottomBar = {
            HomeBottomNav(
                onCreateClick = { showAddDeckDialog = true },
                onStatsClick  = { /* TODO: 통계 화면 */ },
                onSettingsClick = { /* TODO: 전체 설정 */ }
            )
        },
        containerColor = HomeSurface
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())  // 내용이 길면 세로 스크롤
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)  // 섹션 간 간격 28dp
        ) {
            Spacer(Modifier.height(4.dp))

            WelcomeSection()

            DailyProgressHeroCard(
                progress     = todayProgress,
                studiedCount = todayStudied,
                onStartClick = {
                    // "빠른 시작": 학습할 카드가 남은 덱 중 첫 번째를 우선 고르고,
                    // 없으면 그냥 목록의 첫 번째 덱으로 이동한다.
                    // [문법] list.firstOrNull { 조건 } → 조건을 만족하는 첫 원소, 없으면 null.
                    val firstDeck = decks.firstOrNull { it.newCount + it.learnCount + it.reviewCount > 0 }
                        ?: decks.firstOrNull()
                    firstDeck?.let {
                        navController?.navigate("study/${it.id}/${it.name}")
                    }
                }
            )

            DeckSection(
                decks          = decks,
                expandedMenuId = expandedMenuDeckId,
                onDeckClick    = { deck ->
                    // 완주 모드가 켜진 덱이면 완주 모드 학습 화면으로, 아니면 일반 학습 화면으로.
                    if (deck.completionModeEndAt != null) {
                        navController?.navigate("completionStudy/${deck.id}")
                    } else {
                        navController?.navigate("study/${deck.id}/${deck.name}")
                    }
                },
                onMoreClick    = { deck -> expandedMenuDeckId = deck.id },
                onMenuDismiss  = { expandedMenuDeckId = null },
                onManageClick  = { deck ->
                    expandedMenuDeckId = null
                    navController?.navigate("deckManage/${deck.id}/${deck.name}")
                },
                onSettingsClick = { deck ->
                    expandedMenuDeckId = null
                    // [문법] Intent(context, DeckSettingActivity::class.java).apply { putExtra(...) }
                    //   Compose가 아닌 전통적인 Activity(DeckSettingActivity)를 띄우기 위한 코드.
                    //   apply{}로 Intent를 만들자마자 extra 값들을 채워 넣는다.
                    val intent = Intent(context, DeckSettingActivity::class.java).apply {
                        putExtra("deck_id", deck.id)
                        putExtra("deckName", deck.name)
                    }
                    context.startActivity(intent)
                },
                onDeleteClick = { deck ->
                    expandedMenuDeckId = null
                    deckToDelete = deck
                },
                onCompletionSetupClick = { deck ->
                    expandedMenuDeckId = null
                    deckForCompletionSetup = deck
                },
                onDeactivateModeClick = { deck ->
                    expandedMenuDeckId = null
                    deckToDeactivate = deck
                },
                onAddDeckClick = { showAddDeckDialog = true }
            )

            QuickStatsSection(
                streakDays = streakDays,
                totalCards = totalCards
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 상단 앱바: 프로필 아이콘 + 앱 이름 + 검색 버튼 ──────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar() {
    TopAppBar(
        navigationIcon = {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = "프로필",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(HomeSurfaceContainer),
                    tint = HomeOutline
                )
            }
        },
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Anki Advanced",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = HomePrimary,
                    letterSpacing = (-0.5).sp  // 글자 간격을 살짝 좁혀 로고처럼 보이게
                )
            }
        },
        actions = {
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "검색",
                    tint = HomeOnSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = HomeSurfaceContainerLowest
        )
    )
}

// ── 환영 섹션: "오늘의 학습" + "안녕하세요!" ────────────────────────────────
@Composable
private fun WelcomeSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "오늘의 학습",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = HomeOnSurfaceVariant
        )
        Text(
            text = "안녕하세요!",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = HomeOnSurface,
            letterSpacing = (-0.5).sp
        )
    }
}

// ── 오늘 진도 히어로 카드: 보라색 그라데이션 배경의 메인 진행률 카드 ──────────
// progress: 0.0 ~ 1.0, studiedCount: 오늘 학습한 카드 수
@Composable
private fun DailyProgressHeroCard(
    progress: Float,
    studiedCount: Int,
    onStartClick: () -> Unit
) {
    val progressPct = (progress * 100).toInt()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            // [문법] Brush.linearGradient(colors = listOf(A, B))
            //   단색이 아니라 A색에서 B색으로 이어지는 배경을 그리는 붓.
            .background(Brush.linearGradient(colors = listOf(HomePrimary, HomePrimaryDim)))
            .padding(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "DAILY PROGRESS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,  // 글자 간격을 넓혀 라벨처럼 보이게
                    color = HomeOnPrimary.copy(alpha = 0.8f)
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$progressPct%",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = HomeOnPrimary,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text = "완료  (${studiedCount}장)",
                        fontSize = 16.sp,
                        color = HomeOnPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 8.dp)  // 큰 글자와 baseline을 맞추기 위한 여백
                    )
                }
            }

            // 진행률 바. progress 값이 HomeViewModel에서 계산돼 여기까지 그대로 전달된다.
            LinearProgressIndicator(
                progress = { progress },  // [문법] progress를 람다로 감싸는 최신 Compose API 형태
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = HomeOnPrimary,
                trackColor = HomeOnPrimary.copy(alpha = 0.2f),
                strokeCap = StrokeCap.Round,  // 바 끝을 둥글게
                gapSize = 0.dp,
                drawStopIndicator = {}   // 진행 바 끝의 점 표시(stop indicator) 제거
            )

            TextButton(
                onClick = onStartClick,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(50))
                    .background(HomeOnPrimary)
            ) {
                Text(
                    text = "학습 시작하기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = HomePrimary
                )
            }
        }
    }
}

// ── 덱 섹션: 덱 목록 + "새 덱 만들기" 카드 ──────────────────────────────────
@Composable
private fun DeckSection(
    decks: List<DeckUi>,
    expandedMenuId: Long?,
    onDeckClick: (DeckUi) -> Unit,
    onMoreClick: (DeckUi) -> Unit,
    onMenuDismiss: () -> Unit,
    onManageClick: (DeckUi) -> Unit,
    onSettingsClick: (DeckUi) -> Unit,
    onDeleteClick: (DeckUi) -> Unit,
    onCompletionSetupClick: (DeckUi) -> Unit,  // 완주 모드 설정
    onDeactivateModeClick: (DeckUi) -> Unit,   // 일반 모드 전환
    onAddDeckClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "학습 덱 (Decks)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSurface
            )
            TextButton(onClick = {}) {
                Text(
                    text = "모두 보기",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HomePrimary
                )
            }
        }

        // [문법] list.forEachIndexed { index, item -> ... }
        //   원소와 함께 "몇 번째인지(index)"도 같이 받는 반복문. 여기서는 덱마다 다른
        //   아이콘 색을 순환시켜 쓰기 위해 index가 필요하다.
        decks.forEachIndexed { index, deck ->
            // index를 4로 나눈 나머지로 색상 팔레트를 순환시킨다 (0,1,2,3,0,1,2,3...)
            val (iconBg, iconTint) = deckIconColors[index % deckIconColors.size]
            DeckCard(
                deck                   = deck,
                iconBgColor            = iconBg,
                iconTintColor          = iconTint,
                isMenuExpanded         = expandedMenuId == deck.id,
                onDeckClick            = { onDeckClick(deck) },
                onMoreClick            = { onMoreClick(deck) },
                onMenuDismiss          = onMenuDismiss,
                onManageClick          = { onManageClick(deck) },
                onSettingsClick        = { onSettingsClick(deck) },
                onDeleteClick          = { onDeleteClick(deck) },
                onCompletionSetupClick = { onCompletionSetupClick(deck) },
                onDeactivateModeClick  = { onDeactivateModeClick(deck) }
            )
        }

        // 목록 맨 아래 "새 덱 만들기" 카드 (점선 느낌의 테두리)
        AddNewDeckCard(onClick = onAddDeckClick)
    }
}

// ── 개별 덱 카드: 아이콘 + 이름 + 통계(신규/학습중/복습) + 더보기 메뉴 ────────
@Composable
private fun DeckCard(
    deck: DeckUi,
    iconBgColor: Color,
    iconTintColor: Color,
    isMenuExpanded: Boolean,
    onDeckClick: () -> Unit,
    onMoreClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onManageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCompletionSetupClick: () -> Unit,
    onDeactivateModeClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(HomeSurfaceContainerLowest)
            .clickable { onDeckClick() }  // 카드 전체를 누르면 학습 시작
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ── 헤더 행: 아이콘 + 이름 + 더보기 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    // [문법] "abc".take(1) → 문자열 맨 앞 1글자만 잘라내기.
                    Text(
                        text = deck.name.take(1).uppercase(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTintColor
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deck.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomeOnSurface
                    )
                    // 완주 모드가 켜진 덱이면 "N일 플랜" 배지와 만기일을 추가로 보여준다.
                    if (deck.completionModeEndAt != null && deck.completionTargetDays != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(iconTintColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = planLabel(deck.completionTargetDays),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = iconTintColor
                                )
                            }
                            Text(
                                text = "만기: ${formatDate(deck.completionModeEndAt)}",
                                fontSize = 11.sp,
                                color = HomeOnSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "마지막 학습: ${relativeTime(deck.lastStudiedAt)}",
                        fontSize = 12.sp,
                        color = HomeOnSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                // 더보기(⋮) 버튼 + 눌렀을 때 뜨는 드롭다운 메뉴
                Box {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "더보기",
                            tint = HomeOutline
                        )
                    }
                    // [문법] DropdownMenu(expanded = ..., onDismissRequest = ...) { 항목들 }
                    //   expanded가 true일 때만 화면에 나타나는 팝업 메뉴.
                    //   onDismissRequest는 메뉴 바깥을 탭했을 때 "닫아달라"고 알려주는 콜백이다.
                    DropdownMenu(
                        expanded = isMenuExpanded,
                        onDismissRequest = onMenuDismiss,
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = HomeSurfaceContainerLowest,
                        shadowElevation = 8.dp
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "카드 관리",
                                    color = HomeOnSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = HomeOutline,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = onManageClick,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "설정",
                                    color = HomeOnSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = null,
                                    tint = HomeOutline,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = onSettingsClick,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (deck.completionModeEndAt != null) "완주 모드 수정" else "완주 모드 설정",
                                    color = HomePrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = HomePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = onCompletionSetupClick,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                        )
                        // 완주 모드가 켜진 덱에만 "일반 모드로 전환" 메뉴를 추가로 보여준다.
                        if (deck.completionModeEndAt != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "일반 모드로 전환",
                                        color = HomeOnSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = HomeOnSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = onDeactivateModeClick,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = HomeSurfaceContainer
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "삭제",
                                    color = HomeErrorDim,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = HomeErrorDim,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = onDeleteClick,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // ── 통계 행: 신규 / 학습 중 / 복습 카드 수 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeckStatChip("신규",   count = deck.newCount,    countColor = HomePrimary,  modifier = Modifier.weight(1f))
                DeckStatChip("학습 중", count = deck.learnCount,  countColor = HomeTertiary, modifier = Modifier.weight(1f))
                DeckStatChip("복습",   count = deck.reviewCount, countColor = HomeError,    modifier = Modifier.weight(1f))
            }
        }
    }
}

// 목표 기간(일)을 사람이 읽기 좋은 라벨로 변환. 28일 이상이면 "개월" 단위로 반올림 없이 나눈다.
private fun planLabel(days: Int): String = when {
    days >= 28 -> "${days / 30}개월 플랜"
    else       -> "${days}일 플랜"
}

// ms를 "YYYY.MM.DD" 형식 문자열로 변환.
// [문법] "%d.%02d.%02d".format(...) → C언어 스타일의 서식 문자열.
//   %d는 그냥 정수, %02d는 "두 자리가 안 되면 0으로 채워라"(예: 3 → "03")는 뜻.
private fun formatDate(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return "%d.%02d.%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
}

// 마지막 학습 시각을 "X분 전" / "X시간 전" / "어제" / "X일 전" 같은 상대 시간 문자열로 변환.
private fun relativeTime(lastStudiedAt: Long?): String {
    if (lastStudiedAt == null) return "아직 없음"
    val diffMs = System.currentTimeMillis() - lastStudiedAt
    val diffMin  = diffMs / 60_000
    val diffHour = diffMs / 3_600_000
    val diffDay  = diffMs / 86_400_000
    return when {
        diffMin < 1   -> "방금 전"
        diffMin < 60  -> "${diffMin}분 전"
        diffHour < 24 -> "${diffHour}시간 전"
        diffDay == 1L -> "어제"
        else          -> "${diffDay}일 전"
    }
}

// 덱 카드 안의 작은 통계 칩(신규/학습 중/복습 중 하나).
@Composable
private fun DeckStatChip(
    label: String,
    count: Int,
    countColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(HomeSurfaceContainerLow)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = HomeOnSurfaceVariant
        )
        Text(
            text = count.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = countColor
        )
    }
}

// ── "새로운 덱 만들기" 카드 (점선 느낌의 테두리) ────────────────────────────
@Composable
private fun AddNewDeckCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(2.dp, HomeOutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(HomeSurfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "추가",
                    tint = HomeOutline,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "새로운 덱 만들기",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSurfaceVariant
            )
            Text(
                "새로운 단어장이나 학습 자료를 추가하세요",
                fontSize = 13.sp,
                color = HomeOutline,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── 빠른 통계 섹션: 연속 학습 스트릭 + 총 암기한 카드 ───────────────────────
@Composable
private fun QuickStatsSection(
    streakDays: Int,
    totalCards: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 좌측: 연속 학습 스트릭
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeSecondaryContainer.copy(alpha = 0.5f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = HomeOnSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "${streakDays}일",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSecondaryContainer
            )
            Text(
                text = "연속 학습 스트릭",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = HomeOnSecondaryContainer.copy(alpha = 0.7f)
            )
        }

        // 우측: 총 암기한 카드
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeTertiaryContainer.copy(alpha = 0.5f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = HomeTertiary,
                modifier = Modifier.size(28.dp)
            )
            // [문법] "%,d".format(totalCards) → 천 단위마다 콤마(,)를 넣어주는 서식 (예: 12345 → "12,345")
            Text(
                text = "%,d".format(totalCards),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = HomeTertiary
            )
            Text(
                text = "총 암기한 카드",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = HomeTertiary.copy(alpha = 0.7f)
            )
        }
    }
}

// ── 하단 내비게이션 바: 홈 / 만들기 / 통계 / 설정 ───────────────────────────
@Composable
private fun HomeBottomNav(
    onCreateClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeSurfaceContainerLowest.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        BottomNavItem(label = "홈",    icon = Icons.Filled.Home,     isActive = true,  onClick = {})
        BottomNavItem(label = "만들기", icon = Icons.Filled.Add,      isActive = false, onClick = onCreateClick)
        BottomNavItem(label = "통계",  icon = Icons.Filled.Star,     isActive = false, onClick = onStatsClick)
        BottomNavItem(label = "설정",  icon = Icons.Filled.Settings, isActive = false, onClick = onSettingsClick)
    }
}

// 하단 내비게이션의 아이템 하나 (아이콘 + 라벨).
@Composable
private fun BottomNavItem(
    label: String,
    icon: ImageVector,     // [문법] ImageVector: 벡터 아이콘(Icons.Filled.* 등)을 담는 타입
    isActive: Boolean,     // 지금 이 화면이 활성 탭인지
    onClick: () -> Unit
) {
    val color = if (isActive) HomePrimary else HomeOnSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) HomeSecondaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            color = color
        )
    }
}

// ── 덱 추가 다이얼로그 ──────────────────────────────────────────────────
@Composable
private fun AddDeckDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 다이얼로그가 열려있는 동안만 유지되는, 입력 중인 덱 이름.
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("덱 추가") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("덱 이름") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onConfirm(name.trim())  // 공백만 입력했으면 무시
                }
            ) {
                Text("추가", color = HomePrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

// ── 덱 삭제 확인 다이얼로그 ──────────────────────────────────────────────
@Composable
private fun DeleteDeckDialog(
    deckName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("덱 삭제") },
        text = { Text("정말 삭제하시겠습니까?\n\n$deckName") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("삭제", color = HomeError, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

// ── 완주 모드 설정 다이얼로그 (구버전) ────────────────────────────────────
// 참고: 실제로 화면에서 쓰이는 완주 모드 설정 UI는 completion 패키지의
// CompletionModeSetupDialog(캘린더로 날짜를 고르는 버전)이다. 이 함수는 프리셋 일수(7/14/30/60/90일)를
// 라디오 버튼으로 고르는 이전 버전으로, 현재 이 파일 안에서는 호출되는 곳이 없다.
@Composable
private fun CompletionModeSetupDialog(
    deckName: String,
    currentEndAt: Long?,   // 기존 설정이 있으면 null이 아님
    onConfirm: (targetDays: Int, windowStart: Int, windowEnd: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(7, 14, 30, 60, 90)
    var selectedDays by remember { mutableStateOf(presets[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (currentEndAt != null) "완주 모드 수정" else "완주 모드 설정",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = deckName,
                    fontSize = 13.sp,
                    color = HomeOnSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "목표 기간을 선택하세요",
                    fontSize = 13.sp,
                    color = HomeOnSurfaceVariant
                )
                // 기간 프리셋을 라디오 버튼 목록으로 나열
                presets.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedDays == days) HomePrimary.copy(alpha = 0.08f)
                                else Color.Transparent
                            )
                            // [문법] Modifier.selectable(selected = ..., onClick = ...)
                            //   RadioButton과 짝을 이뤄 "라디오 그룹의 항목 하나"를 표현할 때 쓰는 Modifier.
                            //   행 전체를 눌러도 선택되도록 라디오 버튼뿐 아니라 Row에도 붙여둔다.
                            .selectable(
                                selected = selectedDays == days,
                                onClick = { selectedDays = days }
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = planLabel(days),
                            fontSize = 14.sp,
                            fontWeight = if (selectedDays == days) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedDays == days) HomePrimary else HomeOnSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedDays, 0, 24) }) {
                Text(
                    if (currentEndAt != null) "수정" else "시작",
                    color = HomePrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
        containerColor = HomeSurfaceContainerLowest,
        shape = RoundedCornerShape(24.dp)
    )
}

// ── Preview: ViewModel 없이 더미 데이터로 화면 모양만 미리 확인 ──────────────
@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "홈 화면 (메뉴 버튼 위치 조정)")
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HomeSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Spacer(Modifier.height(72.dp)) // TopBar가 차지할 공간만큼 미리 비워둠

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("오늘의 학습", fontSize = 14.sp, color = HomeOnSurfaceVariant)
                    Text("안녕하세요!", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HomeOnSurface)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(HomePrimary, HomePrimaryDim)))
                        .padding(28.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("DAILY PROGRESS", fontSize = 11.sp, color = HomeOnPrimary.copy(0.8f), letterSpacing = 2.sp)
                        Text("85%", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = HomeOnPrimary)
                        LinearProgressIndicator(
                            progress = { 0.85f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = HomeOnPrimary,
                            trackColor = HomeOnPrimary.copy(0.2f)
                        )
                    }
                }

                // 샘플 덱 2개를 하드코딩해서 카드 모양을 미리 보여준다.
                // [문법] listOf("이름" to Triple(a, b, c)).forEachIndexed { i, (name, counts) -> ... }
                //   "a" to b는 Pair(a, b)를 만드는 짧은 표기. Triple(a, b, c)는 값 3개를 묶는 타입.
                //   forEachIndexed의 람다 파라미터 (name, counts)는 Pair를 "구조 분해"해서
                //   한 번에 두 변수로 받는 문법 — component1(), component2()를 자동으로 호출해준다.
                listOf("JLPT N1 어휘" to Triple(12, 45, 89), "데이터 사이언스" to Triple(5, 18, 32))
                    .forEachIndexed { i, (name, counts) ->
                        val (bg, tint) = deckIconColors[i]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(HomeSurfaceContainerLowest)
                                .padding(20.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg),
                                        Alignment.Center
                                    ) { Text(name.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tint) }
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HomeOnSurface)
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                                    DeckStatChip("신규",   counts.first,  HomePrimary,  Modifier.weight(1f))
                                    DeckStatChip("학습 중", counts.second, HomeTertiary, Modifier.weight(1f))
                                    DeckStatChip("복습",   counts.third,  HomeError,    Modifier.weight(1f))
                                }
                            }
                        }
                    }

                Spacer(Modifier.height(80.dp)) // BottomNav가 차지할 공간만큼 미리 비워둠
            }
        }
    }
}
