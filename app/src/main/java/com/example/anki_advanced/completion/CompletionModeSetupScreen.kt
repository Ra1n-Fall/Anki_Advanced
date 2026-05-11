package com.example.anki_advanced.completion

// =====================================================
// CompletionModeSetupScreen.kt
// Stitch "완주 모드 설정 (날짜 지정)" — 팝업 다이얼로그 버전
//
// 역할:
// - 기존 CompletionModeSetupDialog를 대체
// - 캘린더 UI로 목표 종료 날짜 직접 선택
// - 남은 기간 / 종료 예정 / 일일 학습량 추정 표시
// - 설정 완료 시 DB에 CompletionModeConfigEntity 저장
// =====================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar
import kotlin.math.ceil

// ── 색상 (HomeScreen 라이트 테마와 통일) ─────────────────────────────────────
private val SetupPrimary          = Color(0xFF4330F9)
private val SetupOnPrimary        = Color(0xFFF1EDFF)
private val SetupSurfaceCard      = Color(0xFFFFFFFF)
private val SetupSurfaceMid       = Color(0xFFE7E8ED)
private val SetupOnSurface        = Color(0xFF2D2F32)
private val SetupOnSurfaceVariant = Color(0xFF5A5B5F)
private val SetupOutlineVariant   = Color(0xFFACADB1)

// ── 헬퍼 ─────────────────────────────────────────────────────────────────────
private fun endOfDay(year: Int, month: Int, day: Int): Calendar =
    Calendar.getInstance().apply {
        set(year, month, day, 23, 59, 59)
        set(Calendar.MILLISECOND, 999)
    }

