package com.mz2az.scenetrip.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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

    /** 찜한 작품의 하트. iOS `Color.pink` — 빨강(`systemRed`)과 다른 톤이다. */
    val systemPink = Color(0xFFFF2D55)

    /** "저장 안 됨" 배지. iOS `Color.orange`. */
    val systemOrange = Color(0xFFFF9500)

    /** 버스 구간 칩. iOS `Color(.systemGreen)` — 애플 공개값이다(아직 화면에서 재지 않았다). */
    val systemGreen = Color(0xFF34C759)

    val systemBackground = Color(0xFFFFFFFF)
    val systemGray3 = Color(0xFFC7C7CC)

    /** 검색어 지우기 원. iOS 는 연파랑이다 — 실측 #BFE1FF. */
    val clearCircle = Color(0xFFBFE1FF)

    /** 장면 카드 테두리. iOS `systemGray5`. */
    val systemGray5 = Color(0xFFE5E5EA)

    /** 온보딩 지도 조각의 격자선, 페이지 점의 비활성 색. iOS `systemGray4`. */
    val systemGray4 = Color(0xFFD1D1D6)
    val systemGray6 = Color(0xFFF2F2F7)

    /** `.bordered` 버튼의 회색 채움. iOS `tertiarySystemFill` — (118,118,128) 12%. */
    val tertiaryFill = Color(0x1F767680)

    /** 세그먼트 컨트롤의 트랙. systemGray6 보다 살짝 어둡다 — 실측 #EEEEEF. */
    val segmentTrack = Color(0xFFEEEEEF)

    /**
     * 장면 팝업 카드의 바탕. iOS 는 반투명 재질이라 뒤가 희미하게 비치는데,
     * 흰 화면 위에서 잰 값이 #EBEBEB 다. systemGray6(#F2F2F7)를 쓰면 파란 기가
     * 돌아 다르게 보인다(6 차 실측).
     */
    val popupSurface = Color(0xFFEBEBEB)

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
    // 전부 [textBase] 위에 얹는다 — `Text(style = …)` 는 테마 기본값을 **대신하므로** 여기에도
    // 줄 높이·줄바꿈 규칙이 들어 있어야 한다.
    val headline = textBase.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val footnote = textBase.copy(fontSize = 13.sp)
    val subheadline = textBase.copy(fontSize = 15.sp)
    val subheadlineSemibold = textBase.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = textBase.copy(fontSize = 17.sp)
    val caption = textBase.copy(fontSize = 12.sp)
    val caption2 = textBase.copy(fontSize = 11.sp)

    /** iOS `.heavy`. **한글은 Bold(700)로 옮긴다** — iOS 는 한글을 heavy·bold 거의 같은 굵기로
     * 그리는데 Android 는 800·900 이 눈에 띄게 굵다(2026-09-28 실측, 획 두께 2.0pt 대 2.67dp). */
    val caption2Heavy = textBase.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)

    // --- 간격 -------------------------------------------------------------
    //
    // 화면 좌우 여백은 iOS 가 14pt 로 통일돼 있다 (행·칩줄·세그먼트 전부).
    val gutter = 14.dp

    // --- 표면 --------------------------------------------------------------
    //
    // **모서리·딤·재질을 한곳에 모은다.** 쓰는 곳마다 숫자를 적었더니 저장 버튼만
    // 라운드렉트, 장바구니만 직각처럼 제각각 갈렸다(4 차 검사).

    /** iOS 의 `Capsule()` 버튼 — 높이의 절반이라 알약이 된다. */
    val capsuleButton = 100.dp

    /** 시트 위쪽 모서리. iOS 실측 유효 반지름 ≈36 — 연속 곡률이라 커 보인다. */
    val sheetCorner = 30.dp

    /** 팝업 카드 모서리. 시트보다 조금 더 둥글다. */
    val popupCorner = 36.dp

    /** 팝업이 화면 가장자리에서 띄우는 거리. iOS 는 좌·우·아래 9pt. */
    val popupInset = 9.dp

    // --- 경고창 (UIAlertController) -----------------------------------------
    //
    // iOS 의 시스템 경고창은 Material 의 `AlertDialog` 와 생김새가 아주 다르다 —
    // Material 은 왼쪽 정렬에 버튼이 오른쪽 아래로 몰리고, iOS 는 **가운데 정렬에
    // 버튼이 가로로 반씩** 나뉜다. 그대로 두면 이 화면만 다른 앱처럼 보인다.

    /** iOS 경고창의 고정 너비. 화면 폭과 무관하게 270pt 다. */
    val alertWidth = 270.dp

    /** 경고창 모서리. 팝업 카드(36)보다 훨씬 각지다. */
    val alertCorner = 14.dp

    /** 경고창 버튼 한 칸의 높이. */
    val alertButton = 44.dp

    /** 경고창 구분선. iOS 는 1px 이 아니라 **머리카락 굵기**다. */
    val hairline = 0.5.dp

    /** 팝업 뒷배경 딤. iOS 는 **20%** 다 — 60% 로 두면 훨씬 어둡다. */
    const val DIM = 0.20f

    /** 시트 스냅 비율. `BottomSheet.swift` 가 "두 앱이 같은 숫자" 라고 못 박은 값. */
    const val DETENT_COLLAPSED = 0.14f
    const val DETENT_MEDIUM = 0.48f
}

