package com.example.anki_advanced

// =====================================================
// HomeScreen.kt — Stitch "홈 화면 (메뉴 버튼 위치 조정)"
// =====================================================
//
// 역할:
// - 덱 목록 표시 (각 덱마다 new/learn/review 카운트)
// - 오늘의 학습 진행률 표시
// - 덱 추가/삭제 기능
// - 학습 시작, 카드 관리, 덱 설정 화면으로 이동
//
// 주요 특징:
// - Google Stitch 디자인 시스템 기반
// - DisposableEffect로 onResume 등가 구현 (학습 후 복귀 시 카운트 갱신)
// - DropdownMenu로 덱별 메뉴 제공
// =====================================================

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

// =====================================================
// 홈 화면 전체 흐름도
// =====================================================
//
// 1. HomeActivity에서 HomeScreen()을 띄운다.
// 2. HomeScreen은 HomeViewModel의 상태를 구독한다.
//    - decks: 덱 목록
//    - todayProgress: 오늘 진행률
//    - todayStudied: 오늘 학습한 카드 수
//
// 3. 실제 데이터 준비는 HomeViewModel이 한다.
//    - DB에서 덱 목록을 읽는다.
//    - 각 덱의 new / learn / review 개수를 계산한다.
//    - 오늘 학습 수와 진행률도 같이 계산한다.
//
// 4. HomeScreen은 계산된 상태를 받아 화면만 그린다.
//    - WelcomeSection()        : 상단 인사 영역
//    - DailyProgressHeroCard() : 오늘 진행률 카드
//    - DeckSection()           : 덱 목록 영역
//    - QuickStatsSection()     : 하단 통계 카드
//
// 5. 사용자가 홈 화면에서 액션을 누르면
//    - 덱 카드 클릭           -> 학습 화면으로 이동
//    - 더보기 메뉴            -> 관리 / 설정 / 삭제
//    - 덱 추가 버튼           -> AddDeckDialog 표시
//    - 삭제 확인             -> DeleteDeckDialog 표시
//
// 6. 다이얼로그에서 실제 추가/삭제가 확정되면
//    - HomeScreen이 ViewModel.addDeck(), deleteDeck()를 호출한다.
//    - ViewModel이 DB를 수정한다.
//    - ViewModel이 다시 상태를 갱신한다.
//    - HomeScreen이 새 상태로 자동 재구성된다.
//
// 7. 학습 화면에서 다시 돌아오면
//    - DisposableEffect + ON_RESUME이 loadDecks()를 다시 호출한다.
//    - 그래서 홈 화면 카드 수와 진행률이 최신값으로 갱신된다.
//
// 레거시와 비교하면:
// - 예전: Activity + RecyclerView + Adapter + notifyDataSetChanged()
// - 지금: ViewModel 상태 + Compose 함수 + 상태 변경 시 자동 재구성
// =====================================================

// =====================================================
// 색상 — Stitch 디자인 토큰
// =====================================================
// 'Home' 접두사 = 홈 화면 전용 색상
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

// 덱 아이콘 배경색 순환
// 각 덱마다 다른 색상 조합을 자동으로 할당 (index % 4)
// Pair(배경색, 아이콘 색)
private val deckIconColors = listOf(
    Pair(HomeSecondaryContainer, HomePrimary),       // 보라 계열
    Pair(HomeTertiaryContainer,  HomeTertiary),      // 분홍 계열
    Pair(Color(0xFFDCF2DC),      Color(0xFF2E7D32)), // 초록 계열
    Pair(Color(0xFFFFF3CD),      Color(0xFFF57F17)), // 노랑 계열
)