@Composable
fun CompletionModeSetupDialog(
    deckId: Long,
    deckName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CompletionModeSetupViewModel = viewModel()
) {
    LaunchedEffect(deckId) { viewModel.load(deckId) }

    val totalCards    by viewModel.totalCards.collectAsState()
    val existingEndAt by viewModel.existingEndAt.collectAsState()
    val isEditMode = existingEndAt != null

    val initialDate = remember(existingEndAt) {
        if (existingEndAt != null) {
            Calendar.getInstance().apply { timeInMillis = existingEndAt!! }
        } else {
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 30) }
        }
    }

    var selectedDate by remember(initialDate) {
        mutableStateOf(
            endOfDay(
                initialDate.get(Calendar.YEAR),
                initialDate.get(Calendar.MONTH),
                initialDate.get(Calendar.DAY_OF_MONTH)
            )
        )
    }
    var displayedMonth by remember(initialDate) {
        mutableStateOf(
            Calendar.getInstance().apply {
                set(Calendar.YEAR,  initialDate.get(Calendar.YEAR))
                set(Calendar.MONTH, initialDate.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, 1)
            }
        )
    }

    // 오늘 자정 — 오늘 이전 날짜 비활성화 기준
    val todayMidnight = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }

    val daysRemaining by remember(selectedDate) {
        derivedStateOf {
            ((selectedDate.timeInMillis - System.currentTimeMillis()) / 86_400_000L)
                .toInt().coerceAtLeast(1)
        }
    }
    val cardsPerDay by remember(totalCards, daysRemaining) {
        derivedStateOf {
            if (totalCards > 0 && daysRemaining > 0)
                ceil(totalCards.toDouble() / daysRemaining).toInt()
            else 0
        }
    }
    val minutesPerDay by remember(cardsPerDay) {
        derivedStateOf { (cardsPerDay * 0.5).toInt().coerceAtLeast(if (cardsPerDay > 0) 1 else 0) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = SetupSurfaceCard
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 헤더 ─────────────────────────────────────────────────────
                Column {
                    Text(
                        if (isEditMode) "완주 모드 수정" else "완주 모드 설정",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = SetupOnSurface
                    )
                    Text(
                        deckName,
                        fontSize = 12.sp,
                        color = SetupOnSurfaceVariant
                    )
                }

                Text(
                    "목표 종료 날짜를 선택하세요",
                    fontSize = 13.sp,
                    color = SetupOnSurfaceVariant
                )

                // ── 캘린더 ───────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SetupSurfaceMid)
                        .padding(12.dp)
                ) {
                    // 월 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                displayedMonth = (displayedMonth.clone() as Calendar).apply {
                                    add(Calendar.MONTH, -1)
                                }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "이전 달",
                                tint = SetupOnSurface
                            )
                        }
                        Text(
                            "${displayedMonth.get(Calendar.YEAR)}년 " +
                            "${displayedMonth.get(Calendar.MONTH) + 1}월",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = SetupOnSurface
                        )
                        IconButton(
                            onClick = {
                                displayedMonth = (displayedMonth.clone() as Calendar).apply {
                                    add(Calendar.MONTH, 1)
                                }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "다음 달",
                                tint = SetupOnSurface
                            )
                        }
                    }

                    // 요일 헤더
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("일", "월", "화", "수", "목", "금", "토").forEach { label ->
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = SetupOnSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    // 날짜 그리드
                    val year  = displayedMonth.get(Calendar.YEAR)
                    val month = displayedMonth.get(Calendar.MONTH)
                    val firstDay    = Calendar.getInstance().apply { set(year, month, 1) }
                    val startOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1
                    val daysInMonth = displayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val rowCount    = ceil((startOffset + daysInMonth).toDouble() / 7).toInt()

                    for (row in 0 until rowCount) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (col in 0 until 7) {
                                val dayNum = row * 7 + col - startOffset + 1
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (dayNum in 1..daysInMonth) {
                                        val cellCal = endOfDay(year, month, dayNum)
                                        // 오늘 포함 이전 날짜는 선택 불가
                                        val isPast =
                                            cellCal.get(Calendar.YEAR) < todayMidnight.get(Calendar.YEAR) ||
                                            (cellCal.get(Calendar.YEAR) == todayMidnight.get(Calendar.YEAR) &&
                                             cellCal.get(Calendar.DAY_OF_YEAR) <= todayMidnight.get(Calendar.DAY_OF_YEAR))
                                        val isSelected =
                                            selectedDate.get(Calendar.YEAR)         == year  &&
                                            selectedDate.get(Calendar.MONTH)        == month &&
                                            selectedDate.get(Calendar.DAY_OF_MONTH) == dayNum

                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isSelected) SetupPrimary else Color.Transparent
                                                )
                                                .then(
                                                    if (!isPast) Modifier.clickable {
                                                        selectedDate = cellCal
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$dayNum",
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> SetupOnPrimary
                                                    isPast     -> SetupOutlineVariant
                                                    else       -> SetupOnSurface
                                                },
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 정보 영역 ────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SetupSurfaceMid)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("남은 기간", fontSize = 11.sp, color = SetupOnSurfaceVariant)
                            Text(
                                "${daysRemaining}일",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = SetupPrimary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("종료 예정", fontSize = 11.sp, color = SetupOnSurfaceVariant)
                            Text(
                                "${selectedDate.get(Calendar.MONTH) + 1}월 " +
                                "${selectedDate.get(Calendar.DAY_OF_MONTH)}일",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = SetupOnSurface
                            )
                        }
                    }

                    HorizontalDivider(color = SetupOutlineVariant.copy(alpha = 0.4f))

                    if (totalCards > 0) {
                        Text(
                            "총 ${totalCards}개의 단어를 매일 약 ${minutesPerDay}분씩 학습",
                            fontSize = 12.sp,
                            color = SetupOnSurfaceVariant
                        )
                    } else {
                        Text(
                            "카드를 추가하면 일일 학습량을 확인할 수 있습니다",
                            fontSize = 12.sp,
                            color = SetupOnSurfaceVariant
                        )
                    }
                }

                // ── 버튼 ─────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SetupOnSurfaceVariant)
                    ) {
                        Text("취소", fontWeight = FontWeight.Medium)
                    }
                    TextButton(
                        onClick = {
                            viewModel.save(deckId, selectedDate.timeInMillis) {
                                onConfirm()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SetupPrimary),
                        colors = ButtonDefaults.textButtonColors(contentColor = SetupOnPrimary)
                    ) {
                        Text(
                            if (isEditMode) "수정 완료" else "설정 완료",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
