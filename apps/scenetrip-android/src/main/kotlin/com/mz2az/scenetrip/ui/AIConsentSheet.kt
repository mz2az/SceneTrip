package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.tr

/** 안내 시트의 한국어 원문. iOS `AIConsentSheet` 와 한 글자까지 같아야 하고, 영어는 번역표에서 온다. */
object AIConsentCopy {
    const val TITLE_KO = "AI 기능 사용 안내"
    const val BODY_KO =
        "AI 가이드와 AI 코스 만들기는 입력한 내용, 대화, 관심 작품·여행 조건·코스 맥락을 " +
            "미국 오리건에 있는 Amazon Bedrock(AWS)으로 보내 답변과 일정을 만듭니다. " +
            "이름·이메일은 보내지 않아요. AI 답변은 틀릴 수 있어요."
    const val LINK_KO = LegalLinks.PRIVACY_TITLE_KO
    const val ALLOW_KO = "허용하고 계속"
    const val CANCEL_KO = "취소"

    /** 마이페이지 토글 줄과 꼬리말. */
    const val TOGGLE_KO = "AI 기능 사용"
    const val FOOTNOTE_KO = "끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요."

    val copyKeys: List<String> = listOf(TITLE_KO, BODY_KO, LINK_KO, ALLOW_KO, CANCEL_KO, TOGGLE_KO, FOOTNOTE_KO)
}

/** iOS `ConsentSheet` 의 높이 한도(포인트 → dp). 내용이 이 사이면 내용만큼, 넘으면 스크롤·크게 끌기. */
private val MINIMUM_HEIGHT = 240.dp
private val MAXIMUM_HEIGHT = 480.dp

/** 손잡이 줄(위 5 + 5)이 차지하는 높이 — [IOSSheet] 가 높이가 둘일 때 그린다. */
private val GRIP_HEIGHT = 10.dp

/**
 * 첫 질문·첫 AI 코스 만들기·설정에서 공통으로 쓰는 안내. iOS `ConsentSheet`(PR #312 의 내용에 맞춘 높이).
 *
 * - 높이는 **내용만큼**이다(240~480dp) — 처음 한 번은 반쯤으로 열렸다가 내용 높이를 재면 그 높이로 맞춘다.
 *   더 크면 스크롤되고 위로 끌면 크게 열린다(`[고정, 크게]` 두 높이).
 * - 단추는 **세로로 쌓인 전체 폭**이다. 허용(캡슐, 파랑)이 위, 취소(회색 글자)가 아래라 큰 글자에서도
 *   글자가 잘리지 않는다.
 * - 취소·바깥 누르기·끌어 내리기는 모두 취소다.
 */
@Composable
fun ConsentSheet(
    title: String,
    disclosure: String,
    linkTitle: String,
    link: String,
    allowTitle: String,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var contentPx by remember { mutableIntStateOf(0) }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val fixed: Dp? =
        if (contentPx == 0) {
            null // 아직 재지 못했다 — iOS `.medium`.
        } else {
            (with(density) { contentPx.toDp() } + GRIP_HEIGHT + navBottom).coerceIn(MINIMUM_HEIGHT, MAXIMUM_HEIGHT)
        }
    IOSSheet(
        detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE),
        onDismiss = onCancel,
        fixedHeight = fixed,
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth().onSizeChanged { contentPx = it.height }.padding(24.dp),
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label)
                Text(disclosure, fontSize = 17.sp, color = IOS.label)
                Box(
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { openExternalUrl(context, link) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(linkTitle, fontSize = 17.sp, color = IOS.accent, textDecoration = TextDecoration.Underline)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .clip(CircleShape)
                                .background(IOS.accent)
                                .clickable(role = Role.Button, onClick = onAllow)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Text(
                            allowTitle,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IOS.onAccent,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onCancel)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(tr(AIConsentCopy.CANCEL_KO), fontSize = 15.sp, color = IOS.secondaryLabel, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** iOS `AIConsentSheet` — 「AI 기능 사용 안내」. */
@Composable
fun AIConsentSheet(
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    ConsentSheet(
        title = tr(AIConsentCopy.TITLE_KO),
        disclosure = tr(AIConsentCopy.BODY_KO),
        linkTitle = tr(AIConsentCopy.LINK_KO),
        link = LegalLinks.PRIVACY,
        allowTitle = tr(AIConsentCopy.ALLOW_KO),
        onAllow = onAllow,
        onCancel = onCancel,
    )
}

/**
 * 구독 함수가 알려 줄 때마다 다시 그려지게 하는 수. 값이 아니라 「바뀌었다」 만 쓴다 — 단위 시험이 도는
 * 순수 클래스([AIConsentGate]·[com.mz2az.scenetrip.data.VersionedConsentStore])를 화면에 잇는 자리다.
 */
@Composable
fun rememberConsentTick(subscribe: (() -> Unit) -> (() -> Unit)): Int {
    val tick = remember(subscribe) { mutableIntStateOf(0) }
    DisposableEffect(subscribe) {
        val unsubscribe = subscribe { tick.intValue += 1 }
        onDispose { unsubscribe() }
    }
    return tick.intValue
}

/**
 * iOS `.aiConsentSheet(gate)`. 시트를 띄우는 화면에 한 번 둔다.
 * 허용하면 시트가 사라진 **다음** 대기 중 동작을 한 번 실행한다(`didDismiss`). 끌어 내려 닫기도 취소다.
 */
@Composable
fun AIConsentSheetHost(gate: AIConsentGate) {
    val subscribe = remember(gate) { gate::addListener }
    rememberConsentTick(subscribe)
    val presented = gate.isPresented
    LaunchedEffect(presented) {
        if (!presented) gate.didDismiss()
    }
    if (presented) AIConsentSheet(onAllow = gate::allow, onCancel = gate::cancel)
}
