package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.DraftPhoto
import com.mz2az.scenetrip.ui.IOS
import java.util.UUID

@Composable
internal fun CommunityPlacementEditor(
    story: String,
    photos: List<DraftPhoto>,
    placements: Map<UUID, CommunityPhotoPlacement>,
    locked: Boolean,
    onChange: (Map<UUID, CommunityPhotoPlacement>) -> Unit,
) {
    val paragraphs = CommunityArticle.paragraphs(story)
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (photos.isNotEmpty()) {
            Text(tr("사진 배치"), style = IOS.headline, color = IOS.label)
            Text(tr("빈 줄로 문단을 나누고 사진을 넣을 위치를 골라 주세요"), style = IOS.footnote, color = IOS.secondaryLabel)
            photos.forEachIndexed { index, photo ->
                val place = CommunityArticle.placement(photo.id, index, paragraphs.size, placements)
                PlacementControls(index, place, paragraphs, locked) { onChange(placements + (photo.id to it)) }
            }
        }
        if (paragraphs.size >
            CommunityArticle.PARAGRAPH_LIMIT
        ) {
            Text(tr("본문은 64개 문단까지 쓸 수 있어요"), color = IOS.systemRed, style = IOS.footnote)
        }
    }
}

@Composable
private fun PlacementControls(
    index: Int,
    place: CommunityPhotoPlacement,
    paragraphs: List<String>,
    locked: Boolean,
    onChange: (CommunityPhotoPlacement) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(IOS.systemGray6, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("사진 %d").format(index + 1), style = IOS.subheadline.copy(fontWeight = FontWeight.SemiBold), color = IOS.label)
        PlacementPicker(index, place.afterParagraph, paragraphs, !locked) { onChange(place.copy(afterParagraph = it)) }
        PlacementField("사진 설명", place.caption, !locked, "photo-caption-$index") { onChange(place.copy(caption = it)) }
        PlacementCounter(
            tr("사진 설명 %d/%d").format(place.captionLength, CommunityPhotoPlacement.CAPTION_LIMIT),
            place.captionLength <= CommunityPhotoPlacement.CAPTION_LIMIT,
        )
        PlacementField("사진 출처 또는 이용 조건", place.credit, !locked, "photo-credit-$index") { onChange(place.copy(credit = it)) }
        PlacementCounter(
            tr("출처 %d/%d").format(place.creditLength, CommunityPhotoPlacement.CREDIT_LIMIT),
            place.creditLength <= CommunityPhotoPlacement.CREDIT_LIMIT,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("AI 변환 사진"), style = IOS.footnote, color = IOS.label, modifier = Modifier.weight(1f))
            Switch(
                place.aiGenerated,
                { onChange(place.copy(aiGenerated = it)) },
                enabled = !locked,
                modifier = Modifier.semantics { contentDescription = tr("AI 변환 사진") }.testTag("photo-ai-$index"),
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = IOS.systemGreen,
                        uncheckedTrackColor = IOS.systemGray3,
                        uncheckedThumbColor = Color.White,
                    ),
            )
        }
    }
}

@Composable
private fun PlacementPicker(
    index: Int,
    position: Int,
    paragraphs: List<String>,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    menu = true
                }.padding(vertical = 10.dp)
                .testTag("photo-position-$index"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("사진 위치"), style = IOS.footnote, color = IOS.secondaryLabel, modifier = Modifier.weight(1f))
            Text(if (position == 0) tr("본문 시작") else tr("%d번째 문단 뒤").format(position), color = IOS.accent, style = IOS.subheadline)
            Text(" ⌄", color = IOS.accent)
        }
        DropdownMenu(menu && enabled, { menu = false }, Modifier.heightIn(max = 360.dp).background(IOS.systemBackground)) {
            for (choice in 0..paragraphs.size) {
                val label = if (choice == 0) tr("본문 시작") else tr("%d번째 문단 뒤").format(choice) + " · " + paragraphs[choice - 1].take(24)
                DropdownMenuItem(text = { Text(label, style = IOS.subheadline, color = IOS.label) }, onClick = {
                    menu = false
                    onChange(choice)
                })
            }
        }
    }
}

@Composable
private fun PlacementField(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value,
        onChange,
        enabled = enabled,
        textStyle = IOS.subheadline.copy(color = IOS.label),
        cursorBrush = SolidColor(IOS.accent),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .testTag(tag)
                .semantics { contentDescription = tr(label) },
        decorationBox = { inner ->
            if (value.isEmpty()) Text(tr(label), style = IOS.subheadline, color = IOS.tertiaryLabel)
            inner()
        },
    )
}

@Composable
private fun PlacementCounter(
    text: String,
    valid: Boolean,
) {
    Text(text, style = IOS.caption2, color = if (valid) IOS.secondaryLabel else IOS.systemRed)
}
