package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.EntityType
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import com.mz2az.scenetrip.ui.IOS

/**
 * 검색 중 지도·시트를 덮는 막. 바깥을 누르면 검색이 닫힌다.
 *
 * iOS 는 `Color.black.opacity(0.25)` 다.
 */
@Composable
fun SearchScrim(onDismiss: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(onClick = onDismiss),
    )
}

/**
 * 자동완성 패널.
 *
 * iOS `SearchTab/SuggestionPanel.swift` 를 그대로 옮겼다. 그쪽 주석이 경계를 적어
 * 뒀다 — **랭킹과 갈래는 서버가 정한다.** 프론트가 같은 규칙을 두 번 구현하면
 * iOS·Android 가 갈린다. 여기서는 그 순서를 **섹션으로 배치만** 한다.
 *
 * 높이 프레임을 걸지 않는다 — 정해진 높이를 주면 늘 그만큼을 차지하고 내용을 세로
 * 가운데에 놓아서, 내용이 적으면 검색창에서 떨어져 보이고 많으면 위로 넘쳐 검색창을
 * 덮는다(iOS 실측). 패널은 내용만큼만 아래로 자란다.
 */
@Composable
fun SuggestionPanel(
    draft: String,
    suggestions: List<Suggestion>,
    topWork: ContentDetail?,
    onCommit: (String, EntityType?) -> Unit,
    onOpenWork: (ContentDetail) -> Unit,
    onSelectPlace: (Suggestion) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = IOS.gutter, vertical = 2.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = IOS.label, spotColor = IOS.label)
                .clip(RoundedCornerShape(14.dp))
                .background(IOS.systemBackground),
    ) {
        if (draft.isEmpty()) {
            SectionLabel("추천 검색어")
            // 글자만 두면 **고른 것이 장소인지 작품인지 알 수 없어** 늘 작품 탭이
            // 열렸다(iOS 실측: 북촌한옥마을을 눌러도 작품 탭). 갈래를 함께 넘긴다.
            RECOMMENDED.forEach { (term, type) ->
                SuggestRow(
                    type = type,
                    text = term,
                    detail = "",
                    onClick = { onCommit(term, type) },
                )
            }
            return@Column
        }

        if (topWork != null) {
            WorkCard(work = topWork, alias = aliasOf(topWork, suggestions), onClick = { onOpenWork(topWork) })
            RowLine()
        }

        // 장소 행은 **3개까지만** — 패널이 내용만큼 자라므로 키보드까지 닿지 않게
        // 총높이를 여기서 붙든다. 나머지 장소는 연관 검색어 칩으로도 닿는다.
        val placeItems = suggestions.filter { it.type == EntityType.place }.take(3)
        if (placeItems.isNotEmpty()) {
            SectionLabel("장소")
            placeItems.forEach { item ->
                SuggestRow(
                    type = EntityType.place,
                    text = item.name,
                    detail = item.subtitle.orEmpty(),
                    alias = aliasOf(item),
                    onClick = { onSelectPlace(item) },
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            SectionLabel("연관 검색어")
            LazyRow(
                contentPadding = PaddingValues(horizontal = IOS.gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                items(suggestions.size) { index ->
                    val item = suggestions[index]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(IOS.systemGray6)
                                .clickable {
                                    // 칩도 갈래대로 움직인다 — 장소 칩은 그 장소로.
                                    if (item.type == EntityType.place) {
                                        onSelectPlace(item)
                                    } else {
                                        onCommit(item.name, item.type)
                                    }
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        TypeIcon(item.type, IOS.label, 13.dp)
                        Text(item.name, style = IOS.caption, color = IOS.label)
                    }
                }
            }
        }
    }
}

/**
 * 검색창이 비었을 때 보여 주는 추천. **갈래별로 하나씩** 둔다 — 셋이 각각 작품·
 * 인물·장소라서 어느 탭이 열리는지도 함께 익힌다. iOS 와 같은 세 낱말이다.
 */
private val RECOMMENDED =
    listOf(
        "도깨비" to EntityType.content,
        "공유" to EntityType.person,
        "북촌한옥마을" to EntityType.place,
    )

/** 별칭으로 걸렸으면 그 표기를, 아니면 첫 별칭(영어 제목)을 보여 준다. */
private fun aliasOf(
    work: ContentDetail,
    suggestions: List<Suggestion>,
): String? {
    val matched = suggestions.firstOrNull { it.type == EntityType.content && it.id == work.id }
    val term = matched?.matchedTerm
    if (term != null && term != work.title) return term
    return work.aliases?.firstOrNull()
}

/** `matchedTerm` 은 실제로 걸린 표기다 — 이름과 다를 때만 보여 준다. */
private fun aliasOf(item: Suggestion): String? = item.matchedTerm?.takeIf { it != item.name }

@Composable
private fun WorkCard(
    work: ContentDetail,
    alias: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(IOS.gutter),
    ) {
        RemoteImage(
            url = work.posterUrl?.toString(),
            modifier = Modifier.size(width = 46.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(work.title, style = IOS.headline, color = IOS.label)
            val meta =
                listOfNotNull(
                    work.broadcaster,
                    work.releaseYear?.toString(),
                    work.genres?.joinToString(" "),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
            Text(meta, style = IOS.caption, color = IOS.secondaryLabel)
            if (alias != null) AliasBadge(alias)
        }
        Text("↖", style = IOS.caption, color = IOS.tertiaryLabel)
    }
}

@Composable
private fun AliasBadge(text: String) {
    Text(
        text = text,
        style = IOS.caption2,
        color = IOS.accent,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(IOS.accent.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = IOS.caption,
        color = IOS.secondaryLabel,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = IOS.gutter)
                .padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SuggestRow(
    type: EntityType,
    text: String,
    detail: String,
    alias: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = IOS.gutter, vertical = 11.dp),
    ) {
        TypeIcon(type, IOS.secondaryLabel, 18.dp)
        Text(text, style = IOS.body, color = IOS.label)
        if (alias != null) AliasBadge(alias)
        Spacer(Modifier.weight(1f))
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = IOS.caption,
                color = IOS.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 갈래 아이콘 — iOS 는 `film` · `person` · `mappin.and.ellipse` 다. */
@Composable
private fun TypeIcon(
    type: EntityType,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    when (type) {
        // **필름 스트립을 직접 그린다.** iOS 는 `film` 인데 material-icons-core 에
        // 같은 모양이 없고, 재생 삼각형으로 대신하면 한눈에 다르게 보인다.
        EntityType.content -> FilmIcon(tint, Modifier.size(size))

        EntityType.person -> Icon(Icons.Filled.Person, null, Modifier.size(size), tint)

        EntityType.place -> Icon(Icons.Filled.Place, null, Modifier.size(size), tint)
    }
}

@Composable
private fun RowLine() {
    Box(
        Modifier
            .padding(start = IOS.gutter)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(IOS.separator),
    )
}

/**
 * 드릴다운 헤더 — `<` 로 한 단계 나온다.
 *
 * iOS `Rows.swift` 의 `DetailHeader` 와 같다. **검색 결과 목록에도 같은 헤더를
 * 쓴다** — 상세에서 `<` 를 눌러 검색 결과까지 온 사용자가 거기서 더 나갈 자리를
 * 못 찾았던 것이 검색 탭 버그정리에서 잡힌 문제다.
 *
 * 아래에 구분선을 두지 않는다 — iOS 에 없다(실측).
 */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowLeft,
            contentDescription = "뒤로",
            // iOS 는 이 화살표를 검정으로 그린다(실측). 강조색이 아니다.
            tint = IOS.label,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IOS.headline,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = IOS.caption,
                    color = IOS.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * SF Symbols 의 `film` — 가운데 화면과 좌우 스프로킷 구멍이 있는 필름 조각.
 *
 * 둥근 사각형 테두리 안에 양옆으로 작은 네모 셋씩. 작은 크기(13~18dp)에서도
 * 필름으로 읽히도록 구멍은 채워서 그린다.
 */
@Composable
private fun FilmIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2, h * 0.13f),
            size =
                androidx.compose.ui.geometry
                    .Size(w - stroke, h * 0.74f),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(w * 0.12f),
            style = Stroke(width = stroke),
        )
        // 좌우 스프로킷 구멍 셋씩.
        val holeW = w * 0.13f
        val holeH = h * 0.13f
        repeat(3) { i ->
            val y = h * (0.23f + i * 0.235f)
            drawRect(
                tint,
                Offset(w * 0.11f, y),
                androidx.compose.ui.geometry
                    .Size(holeW, holeH),
            )
            drawRect(
                tint,
                Offset(w * 0.76f, y),
                androidx.compose.ui.geometry
                    .Size(holeW, holeH),
            )
        }
        // 가운데 세로 칸막이 둘 — 필름 화면을 나눈다.
        drawLine(tint, Offset(w * 0.32f, h * 0.13f), Offset(w * 0.32f, h * 0.87f), strokeWidth = stroke * 0.8f)
        drawLine(tint, Offset(w * 0.68f, h * 0.13f), Offset(w * 0.68f, h * 0.87f), strokeWidth = stroke * 0.8f)
    }
}
