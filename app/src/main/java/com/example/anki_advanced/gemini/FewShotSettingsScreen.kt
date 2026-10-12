package com.example.anki_advanced.gemini

// 퓨샷 예시 설정 화면 두 개.
//   FewShotSettingsScreen ("fewShotSettings"): 목록. 기본 유형 4개의 "내 예시 사용" 스위치와
//     사용자 지정 유형 목록을 보여준다. 덱 만들기 화면의 "예시 설정" 버튼으로 들어온다.
//   FewShotEditScreen ("fewShotEdit/{key}"): 유형 하나의 예시를 편집한다. 목록에서 항목을 누르면 들어온다.
//
// 시각 디자인은 Stitch에서 재생성한 시안을 따른다:
//   - 테두리 대신 배경색 차이로 영역을 나눈다 (흰 카드 ↔ 연회색 블록 ↔ 흰 입력칸)
//   - 입력칸은 평소엔 테두리가 없고, 포커스될 때만 primary 색 테두리가 생긴다
//   - 주요 버튼(저장)은 그라데이션 알약 모양, 보조 버튼은 흰/연회색 알약 모양

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

// ── 색상 팔레트: DeckGenerationScreen과 같은 톤 ──
private val SetBg = Color(0xFFF6F6FA)
private val SetSurface = Color(0xFFFFFFFF)
private val SetSurfaceLow = Color(0xFFF0F0F5)
private val SetPrimary = Color(0xFF4330F9)
private val SetPrimaryDeep = Color(0xFF2B1BE0)  // 저장 버튼 그라데이션의 끝 색
private val SetOnSurface = Color(0xFF2D2F32)
private val SetOnSurfaceMuted = Color(0xFF5A5B5F)
private val SetSwitchTrackOff = Color(0xFFDCDCE2)
private val SetError = Color(0xFFB41340)

private val PillShape = RoundedCornerShape(50)
private val FieldShape = RoundedCornerShape(16.dp)

// ══ 목록 화면 ══════════════════════════════════════════════════════════════
@Composable
fun FewShotSettingsScreen(
    navController: NavController? = null,
    viewModel: FewShotSettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    // 삭제 확인 다이얼로그를 띄울 대상 (null이면 다이얼로그 숨김)
    var pendingDelete by remember { mutableStateOf<CustomCardType?>(null) }

    Scaffold(
        topBar = { SettingsTopBar(title = "퓨샷 예시 설정", onBack = { navController?.popBackStack() }) },
        containerColor = SetBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IntroCard()

            SettingsSection(title = "기본 유형", subtitle = "스위치를 켜면 기본 예시 대신 내 예시를 사용해요.") {
                CardType.entries.forEach { type ->
                    val setting = settings.builtinSetting(type)
                    SettingsRow(
                        title = type.label,
                        subtitle = if (setting.examples.isEmpty()) "아직 내 예시 없음 — 눌러서 추가"
                        else "내 예시 ${setting.examples.size}개",
                        onClick = { navController?.navigate("fewShotEdit/builtin:${type.id}") }
                    ) {
                        SoftSwitch(
                            checked = setting.useCustom,
                            onCheckedChange = { viewModel.setUseCustom(type, it) }
                        )
                    }
                }
            }

            SettingsSection(
                title = "사용자 지정 유형",
                subtitle = "직접 만든 유형은 덱 만들기 화면의 유형 칩에 추가돼요."
            ) {
                if (settings.customTypes.isEmpty()) {
                    Text(
                        "아직 만든 유형이 없어요.",
                        fontSize = 14.sp,
                        color = SetOnSurfaceMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        textAlign = TextAlign.Center
                    )
                }
                settings.customTypes.forEach { type ->
                    SettingsRow(
                        title = type.name,
                        subtitle = "예시 ${type.examples.size}개" +
                            if (type.instruction.isNotBlank()) " · ${type.instruction}" else "",
                        onClick = { navController?.navigate("fewShotEdit/custom:${type.id}") }
                    ) {
                        IconButton(onClick = { pendingDelete = type }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = SetOnSurfaceMuted)
                        }
                    }
                }
                PillButton(
                    text = "새 유형 만들기",
                    icon = Icons.Filled.Add,
                    containerColor = SetSurfaceLow,
                    onClick = { navController?.navigate("fewShotEdit/custom:new") },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    pendingDelete?.let { type ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = SetSurface,
            shape = RoundedCornerShape(28.dp),
            title = { Text("'${type.name}' 유형 삭제", fontWeight = FontWeight.Bold, color = SetOnSurface) },
            text = { Text("이 유형과 예시가 모두 삭제돼요. 이미 만든 카드에는 영향이 없어요.", color = SetOnSurfaceMuted) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomType(type.id)
                    pendingDelete = null
                }) { Text("삭제", color = SetError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("취소", color = SetPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// 목록 화면 맨 위 안내 카드: 원형 배경 안의 반짝이 아이콘 + 설명 문구
@Composable
private fun IntroCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurfaceLow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SetPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = SetPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "AI에게 \"이런 자료로는 이런 카드를 만들어\"라고 보여주는 예시를 직접 정할 수 있어요. " +
                "예시가 실제 학습 자료와 비슷할수록 결과 카드도 원하는 모양에 가까워져요.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = SetOnSurfaceMuted
        )
    }
}

// 흰 카드 모양 섹션 (제목 + 설명 + 내용)
@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SetSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SetOnSurface)
        Text(subtitle, fontSize = 12.sp, color = SetOnSurfaceMuted, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

// 섹션 안의 한 줄: 왼쪽 제목/부제목(누르면 편집 화면), 오른쪽 trailing(스위치나 삭제 버튼)
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SetSurfaceLow)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SetOnSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                color = SetOnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing()
    }
}