// =====================================================
// 루트 화면 — ViewModel 진입점
// =====================================================
//
// 이 함수는 NavHost에서 호출되는 진입점
// navController와 viewModel 같은 외부 의존성을 여기서만 받음
//
// DisposableEffect로 onResume 등가 구현:
// - 학습 화면에서 돌아올 때마다 덱 카운트 갱신
// - Lifecycle.Event.ON_RESUME 감지
// HomeScreen 읽는 순서:
// 1. ViewModel 상태 구독
// 2. 화면 내부에서만 쓰는 UI 상태 선언
// 3. onResume 대응 갱신 연결
// 4. 추가/삭제 다이얼로그 조건부 표시
// 5. Scaffold 안에서 상단바, 진행 카드, 덱 목록, 하단 메뉴 배치
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 레거시 HomeActivity와 비교:
// - `LoadFromDbAndRefresh()` 역할은 HomeViewModel.loadDecks()로 이동했다.
// - HomeScreen은 더 이상 `items`, `adapter`, `notify...()`를 직접 다루지 않는다.
// - ViewModel 상태를 구독해서 화면만 그리는 역할만 맡는다.
fun HomeScreen(
    navController: NavController? = null,  // 화면 이동용 (null 허용 = Preview 대응)
    viewModel: HomeViewModel = viewModel() // viewModel()은 좌측의 타입을 보고 찾아줌
) {
    // ViewModel에서 상태를 구독 (StateFlow → State)
    // collectAsState()는 Flow를 Compose State로 변환
    // StateFlow 값이 바뀌면 자동으로 UI 재구성(recomposition)
    val decks         by viewModel.decks.collectAsState()          // 덱 목록
    val todayProgress by viewModel.todayProgress.collectAsState()  // 오늘 진행률 (0.0 ~ 1.0)
    val todayStudied  by viewModel.todayStudied.collectAsState()   // 오늘 학습한 카드 수

    // 다이얼로그 / 드롭다운 메뉴 상태 관리
    // remember = recomposition 시에도 값 유지
    var showAddDeckDialog  by remember { mutableStateOf(false) }   // 덱 추가 다이얼로그 표시 여부
    var deckToDelete       by remember { mutableStateOf<DeckUi?>(null) }  // 삭제할 덱 (null = 미표시)
    var expandedMenuDeckId by remember { mutableStateOf<Long?>(null) }    // 열린 메뉴의 덱 ID (null = 모두 닫힘)

    // LocalContext = 현재 Composable이 실행되는 Context를 가져옴
    // Intent 생성 시 필요 (DeckSettingActivity로 이동)
    val context = LocalContext.current

    // ── onResume 등가: 학습 화면에서 돌아올 때 카운트 갱신 ──
    //
    // DisposableEffect = Composable이 화면에 나타날 때/사라질 때 실행
    // lifecycleOwner = 현재 Composable의 생명주기 소유자
    val lifecycleOwner = LocalLifecycleOwner.current
    // 레거시 `onResume()` 갱신 로직을 여기로 옮긴 것이다.
    // 목적은 같고, Study 화면에서 돌아오면 DB 기반 카운트를 다시 읽는다.
    DisposableEffect(lifecycleOwner) {
        // LifecycleEventObserver = 생명주기 이벤트를 감지하는 옵저버
        val observer = LifecycleEventObserver { _, event ->
            // Lifecycle.Event.ON_RESUME = 화면이 다시 보일 때
            // (학습 화면에서 뒤로가기로 돌아왔을 때)
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadDecks()
        }
        // 옵저버 등록
        lifecycleOwner.lifecycle.addObserver(observer)

        // onDispose = Composable이 화면에서 사라질 때 실행 (cleanup)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── 덱 추가 다이얼로그 ──
    // showAddDeckDialog가 true일 때만 다이얼로그 표시
    if (showAddDeckDialog) {
        // 레거시 `showAddDeckDialog()`는 Activity에서 직접 다이얼로그를 만들었다.
        // 지금은 Boolean 상태값으로 다이얼로그 표시 여부만 제어한다.
        AddDeckDialog(
            onConfirm = { name ->
                viewModel.addDeck(name)      // ViewModel에 덱 추가 요청
                showAddDeckDialog = false    // 다이얼로그 닫기
            },
            onDismiss = { showAddDeckDialog = false }  // 취소 시 다이얼로그만 닫기
        )
    }

    // ── 덱 삭제 확인 다이얼로그 ──
    // deckToDelete가 null이 아닐 때만 다이얼로그 표시
    // let = null이 아닐 때만 블록 실행 (스마트 캐스팅)
    deckToDelete?.let { deck ->
        // 레거시 `showDeleteDeckDialog(deck)`와 같은 역할이다.
        // 선택된 덱을 state에 넣어두고, null이 아닐 때만 삭제 확인창을 그린다.
        DeleteDeckDialog(
            deckName = deck.name,
            onConfirm = {
                viewModel.deleteDeck(deck.id)  // ViewModel에 덱 삭제 요청
                deckToDelete = null             // 다이얼로그 닫기
            },
            onDismiss = { deckToDelete = null }  // 취소 시 다이얼로그만 닫기
        )
    }

    // ── Scaffold = Material Design 기본 레이아웃 구조 ──
    // topBar, bottomBar, content 영역으로 구성
    Scaffold(
        topBar = { HomeTopBar() },  // 상단 앱바
        bottomBar = {
            HomeBottomNav(
                onCreateClick = { showAddDeckDialog = true },  // 만들기 버튼
                onStatsClick  = { /* TODO: 통계 화면 */ },     // 통계 버튼
                onSettingsClick = { /* TODO: 전체 설정 */ }    // 설정 버튼
            )
        },
        containerColor = HomeSurface  // 배경색
    ) { innerPadding ->
        // innerPadding = topBar/bottomBar가 차지하는 공간
        // 이 영역을 피해서 content를 배치해야 함

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())  // 세로 스크롤 가능
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)  // 섹션 간 간격 28dp
        ) {
            Spacer(Modifier.height(4.dp))

            // 환영 섹션
            WelcomeSection()

            // 오늘의 진도 히어로 카드
            DailyProgressHeroCard(
                progress     = todayProgress,
                studiedCount = todayStudied,
                // 진행률 바 값은 여기서 `todayProgress`를 받아 DailyProgressHeroCard로 전달된다.
                // 즉 바가 변하는 흐름은 HomeViewModel 계산 -> todayProgress -> 이 파라미터 전달이다.
                onStartClick = {
                    // 이 빠른 시작 진입점은 레거시 HomeActivity에는 없던 UX다.
                    // 현재는 복습할 덱이 있으면 그 덱을 우선 골라 바로 학습 화면으로 보낸다.
                    // 카드가 있는 첫 번째 덱으로 학습 시작
                    // firstOrNull { 조건 } = 조건 만족하는 첫 항목 (없으면 null)
                    val firstDeck = decks.firstOrNull { it.newCount + it.learnCount + it.reviewCount > 0 }
                        ?: decks.firstOrNull()  // 학습할 카드 없으면 첫 번째 덱
                    firstDeck?.let {
                        // NavController로 학습 화면으로 이동
                        // 경로에 덱 ID와 이름 포함
                        navController?.navigate("study/${it.id}/${it.name}")
                    }
                }
            )

            // 덱 섹션
            DeckSection(
                decks         = decks,
                expandedMenuId = expandedMenuDeckId,
                onDeckClick   = { deck ->
                    // 레거시에서는 adapter 클릭으로 StudyActivity를 열었다.
                    // 지금은 NavController route 이동으로 같은 흐름을 만든다.
                    // 덱 클릭 → 학습 화면으로 이동
                    navController?.navigate("study/${deck.id}/${deck.name}")
                },
                onMoreClick   = { deck ->
                    // 레거시 `showDeckMoreMenu(anchor, deck)`는 눌린 View를 기준으로 팝업을 띄웠다.
                    // 지금은 열린 덱 id만 상태로 들고 있고, 그 상태로 DropdownMenu를 그린다.
                    // 더보기(점 3개) 클릭 → 해당 덱의 메뉴 열기
                    expandedMenuDeckId = deck.id
                },
                onMenuDismiss = {
                    // 메뉴 닫기
                    expandedMenuDeckId = null
                },
                onManageClick = { deck ->
                    // 레거시 action_manage와 대응되는 동작이다.
                    // 카드 관리 메뉴 클릭 → 카드 관리 화면으로 이동
                    expandedMenuDeckId = null
                    navController?.navigate("deckManage/${deck.id}/${deck.name}")
                },
                onSettingsClick = { deck ->
                    // 이 목적지는 아직 기존 Activity 화면을 그대로 사용한다.
                    // 즉 홈은 Compose로 바뀌었지만 설정 화면은 레거시 UI와 공존 중이다.
                    // 설정 메뉴 클릭 → 덱 설정 화면으로 이동 (XML Activity)
                    expandedMenuDeckId = null
                    val intent = Intent(context, DeckSettingActivity::class.java).apply {
                        putExtra("deck_id", deck.id)
                        putExtra("deckName", deck.name)
                    }
                    context.startActivity(intent)
                },
                onDeleteClick = { deck ->
                    // 레거시의 삭제 메뉴 클릭은 지금 "삭제 확인창을 열 상태로 변경"하는 방식으로 바뀌었다.
                    // 삭제 메뉴 클릭 → 삭제 확인 다이얼로그 표시
                    expandedMenuDeckId = null
                    deckToDelete = deck
                },
                onAddDeckClick = {
                    // 레거시 `btnAddDeck.setOnClickListener { showAddDeckDialog() }`와 대응된다.
                    // 새 덱 추가 카드 클릭 → 덱 추가 다이얼로그 표시
                    showAddDeckDialog = true
                }
            )

            // 빠른 통계 섹션 (연속 학습, 학습 중)
            QuickStatsSection()

            Spacer(Modifier.height(8.dp))
        }
    }
}

