package com.example.anki_advanced.completion

// 완주 모드 설정 팝업(다이얼로그) 화면.
// 사용자가 캘린더에서 목표 종료 날짜를 직접 고르면, 남은 기간 / 종료일 / 하루 예상 학습량을
// 미리 계산해서 보여주고, "설정 완료"를 누르면 DB에 CompletionModeConfigEntity를 저장한다.

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

// ── 색상 팔레트 (HomeScreen 라이트 테마와 통일) ───────────────────────────────
private val SetupPrimary          = Color(0xFF4330F9)
private val SetupOnPrimary        = Color(0xFFF1EDFF)
private val SetupSurfaceCard      = Color(0xFFFFFFFF)
private val SetupSurfaceMid       = Color(0xFFE7E8ED)
private val SetupOnSurface        = Color(0xFF2D2F32)
private val SetupOnSurfaceVariant = Color(0xFF5A5B5F)
private val SetupOutlineVariant   = Color(0xFFACADB1)

// 어떤 날짜(year, month, day)를 "그날의 마지막 순간(23:59:59.999)"으로 만들어주는 헬퍼.
// [문법] Calendar.getInstance().apply { ... }
//   apply는 "이 객체를 만들자마자 블록 안에서 이런저런 설정을 하고, 결과로 그 객체 자신을 돌려줘"라는 뜻.
//   Calendar.getInstance()로 만든 임시 객체에 set(...)을 연달아 호출한 뒤, 그 객체를 그대로 반환한다.
private fun endOfDay(year: Int, month: Int, day: Int): Calendar =
    Calendar.getInstance().apply {
        set(year, month, day, 23, 59, 59)
        set(Calendar.MILLISECOND, 999)
    }