// ══ 편집 화면 ══════════════════════════════════════════════════════════════
// key: "builtin:<유형 id>" / "custom:<id>" / "custom:new" (FewShotEditViewModel.load 참고)
@Composable
fun FewShotEditScreen(
    key: String,
    navController: NavController? = null,
    viewModel: FewShotEditViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(key) { viewModel.load(key) }
    // 저장이 끝나면 목록 화면으로 돌아간다.
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) navController?.popBackStack()
    }

    val title = when {
        uiState.builtinType != null -> "${uiState.builtinType!!.label} 예시"
        uiState.customId == null -> "새 유형 만들기"
        else -> "유형 편집"
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = title,
                onBack = { navController?.popBackStack() },
                onSave = viewModel::save
            )
        },
        containerColor = SetBg
    ) { innerPadding ->
        if (!uiState.loaded) return@Scaffold
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.isCustom) {
                CustomTypeHeader(
                    name = uiState.name,
                    instruction = uiState.instruction,
                    onNameChange = viewModel::setName,
                    onInstructionChange = viewModel::setInstruction
                )
            } else {
                BuiltinHeader(
                    useCustom = uiState.useCustom,
                    onUseCustomChange = viewModel::setUseCustom,
                    onImportDefaults = viewModel::importDefaultExamples
                )
            }

            uiState.errorMessage?.let {
                Text(it, color = SetError, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = SetOnSurfaceMuted,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "예시는 2~5개 정도가 적당해요. 앞·뒤가 비어 있는 카드와 자료가 빈 예시는 저장할 때 빠져요.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = SetOnSurfaceMuted
                )
            }

            uiState.examples.forEachIndexed { exampleIndex, example ->
                ExampleEditor(
                    index = exampleIndex,
                    example = example,
                    viewModel = viewModel
                )
            }

            PillButton(
                text = "예시 추가",
                icon = Icons.Filled.Add,
                containerColor = SetSurface,
                onClick = viewModel::addExample,
                elevated = true
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// 사용자 지정 유형 편집 화면 상단: 이름 + 작성 지침
@Composable
private fun CustomTypeHeader(
    name: String,
    instruction: String,
    onNameChange: (String) -> Unit,
    onInstructionChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SetSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SoftTextField(
            value = name,
            onValueChange = onNameChange,
            label = "유형 이름 (필수, 예: 영단어)",
            placeholder = "예: 토익 빈출 영단어",
            containerColor = SetSurfaceLow,
            singleLine = true
        )
        SoftTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            label = "작성 지침 (선택)",
            placeholder = "예: 영단어를 앞면에, 뜻과 예문 하나를 뒷면에 담아.",
            containerColor = SetSurfaceLow,
            minLines = 2
        )
    }
}