// =====================================================
// 상단 앱바 — 프로필, 타이틀, 검색
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar() {
    TopAppBar(
        // 좌측: 프로필 아이콘
        navigationIcon = {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = "프로필",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)  // 원형으로 자르기
                        .background(HomeSurfaceContainer),
                    tint = HomeOutline
                )
            }
        },
        // 중앙: 앱 이름
        // Box로 감싸서 fillMaxWidth + Center 정렬
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Anki Advanced",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = HomePrimary,
                    letterSpacing = (-0.5).sp  // 글자 간격 좁히기
                )
            }
        },
        // 우측: 검색 버튼
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

// =====================================================
// 환영 섹션 — "오늘의 학습" + "안녕하세요!"
// =====================================================
@Composable
private fun WelcomeSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 상단: 작은 레이블
        Text(
            text = "오늘의 학습",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = HomeOnSurfaceVariant
        )
        // 하단: 큰 인사말
        Text(
            text = "안녕하세요!",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = HomeOnSurface,
            letterSpacing = (-0.5).sp
        )
    }
}

// =====================================================
// 오늘 진도 히어로 카드 — 메인 진행률 표시
// =====================================================
//
// progress: 0.0 ~ 1.0 (0% ~ 100%)
// studiedCount: 오늘 학습한 카드 수
@Composable
private fun DailyProgressHeroCard(
    progress: Float,
    studiedCount: Int,
    onStartClick: () -> Unit
) {
    // Float → Int 백분율 변환
    val progressPct = (progress * 100).toInt()

    // 레거시 ViewHolder의 bind 결과물 한 줄을 Compose 함수로 옮긴 형태다.
    // XML item layout + findViewById + setText 흐름이 이 함수 내부 UI 선언으로 대체됐다.
    // DeckCard는 레거시 item XML + ViewHolder bind를 합쳐놓은 함수라고 보면 된다.
    // 즉 "덱 1개를 화면에 어떻게 보일지"를 여기서 전부 결정한다.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            // 그라데이션 배경 (좌→우)
            .background(Brush.linearGradient(colors = listOf(HomePrimary, HomePrimaryDim)))
            .padding(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // 상단: 레이블 + 백분율
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // "DAILY PROGRESS" 레이블
                Text(
                    text = "DAILY PROGRESS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,  // 글자 간격 넓히기
                    color = HomeOnPrimary.copy(alpha = 0.8f)  // 약간 투명
                )
                // 백분율 + 학습 카드 수
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 큰 백분율
                    Text(
                        text = "$progressPct%",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = HomeOnPrimary,
                        letterSpacing = (-1).sp  // 글자 간격 좁히기
                    )
                    // 완료 텍스트
                    Text(
                        text = "완료  (${studiedCount}장)",
                        fontSize = 16.sp,
                        color = HomeOnPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 8.dp)  // 베이스라인 정렬
                    )
                }
            }

            // 진행률 바
            LinearProgressIndicator(
                progress = { progress },  // 람다로 전달 (Compose 최신 API)
                // 진행률 바가 실제로 채워지는 부분이다.
                // 바로 위의 progress 파라미터 값에 따라 바 길이가 달라진다.
                // 즉 "진행률에 따라 바가 변하는 로직"은 progress 전달에 연결되어 있다.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = HomeOnPrimary,  // 진행 색상
                trackColor = HomeOnPrimary.copy(alpha = 0.2f),  // 배경 색상
                strokeCap = StrokeCap.Round  // 둥근 끝
            )

            // "학습 시작하기" 버튼
            TextButton(
                onClick = onStartClick,
                modifier = Modifier
                    .align(Alignment.End)  // 우측 정렬
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

// =====================================================
// 덱 섹션 — 덱 목록 + 새 덱 추가 카드
// =====================================================
@Composable
private fun DeckSection(
    decks: List<DeckUi>,           // 덱 목록
    expandedMenuId: Long?,          // 현재 열린 메뉴의 덱 ID
    onDeckClick: (DeckUi) -> Unit,  // 덱 클릭
    onMoreClick: (DeckUi) -> Unit,  // 더보기 버튼 클릭
    onMenuDismiss: () -> Unit,      // 메뉴 닫기
    onManageClick: (DeckUi) -> Unit,    // 카드 관리
    onSettingsClick: (DeckUi) -> Unit,  // 설정
    onDeleteClick: (DeckUi) -> Unit,    // 삭제
    onAddDeckClick: () -> Unit      // 새 덱 추가
) {
    // 레거시 RecyclerView + DeckAdapter가 하던 목록 표시 역할이 여기로 왔다.
    // adapter/view-holder 갱신 대신, 각 DeckUi를 바로 DeckCard로 매핑한다.
    // 여기서부터가 레거시 RecyclerView를 Compose 방식으로 바꾼 핵심 구간이다.
    // 예전에는 adapter + onBindViewHolder + notify...()가 목록 갱신을 맡았다.
    // 지금은 상태 리스트를 직접 순회해서 DeckCard()를 그리는 방식으로 단순화됐다.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 섹션 헤더
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
        }

        // 덱 목록
        // forEachIndexed = 인덱스와 항목을 동시에 사용
        // 레거시 RecyclerView의 "row 생성 + bind" 루프를 Compose에서는 이 순회가 대신한다.
        // adapter가 데이터를 꺼내 바인딩하던 대신, 여기서 각 deck을 바로 DeckCard에 넘긴다.
        decks.forEachIndexed { index, deck ->
            // 이 반복 1회가 레거시의 "row 하나 bind"에 해당한다.
            // deck 하나를 꺼내서 DeckCard 하나로 넘긴다고 보면 된다.
            // 아이콘 색상 선택 (순환)
            val (iconBg, iconTint) = deckIconColors[index % deckIconColors.size]
            DeckCard(
                deck             = deck,
                iconBgColor      = iconBg,
                iconTintColor    = iconTint,
                isMenuExpanded   = expandedMenuId == deck.id,  // 현재 덱의 메뉴가 열렸는지
                onDeckClick      = { onDeckClick(deck) },
                onMoreClick      = { onMoreClick(deck) },
                onMenuDismiss    = onMenuDismiss,
                onManageClick    = { onManageClick(deck) },
                onSettingsClick  = { onSettingsClick(deck) },
                onDeleteClick    = { onDeleteClick(deck) }
            )
        }

        // 새 덱 추가 카드 (점선 테두리)
        AddNewDeckCard(onClick = onAddDeckClick)
    }
}

