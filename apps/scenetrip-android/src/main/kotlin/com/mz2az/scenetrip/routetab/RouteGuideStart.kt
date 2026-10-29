package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS

/** 가이드 시작 화면이 권하는 질문 넷 (MZ2AZ-444). 되는 질문만 둔다 — 안 되는 예시는 첫인상을 깎는다. */
object RouteGuideStartCopy {
    val PROMPTS_KO = listOf("주변 촬영지 알려줘", "주변 음식점 알려줘", "주변 카페 알려줘", "주변 명소 알려줘")
}

/**
 * 코스를 담기 전에도 쓸 수 있는 가이드 입구. **열기만 해서는 모델을 호출하지 않는다.** iOS `RouteGuideStart`를
 * 옮긴 것이다.
 *
 * - 「지도 주변 촬영지 보기」: AI 없이 지도 중심 주변 촬영지를 본다([onOpenNearby]).
 * - 기준 바꾸기: 기기 위치와 지도 중심을 오간다([onChangeAnchor], 둘 다 있을 때만).
 * - 「코스 추천받기 (AI)」: 일정짜기 마법사를 연다([onOpenPlanner]).
 * - 추천 질문 넷: 대화가 비어 있을 때만([showQuestions]), 질문할 수 있을 때만([canAsk]) 눌린다.
 * - 장바구니: 담아 둔 장소 미리보기와 열기([onOpenCart]).
 */
@Composable
fun RouteGuideStart(
    hasLocation: Boolean,
    anchorActionTitle: String,
    onChangeAnchor: (() -> Unit)?,
    cartPlaces: List<String>,
    onOpenCart: (() -> Unit)?,
    onOpenNearby: (() -> Unit)?,
    onOpenPlanner: (() -> Unit)?,
    showQuestions: Boolean,
    canAsk: Boolean,
    onAsk: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(tr("무엇을 도와드릴까요?"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Text(tr("장소를 코스에 담기 전에도 주변을 찾아볼 수 있어요."), fontSize = 12.sp, color = IOS.secondaryLabel)
        if (onOpenNearby != null) {
            BorderedPill(tr("지도 주변 촬영지 보기"), 15.sp, FontWeight.SemiBold, 32.dp, onOpenNearby)
        }
        if (hasLocation && onChangeAnchor != null) {
            BorderedPill(anchorActionTitle, 12.sp, FontWeight.Normal, 28.dp, onChangeAnchor)
        }
        HorizontalDivider(color = IOS.separator, thickness = 0.5.dp)
        if (onOpenPlanner != null) {
            BorderedPill(tr("코스 추천받기 (AI)"), 15.sp, FontWeight.SemiBold, 32.dp, onOpenPlanner)
            Text(tr("AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요."), fontSize = 11.sp, color = IOS.secondaryLabel)
        }
        if (showQuestions) {
            Text(tr("AI에게 주변 장소 물어보기"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            RouteGuideStartCopy.PROMPTS_KO.forEach { prompt ->
                val text = tr(prompt)
                Text(
                    text,
                    fontSize = 12.sp,
                    color = IOS.label,
                    modifier =
                        Modifier
                            .alpha(if (canAsk) 1f else 0.4f)
                            .clip(CircleShape)
                            .background(IOS.systemGray6)
                            .clickable(enabled = canAsk) { onAsk(text) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(tr("추천 장소는 골라 담으세요. 일정 변경 요청은 초안에 반영돼요."), fontSize = 11.sp, color = IOS.secondaryLabel)
        }
        if (onOpenCart != null) {
            HorizontalDivider(color = IOS.separator, thickness = 0.5.dp)
            Text(
                if (cartPlaces.isEmpty()) tr("장바구니에 담긴 장소가 없어요.") else cartPlaces.take(3).joinToString(" · "),
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            BorderedPill(tr("장바구니 장소 보기"), 12.sp, FontWeight.Normal, 28.dp, onOpenCart)
        }
    }
}

/**
 * iOS `.buttonStyle(.bordered)` — 유리 패널 위에서는 거의 흰(#FAFAFB, 패널 #F4F4F5 위 실측) 알약에 강조색 글자.
 * 높이는 iOS 실측(15pt 글자 32pt, 12pt 글자 28pt, 좌우 12pt)이다 — 44dp 로 키우면 가이드 첫 화면이 iOS 보다 늘어진다.
 */
@Composable
private fun BorderedPill(
    label: String,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .height(height)
                .clip(CircleShape)
                .background(BORDERED_FILL)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
    ) {
        Text(label, fontSize = size, fontWeight = weight, color = IOS.accent)
    }
}

private val BORDERED_FILL =
    androidx.compose.ui.graphics
        .Color(0xFFFAFAFB)
