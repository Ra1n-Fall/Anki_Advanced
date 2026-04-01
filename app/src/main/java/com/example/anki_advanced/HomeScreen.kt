package com.example.anki_advanced

// =====================================================
// HomeScreen.kt — Stitch "홈 화면 (메뉴 버튼 위치 조정)" 디자인
// =====================================================
// 이 파일은 순수 UI 코드입니다. 비즈니스 로직 없음.
// Android Studio에서 파일 열고 "Split" 또는 "Design" 탭 클릭 시
// 하단의 @Preview 함수가 미리보기로 렌더링됩니다.
// =====================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =====================================================
// 색상 — Stitch 디자인 시스템 토큰
// =====================================================
private val HomePrimary             = Color(0xFF4330F9)
private val HomePrimaryDim          = Color(0xFF3517EE)
private val HomeOnPrimary           = Color(0xFFF1EDFF)
private val HomeSurface             = Color(0xFFF6F6FA)
private val HomeSurfaceContainerLowest = Color(0xFFFFFFFF)
private val HomeSurfaceContainerLow = Color(0xFFF0F0F5)
private val HomeSurfaceContainer    = Color(0xFFE7E8ED)
private val HomeOnSurface           = Color(0xFF2D2F32)
private val HomeOnSurfaceVariant    = Color(0xFF5A5B5F)
private val HomeOutline             = Color(0xFF75777A)
private val HomeOutlineVariant      = Color(0xFFACADB1)
private val HomeSecondaryContainer  = Color(0xFFD8DAFF)
private val HomeOnSecondaryContainer= Color(0xFF494C6A)
private val HomeTertiary            = Color(0xFF983772)
private val HomeTertiaryContainer   = Color(0xFFFFD8EE)
private val HomeError               = Color(0xFFB41340)

// =====================================================
// 데이터 모델 (UI 전용)
// =====================================================
private data class DeckUiModel(
    val title: String,
    val subtitle: String,
    val plan: String,
    val iconEmoji: String,
    val iconBgColor: Color,
    val iconTintColor: Color,
    val newCount: Int,
    val learningCount: Int,
    val reviewCount: Int
)

// =====================================================
// 루트 화면
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: androidx.navigation.NavController? = null
) {
    val decks = listOf(
        DeckUiModel(
            title = "JLPT N1 어휘",
            subtitle = "마지막 학습: 2시간 전",
            plan = "플랜: 3개월",
            iconEmoji = "翻",
            iconBgColor = HomeSecondaryContainer,
            iconTintColor = HomePrimary,
            newCount = 12,
            learningCount = 45,
            reviewCount = 89
        ),
        DeckUiModel(
            title = "데이터 사이언스 개념",
            subtitle = "마지막 학습: 어제",
            plan = "플랜: 무제한",
            iconEmoji = "🧠",
            iconBgColor = HomeTertiaryContainer,
            iconTintColor = HomeTertiary,
            newCount = 5,
            learningCount = 18,
            reviewCount = 32
        )
    )

    Scaffold(
        topBar = { HomeTopBar() },
        bottomBar = { HomeBottomNav() },
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

            // 환영 헤더
            WelcomeSection()

            // 오늘 진도 히어로 카드
            DailyProgressHeroCard()

            // 덱 목록
            DeckSection(decks = decks)

            // 빠른 통계
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
            text = "안녕하세요, 지현님",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = HomeOnSurface,
            letterSpacing = (-0.5).sp
        )
    }
}