// =====================================================
// 개별 덱 카드 — 아이콘, 이름, 통계, 메뉴
// =====================================================
@Composable
private fun DeckCard(
    deck: DeckUi,              // 덱 정보
    iconBgColor: Color,        // 아이콘 배경색
    iconTintColor: Color,      // 아이콘 글자색
    isMenuExpanded: Boolean,   // 메뉴 열림 상태
    onDeckClick: () -> Unit,   // 카드 전체 클릭
    onMoreClick: () -> Unit,   // 더보기 버튼 클릭
    onMenuDismiss: () -> Unit, // 메뉴 닫기
    onManageClick: () -> Unit, // 카드 관리
    onSettingsClick: () -> Unit,  // 설정
    onDeleteClick: () -> Unit  // 삭제
) {
    // 레거시 RecyclerView 한 줄 아이템에 해당하는 Compose 버전이다.
    // 카드 전체 클릭은 학습 시작, 오른쪽 메뉴는 덱 관련 액션을 연다.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(HomeSurfaceContainerLowest)
            .clickable { onDeckClick() }  // 카드 전체 클릭 시 학습 시작
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ── 헤더 행: 아이콘 + 이름 + 더보기 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 아이콘 (덱 이름의 첫 글자)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = deck.name.take(1).uppercase(),  // 첫 글자 대문자
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTintColor
                    )
                }

                Spacer(Modifier.width(12.dp))

                // 덱 이름 + 카드 남음
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deck.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomeOnSurface
                    )
                    Text(
                        text = "${deck.newCount + deck.learnCount + deck.reviewCount}장 남음",
                        fontSize = 13.sp,
                        color = HomeOnSurfaceVariant
                    )
                }

                // 더보기 버튼 + DropdownMenu
                Box {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "더보기",
                            tint = HomeOutline
                        )
                    }
                    // DropdownMenu = 팝업 메뉴
                    // expanded = 메뉴 표시 여부
                    // onDismissRequest = 메뉴 밖 클릭 시 호출
                    // 레거시 PopupMenu와 같은 역할이다.
                    // 차이는 XML 메뉴를 inflate하지 않고, 여기서 항목을 직접 선언한다는 점이다.
                    DropdownMenu(
                        expanded = isMenuExpanded,
                        onDismissRequest = onMenuDismiss
                    ) {
                        // 레거시 `menu_deck_more.xml` 항목들을 지금은 Compose 코드 안에 직접 적었다.
                        DropdownMenuItem(
                            text = { Text("카드 관리") },
                            onClick = onManageClick
                        )
                        DropdownMenuItem(
                            text = { Text("설정") },
                            onClick = onSettingsClick
                        )
                        DropdownMenuItem(
                            text = { Text("삭제", color = HomeError) },
                            onClick = onDeleteClick
                        )
                    }
                }
            }

            // ── 통계 행: 신규/학습 중/복습 ──
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