// 기본 유형 편집 화면 상단: "내 예시 사용" 스위치 + 기본 예시 불러오기
@Composable
private fun BuiltinHeader(
    useCustom: Boolean,
    onUseCustomChange: (Boolean) -> Unit,
    onImportDefaults: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SetSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("내 예시 사용", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SetOnSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "켜면 기본 예시 대신 아래 예시를 사용해요. 개념 맞히기·설명하기 같은 세부 형태는 예시가 아니라 덱 만들기 화면에서 유형별로 골라요.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = SetOnSurfaceMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            SoftSwitch(checked = useCustom, onCheckedChange = onUseCustomChange)
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onImportDefaults)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("기본 예시 불러와서 고치기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SetPrimary)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = SetPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

// 예시 하나: 자료 입력칸 + 카드 목록 + 카드 추가 버튼
@Composable
private fun ExampleEditor(
    index: Int,
    example: DraftExample,
    viewModel: FewShotEditViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SetSurface)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SetPrimary)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "예시 ${index + 1}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = SetOnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.removeExample(index) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "예시 삭제", tint = SetOnSurfaceMuted)
            }
        }

        Column(
            modifier = Modifier.padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftTextField(
                value = example.source,
                onValueChange = { viewModel.setSource(index, it) },
                label = "자료 (이런 내용이 들어오면)",
                placeholder = "학습할 원본 텍스트나 문장을 입력하세요",
                containerColor = SetSurfaceLow,
                minLines = 2
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = SetPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("이런 카드를 만든다", fontSize = 14.sp, color = SetOnSurfaceMuted)
            }

            example.cards.forEachIndexed { cardIndex, card ->
                CardEditor(
                    cardIndex = cardIndex,
                    card = card,
                    onRemove = { viewModel.removeCard(index, cardIndex) },
                    onFrontChange = { viewModel.setFront(index, cardIndex, it) },
                    onBackChange = { viewModel.setBack(index, cardIndex, it) }
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewModel.addCard(index) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = SetPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("카드 추가", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SetPrimary)
            }
        }
    }
}

// 예시 안의 카드 한 장: 연회색 블록 위에 흰 앞면/뒷면 입력칸
@Composable
private fun CardEditor(
    cardIndex: Int,
    card: DraftCard,
    onRemove: () -> Unit,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurfaceLow)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "카드 ${cardIndex + 1}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SetOnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "카드 삭제", tint = SetOnSurfaceMuted)
            }
        }
        Column(
            modifier = Modifier.padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SoftTextField(
                value = card.front,
                onValueChange = onFrontChange,
                label = "앞면 (질문)",
                containerColor = SetSurface
            )
            SoftTextField(
                value = card.back,
                onValueChange = onBackChange,
                label = "뒷면 (답)",
                containerColor = SetSurface
            )
        }
    }
}

// ══ 공용 부품 ══════════════════════════════════════════════════════════════

// 상단바: 흰 배경 + 아래쪽 옅은 그림자. onSave가 있으면 오른쪽에 그라데이션 "저장" 버튼.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit, onSave: (() -> Unit)? = null) {
    Surface(color = SetSurface, shadowElevation = 2.dp) {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = SetOnSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로", tint = SetOnSurface)
                }
            },
            actions = {
                if (onSave != null) {
                    // [문법] Modifier.background(Brush.linearGradient(...))
                    //   단색 대신 두 색이 대각선으로 섞이는 그라데이션으로 배경을 칠한다.
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(PillShape)
                            .background(Brush.linearGradient(listOf(SetPrimary, SetPrimaryDeep)))
                            .clickable(onClick = onSave)
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text("저장", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SetSurface)
        )
    }
}

// 테두리 없는 알약 모양 스위치. 꺼져 있을 때 회색 트랙 + 큰 흰 원, 켜지면 primary 트랙.
// [문법] thumbContent = { Spacer(...) }
//   Material3 Switch는 thumbContent가 있으면 꺼진 상태에서도 원(thumb)을 크게 그린다.
//   안에 그릴 아이콘은 없으므로 빈 Spacer만 넣어서 "항상 큰 원" 효과만 얻는다.
@Composable
private fun SoftSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = { Spacer(Modifier.size(SwitchDefaults.IconSize)) },
        colors = SwitchDefaults.colors(
            checkedTrackColor = SetPrimary,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = SetSwitchTrackOff,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

// 평소엔 테두리 없이 배경색(containerColor)만 있고, 포커스되면 primary 테두리가 생기는 입력칸.
// 놓이는 곳의 배경과 대비되는 색을 넘겨준다 (흰 카드 위엔 연회색, 연회색 블록 위엔 흰색).
@Composable
private fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    containerColor: Color,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            focusedBorderColor = SetPrimary,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = SetPrimary,
            unfocusedLabelColor = SetOnSurfaceMuted,
            unfocusedPlaceholderColor = SetOnSurfaceMuted.copy(alpha = 0.6f),
            focusedPlaceholderColor = SetOnSurfaceMuted.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// 가로 꽉 찬 알약 버튼 (아이콘 + primary 굵은 글씨). elevated면 옅은 그림자로 살짝 띄운다.
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier.shadow(6.dp, PillShape, ambientColor = SetPrimary, spotColor = SetPrimary.copy(alpha = 0.3f)) else Modifier)
            .clip(PillShape)
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(PaddingValues(vertical = 18.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SetPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SetPrimary)
    }
}
