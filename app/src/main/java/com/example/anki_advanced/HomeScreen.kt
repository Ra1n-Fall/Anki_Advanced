package com.example.anki_advanced

// =====================================================
// HomeScreen.kt — Stitch "홈 화면 (메뉴 버튼 위치 조정)"
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
// 색상 — Stitch 디자인 토큰
// =====================================================
private val HomePrimary              = Color(0xFF4330F9)
private val HomePrimaryDim           = Color(0xFF3517EE)
private val HomeOnPrimary            = Color(0xFFF1EDFF)
private val HomeSurface              = Color(0xFFF6F6FA)
private val HomeSurfaceContainerLowest = Color(0xFFFFFFFF)
private val HomeSurfaceContainerLow  = Color(0xFFF0F0F5)
private val HomeSurfaceContainer     = Color(0xFFE7E8ED)
private val HomeOnSurface            = Color(0xFF2D2F32)
private val HomeOnSurfaceVariant     = Color(0xFF5A5B5F)
private val HomeOutline              = Color(0xFF75777A)
private val HomeOutlineVariant       = Color(0xFFACADB1)
private val HomeSecondaryContainer   = Color(0xFFD8DAFF)
private val HomeOnSecondaryContainer = Color(0xFF494C6A)
private val HomeTertiary             = Color(0xFF983772)
private val HomeTertiaryContainer    = Color(0xFFFFD8EE)
private val HomeError                = Color(0xFFB41340)

// 덱 아이콘 배경색 순환
private val deckIconColors = listOf(
    Pair(HomeSecondaryContainer, HomePrimary),
    Pair(HomeTertiaryContainer,  HomeTertiary),
    Pair(Color(0xFFDCF2DC),      Color(0xFF2E7D32)),
    Pair(Color(0xFFFFF3CD),      Color(0xFFF57F17)),
)

// =====================================================
// 루트 화면
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController? = null,
    viewModel: HomeViewModel = viewModel()
) {
    val decks         by viewModel.decks.collectAsState()
    val todayProgress by viewModel.todayProgress.collectAsState()
    val todayStudied  by viewModel.todayStudied.collectAsState()

    // 다이얼로그 / 드롭다운 상태
    var showAddDeckDialog  by remember { mutableStateOf(false) }
    var deckToDelete       by remember { mutableStateOf<DeckUi?>(null) }
    var expandedMenuDeckId by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current

    // onResume 등가: 학습 화면에서 돌아올 때 카운트 갱신
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadDecks()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 덱 추가 다이얼로그 ──
    if (showAddDeckDialog) {
        AddDeckDialog(
            onConfirm = { name ->
                viewModel.addDeck(name)
                showAddDeckDialog = false
            },
            onDismiss = { showAddDeckDialog = false }
        )
    }

    // ── 덱 삭제 확인 다이얼로그 ──
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            WelcomeSection()

            DailyProgressHeroCard(
                progress     = todayProgress,
                studiedCount = todayStudied,
                onStartClick = {
                    // 카드가 있는 첫 번째 덱으로 학습 시작
                    val firstDeck = decks.firstOrNull { it.newCount + it.learnCount + it.reviewCount > 0 }
                        ?: decks.firstOrNull()
                    firstDeck?.let {
                        navController?.navigate("study/${it.id}/${it.name}")
                    }
                }
            )

            DeckSection(
                decks         = decks,
                expandedMenuId = expandedMenuDeckId,
                onDeckClick   = { deck ->
                    navController?.navigate("study/${deck.id}/${deck.name}")
                },
                onMoreClick   = { deck -> expandedMenuDeckId = deck.id },
                onMenuDismiss = { expandedMenuDeckId = null },
                onManageClick = { deck ->
                    expandedMenuDeckId = null
                    navController?.navigate("deckManage/${deck.id}/${deck.name}")
                },
                onSettingsClick = { deck ->
                    expandedMenuDeckId = null
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
                onAddDeckClick = { showAddDeckDialog = true }
            )

            QuickStatsSection()

            Spacer(Modifier.height(8.dp))
        }
    }
}

// =====================================================
// 상단 앱바
// =====================================================
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
                    letterSpacing = (-0.5).sp
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

// =====================================================
// 환영 섹션
// =====================================================
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

