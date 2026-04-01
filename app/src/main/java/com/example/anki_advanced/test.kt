package com.example.anki_advanced

// =====================================================
// StudyCardPreview.kt — Stitch 디자인 미리보기 전용
// =====================================================
// 이 파일은 비즈니스 로직 없이 순수 UI만 담고 있습니다.
// Android Studio에서 이 파일을 열면 오른쪽 미리보기 패널에
// Stitch에서 만든 디자인이 그대로 나옵니다.
//
// 사용법:
// 1. build.gradle에 Compose 의존성 추가 + Sync
// 2. 이 파일을 app/src/main/java/com/example/anki_advanced/ 에 넣기
// 3. Android Studio에서 파일 열기
// 4. 오른쪽 패널에 "Design" 또는 "Split" 탭 클릭
// 5. @Preview 함수 옆에 디자인이 표시됨
// =====================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// =====================================================
// 색상 정의 — Stitch 디자인의 Lumina Lexicon 테마
// =====================================================
// Stitch HTML에서 가져온 색상값들
val StitchPrimary = Color(0xFF4F46E5)          // primary-container (Indigo)
val StitchPrimaryDark = Color(0xFF3525CD)      // primary
val StitchOnPrimary = Color(0xFFFFFFFF)        // on-primary
val StitchSurface = Color(0xFFFCF8FF)          // surface / background
val StitchSurfaceContainer = Color(0xFFF0ECF9) // surface-container
val StitchSurfaceContainerLow = Color(0xFFF5F2FF)  // surface-container-low
val StitchSurfaceHighest = Color(0xFFE4E1EE)   // surface-container-highest
val StitchOnSurface = Color(0xFF1B1B24)        // on-surface
val StitchOutline = Color(0xFF777587)          // outline
val StitchError = Color(0xFFBA1A1A)            // error
val StitchSecondary = Color(0xFF006B5F)        // secondary
val StitchOrange = Color(0xFFE65100)           // Hard 버튼 색상


// =====================================================
// 메인 화면 — Stitch "Study Card" 디자인 재현
// =====================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyCardScreen() {
    Scaffold(
        // ─── 상단바 ───
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 아이콘 자리 (정사각형 박스로 대체)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE2DFFF)),  // primary-fixed
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📚", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Study: English Vocabulary",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = StitchOnSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Text("✕", fontSize = 20.sp, color = StitchOutline)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StitchSurface.copy(alpha = 0.6f)
                )
            )
        },

        // ─── 하단 난이도 버튼 ───
        bottomBar = {
            DifficultyButtonsBar()
        },

        containerColor = StitchSurface

    ) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ─── 프로그레스 바 ───
            DailyProgressSection()

            Spacer(modifier = Modifier.height(40.dp))

            // ─── 플래시카드 ───
            FlashcardSection()
        }
    }
}


// =====================================================
// 프로그레스 바 영역
// =====================================================
@Composable
fun DailyProgressSection() {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 라벨 + 숫자
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "Daily Progress",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = StitchOutline
            )
            Row {
                Text(
                    text = "20 ",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = StitchPrimaryDark
                )
                Text(
                    text = "/ 120",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = StitchOutline
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 프로그레스 바
        LinearProgressIndicator(
            progress = { 20f / 120f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = StitchPrimaryDark,
            trackColor = StitchSurfaceHighest
        )
    }
}


// =====================================================
// 플래시카드 영역
// =====================================================
@Composable
fun FlashcardSection() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 12.dp
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(48.dp)
            ) {
                // 카테고리 라벨
                Text(
                    text = "VOCABULARY CARD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = StitchOutline
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 단어 (앞면)
                Text(
                    text = "Serendipity",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StitchOnSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.weight(1f))

                // 정답 보기 버튼
                Button(
                    onClick = { },
                    shape = RoundedCornerShape(50),  // pill 모양 (둥근 버튼)
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StitchPrimary
                    ),
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth(0.7f),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp
                    )
                ) {
                    Text(
                        text = "Show Answer",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = StitchOnPrimary
                    )
                }
            }
        }
    }
}


// =====================================================
// 하단 난이도 버튼 바
// =====================================================
@Composable
fun DifficultyButtonsBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StitchSurface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Again
        DifficultyButton(
            label = "Again",
            time = "1m",
            labelColor = StitchError,
            modifier = Modifier.weight(1f)
        )
        // Hard
        DifficultyButton(
            label = "Hard",
            time = "2d",
            labelColor = StitchOrange,
            modifier = Modifier.weight(1f)
        )
        // Good
        DifficultyButton(
            label = "Good",
            time = "4d",
            labelColor = StitchPrimaryDark,
            modifier = Modifier.weight(1f)
        )
        // Easy
        DifficultyButton(
            label = "Easy",
            time = "7d",
            labelColor = StitchSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DifficultyButton(
    label: String,
    time: String,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = StitchSurfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = labelColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = time,
                fontSize = 10.sp,
                color = StitchOutline
            )
        }
    }
}


// =====================================================
// Preview — Android Studio에서 이 부분이 미리보기로 표시됨
// =====================================================
// Android Studio에서 이 파일을 열고
// 오른쪽 "Split" 또는 "Design" 탭을 클릭하면
// 아래 Preview가 렌더링됩니다.
// =====================================================
@Preview(
    showBackground = true,
    widthDp = 400,         // 일반 폰 화면 너비
    heightDp = 800,        // 일반 폰 화면 높이
    name = "Study Card"
)
@Composable
fun StudyCardPreview() {
    MaterialTheme {
        StudyCardScreen()
    }
}
