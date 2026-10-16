package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.KakaoDirectionsNotice
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.tr
import kotlinx.coroutines.delay

/** 위치기반서비스 동의 시트·마이페이지 줄·카카오 알림의 한국어 원문. iOS `LocationConsentSheet` 와 한 글자까지 같아야 하고, 영어는 번역표에서 온다. */
object LocationConsentCopy {
    const val TITLE_KO = "위치기반서비스 이용 동의"
    const val BODY_KO =
        "현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. " +
            "위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다."
    const val LINK_KO = "위치기반서비스 이용약관"
    const val ALLOW_KO = "동의하고 계속"

    /** 마이페이지 토글 줄과 꼬리말. */
    const val TOGGLE_KO = "위치기반서비스 이용"
    const val FOOTNOTE_KO = "끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요."

    /** 길 안내 성공 때마다(사용자 요청 하나에 한 번) 위에 잠깐 띄우는 띠. */
    const val KAKAO_NOTICE_KO = "길 안내를 위해 카카오에 현재 위치를 보냈어요."

    val copyKeys: List<String> =
        listOf(
            TITLE_KO,
            BODY_KO,
            LINK_KO,
            ALLOW_KO,
            TOGGLE_KO,
            FOOTNOTE_KO,
            KAKAO_NOTICE_KO,
            com.mz2az.scenetrip.data.LocationConsentRequired.MESSAGE_KO,
        )
}

/** iOS `LocationConsentSheet` — 「위치기반서비스 이용 동의」. 높이·단추 배치는 AI 안내와 같은 [ConsentSheet] 다. */
@Composable
fun LocationConsentSheet(
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    ConsentSheet(
        title = tr(LocationConsentCopy.TITLE_KO),
        disclosure = tr(LocationConsentCopy.BODY_KO),
        linkTitle = tr(LocationConsentCopy.LINK_KO),
        link = LegalLinks.LOCATION_SERVICES,
        allowTitle = tr(LocationConsentCopy.ALLOW_KO),
        onAllow = onAllow,
        onCancel = onCancel,
    )
}

/**
 * iOS `.locationConsentSheet(gate, onCancel:)`. 시트를 띄우는 화면에 한 번 둔다.
 * 허용하면 시트가 사라진 **다음** 대기 중 동작을 한 번 실행하고, 허용 없이 닫히면(취소·바깥 누르기·끌어 내려 닫기)
 * 사라진 뒤 [onCancel] 을 한 번 부른다.
 */
@Composable
fun LocationConsentSheetHost(
    gate: AIConsentGate,
    onCancel: (() -> Unit)? = null,
) {
    val latestCancel by rememberUpdatedState(onCancel)
    DisposableEffect(gate) {
        gate.onCancel = { latestCancel?.invoke() }
        onDispose { gate.onCancel = null }
    }
    val subscribe = remember(gate) { gate::addListener }
    rememberConsentTick(subscribe)
    val presented = gate.isPresented
    LaunchedEffect(presented) {
        if (!presented) gate.didDismiss()
    }
    if (presented) LocationConsentSheet(onAllow = gate::allow, onCancel = gate::cancel)
}

/**
 * iOS `.kakaoDirectionsNotice(notice)` — 길 안내가 성공했을 때 위쪽에 4초 동안 뜨는 알림 띠. 눌림을 가로채지 않는다.
 * 부모 안 위쪽 가운데에 둔다([modifier] 로 상태바 여백을 얹는다).
 */
@Composable
fun KakaoDirectionsNoticeBanner(
    notice: KakaoDirectionsNotice,
    modifier: Modifier = Modifier,
) {
    val subscribe = remember(notice) { notice::addListener }
    rememberConsentTick(subscribe)
    val id = notice.visibleRequest
    LaunchedEffect(id) {
        if (id != null) {
            delay(4_000)
            notice.hide(id)
        }
    }
    if (id != null) {
        Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
            Text(
                tr(LocationConsentCopy.KAKAO_NOTICE_KO),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.onAccent,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IOS.pinDeep)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