// =====================================================
// 오늘 진도 히어로 카드
// =====================================================
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
            .background(Brush.linearGradient(colors = listOf(HomePrimary, HomePrimaryDim)))
            .padding(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "DAILY PROGRESS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
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
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = HomeOnPrimary,
                trackColor = HomeOnPrimary.copy(alpha = 0.2f),
                strokeCap = StrokeCap.Round
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

// =====================================================
// 덱 섹션
// =====================================================
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
        }

        decks.forEachIndexed { index, deck ->
            val (iconBg, iconTint) = deckIconColors[index % deckIconColors.size]
            DeckCard(
                deck             = deck,
                iconBgColor      = iconBg,
                iconTintColor    = iconTint,
                isMenuExpanded   = expandedMenuId == deck.id,
                onDeckClick      = { onDeckClick(deck) },
                onMoreClick      = { onMoreClick(deck) },
                onMenuDismiss    = onMenuDismiss,
                onManageClick    = { onManageClick(deck) },
                onSettingsClick  = { onSettingsClick(deck) },
                onDeleteClick    = { onDeleteClick(deck) }
            )
        }

        AddNewDeckCard(onClick = onAddDeckClick)
    }
}

// =====================================================
// 개별 덱 카드
// =====================================================
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
    onDeleteClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(HomeSurfaceContainerLowest)
            .clickable { onDeckClick() }
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 헤더 행
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 아이콘 (덱 이름 첫 글자)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = deck.name.take(1).uppercase(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTintColor
                    )
                }

                Spacer(Modifier.width(12.dp))

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
                    DropdownMenu(
                        expanded = isMenuExpanded,
                        onDismissRequest = onMenuDismiss
                    ) {
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

            // 통계 행
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeckStatChip(label = "신규",   count = deck.newCount,    countColor = HomePrimary,  modifier = Modifier.weight(1f))
                DeckStatChip(label = "학습 중", count = deck.learnCount,  countColor = HomeTertiary, modifier = Modifier.weight(1f))
                DeckStatChip(label = "복습",   count = deck.reviewCount, countColor = HomeError,    modifier = Modifier.weight(1f))
            }
        }
    }
}

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
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HomeOnSurfaceVariant)
        Text(text = count.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = countColor)
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
                Icon(Icons.Filled.Add, contentDescription = "추가", tint = HomeOutline, modifier = Modifier.size(24.dp))
            }
            Text("새로운 덱 만들기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HomeOnSurfaceVariant)
            Text("새로운 단어장이나 학습 자료를 추가하세요", fontSize = 13.sp, color = HomeOutline, textAlign = TextAlign.Center)
        }
    }
}

// =====================================================
// 빠른 통계 섹션
// =====================================================
@Composable
private fun QuickStatsSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeTertiaryContainer.copy(alpha = 0.25f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = HomeTertiary, modifier = Modifier.size(28.dp))
            Text("학습 중", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HomeTertiary)
            Text("꾸준히 해봐요!", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HomeTertiary.copy(alpha = 0.75f))
        }
    }
}

// =====================================================
// 하단 네비게이션 바
// =====================================================
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
        BottomNavItem(label = "홈",   emoji = "🏠", isActive = true,  onClick = {})
        BottomNavItem(label = "만들기", emoji = "➕", isActive = false, onClick = onCreateClick)
        BottomNavItem(label = "통계",  emoji = "📊", isActive = false, onClick = onStatsClick)
        BottomNavItem(label = "설정",  emoji = null, isActive = false, onClick = onSettingsClick, useIcon = true)
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    emoji: String?,
    isActive: Boolean,
    onClick: () -> Unit,
    useIcon: Boolean = false
) {
    val color = if (isActive) HomePrimary else HomeOnSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) HomePrimary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (useIcon) {
            Icon(Icons.Filled.Settings, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        } else {
            Text(emoji ?: "", fontSize = 22.sp)
        }
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
private fun AddDeckDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }
            ) {
                Text("추가", color = HomePrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// =====================================================
// 덱 삭제 확인 다이얼로그
// =====================================================
@Composable
private fun DeleteDeckDialog(deckName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// =====================================================
// Preview
// =====================================================
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

                // 환영
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