// 통계 칩 (신규/학습 중/복습)
@Composable
private fun DeckStatChip(
    label: String,       // 레이블 (예: "신규")
    count: Int,          // 카드 수
    countColor: Color,   // 카드 수 색상
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
        // 상단: 레이블
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = HomeOnSurfaceVariant
        )
        // 하단: 카드 수
        Text(
            text = count.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = countColor
        )
    }
}

// =====================================================
// 새 덱 추가 카드 (점선 테두리)
// =====================================================
@Composable
private fun AddNewDeckCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            // border = 테두리 (점선 효과는 alpha로 구현)
            .border(2.dp, HomeOutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // + 아이콘
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
            // 타이틀
            Text(
                "새로운 덱 만들기",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSurfaceVariant
            )
            // 설명
            Text(
                "새로운 단어장이나 학습 자료를 추가하세요",
                fontSize = 13.sp,
                color = HomeOutline,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =====================================================
// 빠른 통계 섹션 — 연속 학습, 학습 중
// =====================================================
@Composable
private fun QuickStatsSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 좌측: 연속 학습 카드
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeSecondaryContainer.copy(alpha = 0.4f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("🔥", fontSize = 24.sp)
            Text("오늘", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HomeOnSecondaryContainer)
            Text("연속 학습 중", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HomeOnSecondaryContainer.copy(alpha = 0.75f))
        }

        // 우측: 학습 중 카드
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeTertiaryContainer.copy(alpha = 0.25f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = HomeTertiary,
                modifier = Modifier.size(28.dp)
            )
            Text("학습 중", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HomeTertiary)
            Text("꾸준히 해봐요!", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HomeTertiary.copy(alpha = 0.75f))
        }
    }
}