/**
 * iOS 글자의 공통 바탕 — **줄 높이 1.2em·자간 0·단어 단위 줄바꿈.**
 *
 * - 줄 높이 **1.3em**: iOS 한글은 한 줄 칸이 약 1.2배(13→16), 줄과 줄 사이가 약 1.33배(13→17.3)다.
 *   Android 한글 글꼴(Noto Sans CJK)은 제 값이 약 1.44배라 줄마다 높았다. 그 사이 1.3 으로 둔다.
 *   **`Trim.Both` 여야 한 줄짜리에도 먹는다** — `Trim.None` 이면 첫 줄은 글꼴 제 높이를 그대로
 *   두고 둘째 줄부터만 줄어, 한 줄 글은 하나도 안 바뀌고 여러 줄 글만 빽빽해졌다(2026-09-28 실측).
 * - 줄바꿈: 한글을 **어절 단위**로 꺾는다(`WordBreak.Phrase`). 기본값은 글자 단위라
 *   「케이팝 데몬 헌 / 터스」처럼 단어 중간에서 끊겼다(iOS 는 「케이팝 / 데몬 헌터스」).
 *   **로케일을 한국어로 박아야 먹는다** — Phrase 는 글자의 언어를 보고 동작하는데, 이 앱을 쓰는
 *   외국인의 기기 언어(en 등)를 따르면 한글에 적용되지 않았다. 앱 글자는 한국어다.
 */
val textBase =
    TextStyle(
        letterSpacing = 0.sp,
        lineHeight = 1.3.em,
        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
        lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
        localeList = LocaleList("ko"),
    )

/**
 * 앱 전체 글자 기본값 — **줄 높이는 글꼴 자체값, 자간 0.** SwiftUI 의 기본이 이렇다.
 *
 * Material3 의 기본 타이포(`bodyLarge`: 줄 높이 24sp, 자간 0.5sp)를 그대로 두면 모든 `Text`
 * 가 이것을 물려받아, 13sp 한 줄이 24dp 높이가 되고 글자가 벌어진다. 행·칩·탭 라벨이
 * 전부 부풀고 줄바꿈 자리까지 iOS 와 갈렸다(2026-09-28 화면 대조, 차이 24건 중 1위). 값은 [textBase].
 */
fun iosTypography(): Typography {
    val base = Typography()

    fun TextStyle.ios() = merge(textBase)
    return Typography(
        displayLarge = base.displayLarge.ios(),
        displayMedium = base.displayMedium.ios(),
        displaySmall = base.displaySmall.ios(),
        headlineLarge = base.headlineLarge.ios(),
        headlineMedium = base.headlineMedium.ios(),
        headlineSmall = base.headlineSmall.ios(),
        titleLarge = base.titleLarge.ios(),
        titleMedium = base.titleMedium.ios(),
        titleSmall = base.titleSmall.ios(),
        bodyLarge = base.bodyLarge.ios(),
        bodyMedium = base.bodyMedium.ios(),
        bodySmall = base.bodySmall.ios(),
        labelLarge = base.labelLarge.ios(),
        labelMedium = base.labelMedium.ios(),
        labelSmall = base.labelSmall.ios(),
    )
}

/** iOS `Divider()` 와 같은 선. 목록에서는 왼쪽을 14pt 띄운다. */
@Composable
fun iosSeparatorColor(): Color = IOS.separator
