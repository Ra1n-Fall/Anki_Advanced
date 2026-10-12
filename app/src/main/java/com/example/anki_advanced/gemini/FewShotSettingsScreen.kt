package com.example.anki_advanced.gemini

// 퓨샷 예시 설정 화면 두 개.
//   FewShotSettingsScreen ("fewShotSettings"): 목록. 기본 유형 4개의 "내 예시 사용" 스위치와
//     사용자 지정 유형 목록을 보여준다. 덱 만들기 화면의 "예시 설정" 버튼으로 들어온다.
//   FewShotEditScreen ("fewShotEdit/{key}"): 유형 하나의 예시를 편집한다. 목록에서 항목을 누르면 들어온다.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
private val SetOnSurface = Color(0xFF2D2F32)
private val SetOnSurfaceMuted = Color(0xFF5A5B5F)
private val SetError = Color(0xFFB41340)

// ══ 목록 화면 ══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FewShotSettingsScreen(
    navController: NavController? = null,
    viewModel: FewShotSettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    // 삭제 확인 다이얼로그를 띄울 대상 (null이면 다이얼로그 숨김)
    var pendingDelete by remember { mutableStateOf<CustomCardType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("퓨샷 예시 설정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SetSurface)
            )
        },
        containerColor = SetBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "AI에게 \"이런 자료로는 이런 카드를 만들어\"라고 보여주는 예시를 직접 정할 수 있어요. " +
                    "예시가 실제 학습 자료와 비슷할수록 결과 카드도 원하는 모양에 가까워져요.",
                fontSize = 13.sp,
                color = SetOnSurfaceMuted
            )

            SettingsSection(title = "기본 유형", subtitle = "스위치를 켜면 기본 예시 대신 내 예시를 사용해요.") {
                CardType.entries.forEach { type ->
                    val setting = settings.builtinSetting(type)
                    SettingsRow(
                        title = type.label,
                        subtitle = if (setting.examples.isEmpty()) "아직 내 예시 없음 — 눌러서 추가"
                        else "내 예시 ${setting.examples.size}개",
                        onClick = { navController?.navigate("fewShotEdit/builtin:${type.id}") }
                    ) {
                        Switch(
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
                        fontSize = 13.sp,
                        color = SetOnSurfaceMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
                            Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = SetOnSurfaceMuted)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { navController?.navigate("fewShotEdit/custom:new") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("새 유형 만들기")
                }
            }
        }
    }

    pendingDelete?.let { type ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("'${type.name}' 유형 삭제") },
            text = { Text("이 유형과 예시가 모두 삭제돼요. 이미 만든 카드에는 영향이 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomType(type.id)
                    pendingDelete = null
                }) { Text("삭제", color = SetError) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            }
        )
    }
}

// 흰 카드 모양 섹션 (제목 + 설명 + 내용)
@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SetOnSurface)
        Text(subtitle, fontSize = 12.sp, color = SetOnSurfaceMuted)
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
            .clip(RoundedCornerShape(14.dp))
            .background(SetSurfaceLow)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SetOnSurface)
            Text(
                subtitle,
                fontSize = 12.sp,
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
@OptIn(ExperimentalMaterial3Api::class)
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
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) {
                        Text("저장", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SetSurface)
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
                .padding(20.dp),
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

            Text(
                "예시는 2~5개 정도가 적당해요. 앞·뒤가 비어 있는 카드와 자료가 빈 예시는 저장할 때 빠져요.",
                fontSize = 12.sp,
                color = SetOnSurfaceMuted
            )

            uiState.examples.forEachIndexed { exampleIndex, example ->
                ExampleEditor(
                    index = exampleIndex,
                    example = example,
                    styleOptions = uiState.styleOptions,
                    viewModel = viewModel
                )
            }

            OutlinedButton(onClick = viewModel::addExample, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("예시 추가")
            }
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
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("유형 이름 (필수, 예: 영단어)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            label = { Text("작성 지침 (선택)") },
            placeholder = { Text("예: 영단어를 front에, 뜻과 예문 하나를 back에 담아.") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
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
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("내 예시 사용", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SetOnSurface)
                Text(
                    "켜면 기본 예시 대신 아래 예시를 사용해요. 고른 형태에 맞는 내 예시가 없으면 기본 예시로 대신해요.",
                    fontSize = 12.sp,
                    color = SetOnSurfaceMuted
                )
            }
            Switch(checked = useCustom, onCheckedChange = onUseCustomChange)
        }
        TextButton(onClick = onImportDefaults) {
            Text("기본 예시 불러와서 고치기", fontSize = 13.sp)
        }
    }
}

// 예시 하나: 자료 입력칸 + 카드 목록 + 카드 추가 버튼
@Composable
private fun ExampleEditor(
    index: Int,
    example: DraftExample,
    styleOptions: List<CardStyle>,
    viewModel: FewShotEditViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SetSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "예시 ${index + 1}",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = SetOnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.removeExample(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = "예시 삭제", tint = SetOnSurfaceMuted)
            }
        }

        OutlinedTextField(
            value = example.source,
            onValueChange = { viewModel.setSource(index, it) },
            label = { Text("자료 (이런 내용이 들어오면)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        Text("→ 이런 카드를 만든다", fontSize = 12.sp, color = SetOnSurfaceMuted)

        example.cards.forEachIndexed { cardIndex, card ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SetSurfaceLow)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "카드 ${cardIndex + 1}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SetOnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.removeCard(index, cardIndex) }) {
                        Icon(Icons.Filled.Close, contentDescription = "카드 삭제", tint = SetOnSurfaceMuted)
                    }
                }
                // 기본 유형이면 이 카드가 어떤 세부 형태인지 고른다. 덱 만들기에서 형태를
                // 하나만 체크했을 때 그 형태의 카드만 예시로 보내기 위해 필요하다.
                if (styleOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        styleOptions.forEach { style ->
                            FilterChip(
                                selected = card.style == style,
                                onClick = { viewModel.setStyle(index, cardIndex, style) },
                                label = { Text(style.label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SetPrimary.copy(alpha = 0.12f),
                                    selectedLabelColor = SetPrimary
                                )
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = card.front,
                    onValueChange = { viewModel.setFront(index, cardIndex, it) },
                    label = { Text("앞면 (질문)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = card.back,
                    onValueChange = { viewModel.setBack(index, cardIndex, it) },
                    label = { Text("뒷면 (답)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                )
            }
        }

        TextButton(onClick = { viewModel.addCard(index) }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("카드 추가", fontSize = 13.sp)
        }
    }
}