// =====================================================
// 하단 네비게이션 바 — 홈/만들기/통계/설정
// =====================================================
@Composable
private fun HomeBottomNav(
    onCreateClick: () -> Unit,    // 만들기 버튼
    onStatsClick: () -> Unit,     // 통계 버튼
    onSettingsClick: () -> Unit   // 설정 버튼
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeSurfaceContainerLowest.copy(alpha = 0.95f))  // 약간 투명
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround  // 균등 분배
    ) {
        BottomNavItem(label = "홈",   emoji = "🏠", isActive = true,  onClick = {})
        BottomNavItem(label = "만들기", emoji = "➕", isActive = false, onClick = onCreateClick)
        BottomNavItem(label = "통계",  emoji = "📊", isActive = false, onClick = onStatsClick)
        BottomNavItem(label = "설정",  emoji = null, isActive = false, onClick = onSettingsClick, useIcon = true)
    }
}

// 하단 네비게이션 개별 아이템
@Composable
private fun BottomNavItem(
    label: String,        // 레이블 (예: "홈")
    emoji: String?,       // 이모지 (null이면 아이콘 사용)
    isActive: Boolean,    // 활성 상태 (현재 화면)
    onClick: () -> Unit,  // 클릭 이벤트
    useIcon: Boolean = false  // 아이콘 사용 여부
) {
    // 활성 상태에 따라 색상 변경
    val color = if (isActive) HomePrimary else HomeOnSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            // 활성 상태면 배경색 추가
            .background(if (isActive) HomePrimary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (useIcon) {
            // 아이콘 사용 (설정 버튼)
            Icon(
                Icons.Filled.Settings,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        } else {
            // 이모지 사용
            Text(emoji ?: "", fontSize = 22.sp)
        }
        // 레이블
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            color = color
        )
    }
}