// =====================================================
// 오늘 진도 히어로 카드 (그라디언트)
// =====================================================
@Composable
private fun DailyProgressHeroCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(HomePrimary, HomePrimaryDim)
                )
            )
            .padding(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // 상단 텍스트
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
                        text = "85%",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = HomeOnPrimary,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text = "완료됨",
                        fontSize = 18.sp,
                        color = HomeOnPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // 프로그레스 바
            LinearProgressIndicator(
                progress = { 0.85f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = HomeOnPrimary,
                trackColor = HomeOnPrimary.copy(alpha = 0.2f),
                strokeCap = StrokeCap.Round
            )

            // 학습 시작 버튼
            Button(
                onClick = {},
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HomeOnPrimary,
                    contentColor = HomePrimary
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "학습 시작하기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

// =====================================================
// 덱 섹션
// =====================================================
@Composable
private fun DeckSection(decks: List<DeckUiModel>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 헤더
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
            Text(
                text = "모두 보기",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomePrimary
            )
        }

        // 덱 카드들
        decks.forEach { deck ->
            DeckCard(deck = deck)
        }

        // 새 덱 추가 카드
        AddNewDeckCard()
    }
}

// =====================================================
// 개별 덱 카드
// =====================================================
@Composable
private fun DeckCard(deck: DeckUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = HomeSurfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 헤더 행 (아이콘 + 제목 + 더보기)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 덱 아이콘
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(deck.iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = deck.iconEmoji, fontSize = 24.sp)
                }

                Spacer(Modifier.width(12.dp))

                // 제목 + 부제
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deck.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomeOnSurface
                    )
                    Text(
                        text = deck.subtitle,
                        fontSize = 13.sp,
                        color = HomeOnSurfaceVariant
                    )
                }

                // 플랜 뱃지
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(HomePrimary.copy(alpha = 0.1f))
                        .border(1.dp, HomePrimary.copy(alpha = 0.2f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = deck.plan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomePrimary
                    )
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "더보기",
                    tint = HomeOutline,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 통계 행 (신규 / 학습 중 / 복습)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeckStatChip(
                    label = "신규",
                    count = deck.newCount,
                    countColor = HomePrimary,
                    modifier = Modifier.weight(1f)
                )
                DeckStatChip(
                    label = "학습 중",
                    count = deck.learningCount,
                    countColor = HomeTertiary,
                    modifier = Modifier.weight(1f)
                )
                DeckStatChip(
                    label = "복습",
                    count = deck.reviewCount,
                    countColor = HomeError,
                    modifier = Modifier.weight(1f)
                )
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

// =====================================================
// 새 덱 추가 카드 (점선 테두리)
// =====================================================
@Composable
private fun AddNewDeckCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 2.dp,
                color = HomeOutlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp)
            )
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
                    imageVector = Icons.Filled.Add,
                    contentDescription = "추가",
                    tint = HomeOutline,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = "새로운 덱 만들기",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSurfaceVariant
            )
            Text(
                text = "새로운 단어장이나 학습 자료를 추가하세요",
                fontSize = 13.sp,
                color = HomeOutline,
                textAlign = TextAlign.Center
            )
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
        // 스트릭 카드
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeSecondaryContainer.copy(alpha = 0.4f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "🔥", fontSize = 24.sp)
            Text(
                text = "14일",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = HomeOnSecondaryContainer
            )
            Text(
                text = "연속 학습 스트릭",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = HomeOnSecondaryContainer.copy(alpha = 0.75f)
            )
        }

        // 총 카드 카드
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(HomeTertiaryContainer.copy(alpha = 0.25f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = HomeTertiary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "1,240",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = HomeTertiary
            )
            Text(
                text = "총 암기한 카드",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = HomeTertiary.copy(alpha = 0.75f)
            )
        }
    }
}

// =====================================================
// 하단 네비게이션 바
// =====================================================
@Composable
private fun HomeBottomNav() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = HomeSurfaceContainerLowest.copy(alpha = 0.95f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        BottomNavItem(icon = "🏠", label = "홈", isActive = true)
        BottomNavItem(icon = "➕", label = "만들기", isActive = false)
        BottomNavItem(icon = "📊", label = "통계", isActive = false)
        BottomNavItem(icon = null, label = "설정", isActive = false, useSettingsIcon = true)
    }
}

@Composable
private fun BottomNavItem(
    icon: String?,
    label: String,
    isActive: Boolean,
    useSettingsIcon: Boolean = false
) {
    val contentColor = if (isActive) HomePrimary else HomeOnSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) HomePrimary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (useSettingsIcon) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
        } else if (icon != null) {
            Text(text = icon, fontSize = 22.sp)
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor
        )
    }
}


// =====================================================
// Preview — Android Studio Split / Design 탭에서 확인
// =====================================================
@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    name = "홈 화면 (메뉴 버튼 위치 조정)"
)
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen()
    }
}
