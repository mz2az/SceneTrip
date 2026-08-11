package com.mz2az.scenetrip.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 의 시스템 색·글자·간격을 그대로 옮겨 둔 값.
 *
 * ## 왜 Material 기본값을 쓰지 않는가
 *
 * [ADR 0002](../../../../../../../docs/architecture/adr/0002-product-stack-spring-python-native-mobile.md)
 * 가 네이티브 두 벌을 택하면서 **"두 앱이 어긋나지 않게 한다"** 를 스스로 검증
 * 항목으로 걸었다. 어긋남은 동작뿐 아니라 **겉모습**에서도 생긴다 — 실제로 화면
 * 구조만 옮기고 색과 컴포넌트를 Material 기본값에 맡겼더니 "영 다른 앱" 으로
 * 보였다(실측, 2026-08-12).
 *
 * 그래서 **iOS 가 기준이다.** 아래 값은 SwiftUI 가 쓰는 시스템 색의 실제 RGB 이고,
 * 짐작이 아니라 애플이 공개한 값이다. iOS 쪽이 `Color.accentColor` 처럼 시스템
 * 기본을 그대로 쓰므로(커스텀 에셋이 없다) 여기서도 같은 기본값을 적는다.
 *
 * **한쪽을 고치면 다른 쪽도 고친다.** 이 파일의 값이 iOS 소스와 갈리는 순간 두 앱은
 * 다른 제품이 된다.
 */
object IOS {
    // --- 색 ---------------------------------------------------------------
    //
    // iOS 는 커스텀 accent 에셋을 두지 않는다. 즉 `Color.accentColor` 는 시스템
    // 기본인 **systemBlue** 다. 여기에 보라색 Material 기본을 쓰면 그 순간 갈린다.
    // **화면에서 실제로 잰 값이다.** 문서의 systemBlue 는 #007AFF 지만 iOS 26 이
    // 실제로 칠하는 값은 #0088FF 였다(스크린샷 픽셀 측정). 교과서 값을 쓰면 두 앱을
    // 나란히 놓았을 때 파랑이 미묘하게 달라 보인다. 빨강도 같은 이유다.
    val accent = Color(0xFF0088FF)
    val systemRed = Color(0xFFFF383C)

    val systemBackground = Color(0xFFFFFFFF)
    val systemGray3 = Color(0xFFC7C7CC)
    val systemGray6 = Color(0xFFF2F2F7)

    /** 세그먼트 컨트롤의 트랙. systemGray6 보다 살짝 어둡다 — 실측 #EEEEEF. */
    val segmentTrack = Color(0xFFEEEEEF)

    /** `.primary` — 완전한 검정이 아니라 label 색이다. */
    val label = Color(0xFF000000)

    /** `.secondary` · `.tertiary` 는 label 에 불투명도를 건 것이다. */
    val secondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.60f)
    val tertiaryLabel = Color(0xFF3C3C43).copy(alpha = 0.30f)

    /** `Divider()` 의 색. */
    val separator = Color(0xFF3C3C43).copy(alpha = 0.29f)

    /**
     * 번호 배지와 지도 핀의 그러데이션. `NaverMapView.swift` 의 `PinImage` 와
     * **같은 값이어야 한다** — 목록의 3번과 지도의 3번이 다른 색이면 짝이 안 보인다.
     */
    val pinLight = Color(0xFF8FCCF7) // 하늘 (0.56, 0.80, 0.97)
    val pinDeep = Color(0xFF7A68ED) // 보라 (0.48, 0.41, 0.93)

    // --- 글자 -------------------------------------------------------------
    //
    // iOS 텍스트 스타일의 기본 크기(Large)다. SwiftUI 의 `.headline` 등이
    // 이 값으로 그려진다.
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val footnote = TextStyle(fontSize = 13.sp)
    val subheadline = TextStyle(fontSize = 15.sp)
    val subheadlineSemibold = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp)
    val caption = TextStyle(fontSize = 12.sp)
    val caption2 = TextStyle(fontSize = 11.sp)
    val caption2Heavy = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black)

    // --- 간격 -------------------------------------------------------------
    //
    // 화면 좌우 여백은 iOS 가 14pt 로 통일돼 있다 (행·칩줄·세그먼트 전부).
    val gutter = 14.dp

    /** 시트 스냅 비율. `BottomSheet.swift` 가 "두 앱이 같은 숫자" 라고 못 박은 값. */
    const val DETENT_COLLAPSED = 0.14f
    const val DETENT_MEDIUM = 0.48f
}

/** iOS `Divider()` 와 같은 선. 목록에서는 왼쪽을 14pt 띄운다. */
@Composable
fun iosSeparatorColor(): Color = IOS.separator