// =====================================================
// 덱 추가 다이얼로그
// =====================================================
@Composable
private fun AddDeckDialog(
    onConfirm: (String) -> Unit,  // 확인 버튼 (덱 이름 전달)
    onDismiss: () -> Unit          // 취소 버튼
) {
    // 다이얼로그 내부 상태 (덱 이름 입력값)
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,  // 다이얼로그 밖 클릭 시
        title = { Text("덱 추가") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("덱 이름") },
                singleLine = true  // 한 줄만 입력
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onConfirm(name.trim())  // 공백 아니면 확인
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

// =====================================================
// 덱 삭제 확인 다이얼로그
// =====================================================
@Composable
private fun DeleteDeckDialog(
    deckName: String,             // 삭제할 덱 이름
    onConfirm: () -> Unit,        // 삭제 확인
    onDismiss: () -> Unit         // 취소
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

// =====================================================
// Preview — ViewModel 없이 UI만 미리보기
// =====================================================
//
// Preview는 실제 앱을 실행하지 않고 Android Studio에서
// UI를 즉시 확인할 수 있는 기능
//
// ViewModel이나 DB 없이 더미 데이터로 UI 테스트
@Preview(showBackground = true, widthDp = 390, heightDp = 844, name = "홈 화면 (메뉴 버튼 위치 조정)")
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        // Preview용 더미 UI (ViewModel 없이)
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
                Spacer(Modifier.height(72.dp)) // TopBar 공간

                // 환영 섹션
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("오늘의 학습", fontSize = 14.sp, color = HomeOnSurfaceVariant)
                    Text("안녕하세요!", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HomeOnSurface)
                }

                // 히어로 카드
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

                // 샘플 덱
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

                Spacer(Modifier.height(80.dp)) // BottomNav 공간
            }
        }
    }
}