// [문법] @Composable fun X(..., onConfirm: () -> Unit, onDismiss: () -> Unit, viewModel = viewModel())
//   onConfirm / onDismiss는 "이 화면이 부모에게 결과를 알려주는 콜백 함수".
//   화면 자신은 "확인을 눌렀다"는 사실만 알리고, 그 다음에 뭘 할지는 호출하는 쪽이 정한다.
@Composable
fun CompletionModeSetupDialog(
    deckId: Long,
    deckName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CompletionModeSetupViewModel = viewModel()
) {
    // 다이얼로그가 뜰 때 한 번 해당 덱 정보를 불러온다.
    LaunchedEffect(deckId) { viewModel.load(deckId) }

    val totalCards    by viewModel.totalCards.collectAsState()
    val existingEndAt by viewModel.existingEndAt.collectAsState()
    val isEditMode = existingEndAt != null  // 이미 설정이 있으면 "수정 모드"

    // [문법] remember(key1, key2, ...) { ... }
    //   remember는 기본적으로 "처음 한 번만 계산하고 그 이후엔 재사용"하지만,
    //   괄호 안에 key를 넣으면 "그 key가 바뀔 때는 다시 계산해라"는 뜻이 된다.
    //   여기서는 existingEndAt(기존 설정 로딩 결과)이 바뀌면 initialDate를 다시 계산.
    val initialDate = remember(existingEndAt) {
        if (existingEndAt != null) {
            // [문법] existingEndAt!!  → "null이 아님을 내가 보장한다"는 강제 non-null 단언.
            //   바로 위 if에서 이미 null이 아님을 확인했으므로 안전하게 사용 가능.
            Calendar.getInstance().apply { timeInMillis = existingEndAt!! }
        } else {
            // 기존 설정이 없으면 기본값으로 "오늘로부터 30일 뒤"를 제안.
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 30) }
        }
    }

    // [문법] var x by remember(key) { mutableStateOf(초기값) }
    //   Compose 화면 안에서 "값이 바뀌면 화면을 다시 그려야 하는" 상태 변수를 선언하는 표준 패턴.
    //   selectedDate가 바뀌면 이 값을 쓰는 UI 부분이 자동으로 다시 그려진다.
    var selectedDate by remember(initialDate) {
        mutableStateOf(
            endOfDay(
                initialDate.get(Calendar.YEAR),
                initialDate.get(Calendar.MONTH),
                initialDate.get(Calendar.DAY_OF_MONTH)
            )
        )
    }
    // 캘린더에 지금 "몇 년 몇 월"이 펼쳐져 있는지 (월 이동 버튼으로 바뀜)
    var displayedMonth by remember(initialDate) {
        mutableStateOf(
            Calendar.getInstance().apply {
                set(Calendar.YEAR,  initialDate.get(Calendar.YEAR))
                set(Calendar.MONTH, initialDate.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, 1)
            }
        )
    }

    // 오늘 자정 — 캘린더에서 "오늘 이전 날짜는 선택 못 하게" 막는 기준선.
    // remember{}만 쓰고 key가 없으니, 이 다이얼로그가 떠 있는 동안은 딱 한 번만 계산됨.
    val todayMidnight = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }

    // [문법] remember(key) { derivedStateOf { ... } }
    //   derivedStateOf는 "다른 상태값들을 조합해서 계산해내는 파생 상태"를 만들 때 쓴다.
    //   selectedDate가 바뀔 때만 다시 계산되고, 그 외에는 이전 계산 결과를 재사용해서 효율적이다.
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

    // [문법] Dialog(onDismissRequest = onDismiss) { ... }
    //   화면 전체를 덮는 팝업 창을 띄우는 컴포저블. 바깥 영역을 탭하거나 뒤로가기를 누르면
    //   onDismissRequest가 호출된다(여기서는 그대로 onDismiss로 전달해서 "닫아라" 알림).
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = SetupSurfaceCard
        ) {
            Column(
                modifier = Modifier
                    // [문법] .verticalScroll(rememberScrollState())
                    //   내용이 화면보다 길어지면 세로로 스크롤할 수 있게 해주는 Modifier.
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 헤더: 제목 + 덱 이름 ─────────────────────────────────────
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

                // ── 캘린더 UI ────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))  // 모서리를 둥글게 잘라내는 Modifier
                        .background(SetupSurfaceMid)
                        .padding(12.dp)
                ) {
                    // 월 이동 헤더: "◀  2026년 9월  ▶"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                // [문법] (displayedMonth.clone() as Calendar).apply { add(...) }
                                //   Calendar는 "가변(mutable)" 객체라서, 원본을 직접 바꾸면
                                //   Compose가 "값이 바뀌었다"를 감지 못할 수 있다. 그래서 clone()으로
                                //   복사본을 만들고, 그 복사본을 바꾼 뒤 새 객체를 displayedMonth에 대입한다.
                                //   as Calendar는 "이 결과를 Calendar 타입으로 취급해라"는 타입 캐스팅.
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

                    // 요일 헤더 (일~토)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // [문법] listOf(...).forEach { label -> ... }
                        //   리스트의 각 원소마다 블록을 실행. map과 달리 "새 리스트를 만들지 않고
                        //   그냥 하나씩 처리만 한다"는 점이 다르다 (여기서는 Text를 하나씩 그리는 것 자체가 목적).
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

                    // 날짜 그리드 계산: 이번 달 1일이 무슨 요일인지 찾아서, 그만큼 빈 칸을 앞에 두고
                    // 날짜를 채워나간다 (실제 달력 앱들이 다 이런 방식으로 그린다).
                    val year  = displayedMonth.get(Calendar.YEAR)
                    val month = displayedMonth.get(Calendar.MONTH)
                    val firstDay    = Calendar.getInstance().apply { set(year, month, 1) }
                    val startOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1  // 1일 앞에 비워둘 칸 수
                    val daysInMonth = displayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val rowCount    = ceil((startOffset + daysInMonth).toDouble() / 7).toInt()

                    // [문법] for (row in 0 until rowCount) { ... }
                    //   0부터 rowCount "미만"까지 반복 (0..rowCount-1과 동일). 7일씩 한 줄(row)을 그린다.
                    for (row in 0 until rowCount) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (col in 0 until 7) {
                                val dayNum = row * 7 + col - startOffset + 1
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        // [문법] .aspectRatio(1f) → 가로:세로 비율을 1:1(정사각형)로 고정.
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // [문법] dayNum in 1..daysInMonth
                                    //   dayNum이 1부터 daysInMonth 사이(양 끝 포함)에 있는지 검사.
                                    //   범위 밖이면(빈 칸이면) 아무것도 안 그림.
                                    if (dayNum in 1..daysInMonth) {
                                        val cellCal = endOfDay(year, month, dayNum)
                                        // 이 날짜가 오늘보다 이전(또는 오늘)인지 판정 → 선택 불가 여부
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
                                                .clip(CircleShape) // 원 모양으로 자르기
                                                .background(
                                                    if (isSelected) SetupPrimary else Color.Transparent
                                                )
                                                // [문법] Modifier.then(다른Modifier)
                                                //   조건에 따라 Modifier 체인에 뭔가를 "덧붙일지 말지" 고를 때 쓴다.
                                                //   과거 날짜(isPast)면 클릭 가능하게 만들지 않고, 미래 날짜만 클릭 가능하게.
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

                // ── 정보 영역: 남은 기간 / 종료 예정일 / 하루 예상 학습량 ────────
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

                // ── 하단 버튼: 취소 / 설정 완료 ──────────────────────────────
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
                            // 저장 완료 후 콜백으로 onConfirm() 실행 (예: 다이얼로그 닫고 목록 새로고침)
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
