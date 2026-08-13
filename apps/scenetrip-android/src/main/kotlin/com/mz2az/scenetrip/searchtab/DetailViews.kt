package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.IOS

/**
 * 드릴다운 2단 — 작품 상세.
 *
 * iOS `SearchTab/ContentDetailView.swift` 를 옮긴 것이다. 목록에서 넘어온
 * `ContentSummary` 로 먼저 그리고, 상세(`GET /contents/{id}`)가 오면 채운다.
 * 상세에만 있는 것이 줄거리·출연진이라 그 줄은 도착 후에 뜬다.
 *
 * **별칭은 빼 둔다.** 한 줄에 담기지 않아 늘 뒤가 잘렸고, 잘린 별칭은 아무 값도
 * 하지 않는다 — 사용자가 그 이름으로 찾아 들어온 뒤라 이미 아는 정보다.
 */
@Composable
fun ContentDetailView(
    summary: ContentSummary,
    places: List<PlaceSummary>,
    loading: Boolean,
    saved: (Long) -> Boolean,
    detailOf: suspend (Long) -> ContentDetail?,
    onBack: () -> Unit,
    onSelectPlace: (PlaceSummary) -> Unit,
    onSave: (PlaceSummary) -> Unit,
) {
    var detail by remember(summary.id) { mutableStateOf<ContentDetail?>(null) }
    LaunchedEffect(summary.id) { detail = detailOf(summary.id) }

    // "출연: 이름, 이름, …" — 넷까지만. iOS 와 같은 문구·같은 개수다.
    val castLine =
        detail?.cast.orEmpty().map { it.name }.let {
            if (it.isEmpty()) "" else "출연: " + it.take(4).joinToString(", ")
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailHeader(title = summary.title, onBack = onBack)
        LazyColumn {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 12.dp),
                ) {
                    RemoteImage(
                        url = summary.posterUrl?.toString(),
                        modifier =
                            Modifier
                                .size(width = 92.dp, height = 124.dp)
                                .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(summary.meta, style = IOS.subheadline, color = IOS.secondaryLabel)
                        if (castLine.isNotEmpty()) {
                            Text(castLine, style = IOS.caption, color = IOS.secondaryLabel)
                        }
                    }
                }
                detail?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = IOS.gutter),
                    )
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 14.dp),
                ) {
                    Text("촬영지", style = IOS.headline, color = IOS.label)
                    Text("${summary.placeCount}곳", style = IOS.caption, color = IOS.secondaryLabel)
                }
            }

            if (loading || places.isEmpty()) {
                item {
                    Text(
                        text = if (loading) "" else "촬영지를 불러오지 못했습니다",
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    )
                }
            } else {
                // 지도 핀과 같은 배열이므로 행 번호가 곧 핀 번호다.
                itemsIndexed(places) { index, place ->
                    PlaceRow(
                        place = place,
                        number = index + 1,
                        saved = saved(place.id),
                        onTap = { onSelectPlace(place) },
                        onAdd = { onSave(place) },
                    )
                    Box(
                        Modifier
                            .padding(start = IOS.gutter)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(IOS.separator),
                    )
                }
            }
        }
    }
}

/**
 * 드릴다운 3단 — 촬영지 상세.
 *
 * iOS `SearchTab/PlaceDetailView.swift` 를 옮긴 것이다. 상세에만 있는 것이
 * **작품별 장면**(`scenes`)이라 그 절은 도착 후에 뜬다.
 *
 * **담기 버튼은 두 개가 아니다.** 장바구니에 담기는 단위는 장소이므로 이 화면의
 * 저장 버튼과 목록 행의 `+` 가 같은 것을 부른다.
 */
@Composable
fun PlaceDetailView(
    summary: PlaceSummary,
    saved: Boolean,
    detailOf: suspend (Long) -> PlaceDetail?,
    onBack: () -> Unit,
    onToggleSave: () -> Unit,
    onOpenScene: (Scene) -> Unit,
) {
    var detail by remember(summary.id) { mutableStateOf<PlaceDetail?>(null) }
    val context = LocalContext.current
    LaunchedEffect(summary.id) { detail = detailOf(summary.id) }

    Column(modifier = Modifier.fillMaxWidth()) {
        DetailHeader(title = summary.name, subtitle = summary.address.orEmpty(), onBack = onBack)
        LazyColumn {
            item {
                RemoteImage(
                    url = (detail?.imageUrl ?: summary.imageUrl)?.toString(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(horizontal = IOS.gutter)
                            .clip(RoundedCornerShape(10.dp)),
                )
                summary.type?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        modifier =
                            Modifier
                                .padding(horizontal = IOS.gutter, vertical = 12.dp)
                                .clip(CircleShape)
                                .background(IOS.systemGray6)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // 담긴 상태에서 다시 누르면 뺀다 — 목록 행과 같은 규칙이다.
                //
                // 두 버튼이 한 줄이다. 담기는 계약이 적어 둔 세 경로 중 하나이고,
                // 네이버 지도는 외부 앱/브라우저로 넘긴다 — 길찾기와 영업정보는
                // 우리가 만들 것이 아니다.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = IOS.gutter),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(IOS.capsuleButton))
                                .background(IOS.accent)
                                .clickable(onClick = onToggleSave)
                                // iOS 버튼 높이는 30.3pt 다. 6 차가 36.3 이라 해서
                                // 키웠다가 7 차에 40.4 로 커졌다 — 6 차 값이 틀렸다.
                                .padding(vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // iOS 는 `Label(_, systemImage:)` 라 **아이콘이 함께**
                            // 붙는다 — 담기 전에는 가방, 담긴 뒤에는 체크다.
                            if (saved) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = IOS.systemBackground,
                                    modifier = Modifier.size(17.dp),
                                )
                            } else {
                                // iOS 는 `bag.badge.plus` — **가방에 ＋ 배지**다.
                                // 쇼핑카트로 대신하면 모양이 한눈에 다르다.
                                BagPlusIcon(IOS.systemBackground, Modifier.size(17.dp))
                            }
                            Text(
                                text = if (saved) "담김 · 누르면 빼기" else "장바구니에 담기",
                                style = IOS.subheadlineSemibold,
                                color = IOS.systemBackground,
                            )
                        }
                    }
                    val naver = detail?.naverPlaceUrl?.toString()
                    if (naver != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(IOS.capsuleButton))
                                    .background(IOS.systemGray6)
                                    .clickable { openUrl(context, naver) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // iOS 는 `Label` 이라 **아이콘이 글자 앞**에 온다.
                                // `arrow.up.right.square` — 네모 안의 대각선
                                // 화살표이고, 밖으로 나간다는 표시다.
                                ExternalLinkIcon(IOS.accent, Modifier.size(15.dp))
                                Text(
                                    text = "네이버 지도",
                                    style = IOS.subheadlineSemibold,
                                    color = IOS.accent,
                                )
                            }
                        }
                    }
                }
            }

            val scenes = detail?.scenes.orEmpty()
            if (scenes.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = IOS.gutter, vertical = 16.dp),
                    ) {
                        Text("이 장소의 장면", style = IOS.headline, color = IOS.label)
                        Text(
                            text = "${scenes.size}개 작품이 이곳에서 촬영",
                            style = IOS.caption,
                            color = IOS.secondaryLabel,
                        )
                    }
                    // **가로로 넘기는 카드**다. 세로 목록으로 만들면 iOS 와 다른
                    // 화면이 된다(대조 검사에서 놓쳤던 부분).
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = IOS.gutter),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(scenes.size) { index ->
                            SceneCard(scene = scenes[index], onClick = { onOpenScene(scenes[index]) })
                        }
                    }
                }
            }
        }
    }
}

/** 외부 브라우저·앱으로 넘긴다. 네이버 지도의 길찾기·영업정보는 우리 몫이 아니다. */
private fun openUrl(
    context: android.content.Context,
    url: String,
) {
    runCatching {
        context.startActivity(
            android.content
                .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 가로로 넘기는 장면 카드.
 *
 * iOS `PlaceDetailView.swift` 의 `SceneCard` 와 같다. **장면 스틸을 쓴다**
 * (`sceneImageUrl`). 한동안 포스터로 대신했는데, 그러면 한 작품의 장면이 여럿일 때
 * 카드가 전부 같은 그림이 됐다 — "이 장면이 찍힌 곳" 을 보여 주는 것이 이 앱의
 * 핵심이라 포스터로는 대체가 안 된다. 스틸이 없는 장면만 포스터로 물러선다.
 */
@Composable
private fun SceneCard(
    scene: Scene,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(190.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemBackground)
                .border(1.dp, IOS.systemGray5, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
    ) {
        RemoteImage(
            url = (scene.sceneImageUrl ?: scene.posterUrl)?.toString(),
            modifier = Modifier.size(width = 190.dp, height = 110.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.height(96.dp).padding(10.dp),
        ) {
            Text(
                text = scene.contentTitle,
                style = IOS.caption2.copy(fontWeight = FontWeight.Bold),
                color = IOS.accent,
            )
            Text(
                text = scene.sceneDescription ?: "장면 설명이 아직 없습니다",
                style = IOS.subheadline,
                color = if (scene.sceneDescription == null) IOS.secondaryLabel else IOS.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 장면 팝업. 카드에서 두 줄로 잘린 설명의 전문을 본다.
 *
 * iOS `ScenePopup` 을 옮긴 것이다. **담기 버튼을 두지 않는다** — 장바구니에 담기는
 * 단위는 **장소**인데(계약의 `CartItemCreate` 가 `placeId` 를 받는다), 장면 팝업은
 * "이 장소에서 어느 작품의 어떤 장면을 찍었나" 를 보는 자리다. 거기서 담으면
 * 사용자는 장면을 담는다고 생각하는데 실제로는 장소가 담긴다.
 *
 * 닫기 버튼을 **사진 위에 겹친다.** 사진이 팝업 맨 위를 꽉 채우고 있어 바깥 여백에
 * 두면 버튼 하나 때문에 위쪽이 벌어진다. 원 배경을 까는 이유는 포스터마다 밝기가
 * 제각각이라 선 아이콘만 두면 밝은 사진에서 보이지 않기 때문이다.
 */
@Composable
fun ScenePopup(
    scene: Scene,
    placeName: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        // **`DialogProperties` 에는 딤을 끄는 값이 없다.** `decorFitsSystemWindows`
        // 는 인셋 처리이지 딤과 무관한데 그걸 끄고 다 됐다고 믿었다가, 딤이 20% 가
        // 아니라 68% 로 나왔다(5 차 검사 — 기본 60% 와 우리 20% 가 겹쳤다).
        DisableDialogDim()
        Box(
            contentAlignment = Alignment.BottomCenter,
            // iOS 는 **좌·우·아래를 9pt 띄운 떠 있는 카드**이고 뒷배경 딤이 20% 다.
            // 화면을 꽉 채우고 60% 로 어둡게 하면 첫인상이 완전히 다른 화면이 된다
            // (4 차 검사).
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = IOS.DIM))
                    .clickable(onClick = onClose),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        // Dialog 는 시스템 내비게이션 바 인셋을 먹지 않아 카드가
                        // 화면 맨 아래까지 내려온다 — 그 몫을 여기서 더한다.
                        .padding(start = IOS.popupInset, end = IOS.popupInset) // iOS 팝업 카드는 모서리가 훨씬 둥글고(유효 반지름 ≈36) 바탕이
                        // 흰색이 아니라 systemGray6 다(실측 235,235,236).
                        .clip(RoundedCornerShape(IOS.popupCorner))
                        .background(IOS.popupSurface)
                        .padding(18.dp),
            ) {
                Box {
                    RemoteImage(
                        url = (scene.sceneImageUrl ?: scene.posterUrl)?.toString(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                    )
                    // 보이는 원은 28 인데 누를 수 있는 자리는 44 다 — 애플이 권하는
                    // 최소 터치 크기이고, 그보다 작으면 손가락이 자꾸 빗나간다.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(44.dp)
                                .clickable(onClick = onClose),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "닫기",
                                tint = IOS.systemBackground,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                Text(
                    text = scene.contentTitle,
                    style = IOS.caption2.copy(fontWeight = FontWeight.Bold),
                    color = IOS.accent,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(IOS.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(placeName, style = IOS.headline, color = IOS.label)
                scene.sceneDescription?.let {
                    Text(it, style = IOS.subheadline, color = IOS.label)
                }
            }
        }
    }
}

/** iOS 의 `arrow.up.right.square` — 네모 테두리 안의 대각선 화살표. */
@Composable
private fun ExternalLinkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val s = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(s / 2, s / 2),
            size =
                androidx.compose.ui.geometry
                    .Size(w - s, w - s),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(w * 0.18f),
            style = Stroke(width = s),
        )
        // 왼쪽 아래에서 오른쪽 위로 가는 화살표와 촉 둘.
        drawLine(tint, Offset(w * 0.32f, w * 0.68f), Offset(w * 0.68f, w * 0.32f), strokeWidth = s)
        drawLine(tint, Offset(w * 0.44f, w * 0.32f), Offset(w * 0.68f, w * 0.32f), strokeWidth = s)
        drawLine(tint, Offset(w * 0.68f, w * 0.32f), Offset(w * 0.68f, w * 0.56f), strokeWidth = s)
    }
}

/** iOS 의 `bag.badge.plus` — 손잡이 달린 가방 오른쪽 위에 ＋ 배지. */
@Composable
private fun BagPlusIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.10f
        // 가방 몸통.
        drawRoundRect(
            color = tint,
            topLeft = Offset(s / 2, h * 0.34f),
            size =
                androidx.compose.ui.geometry
                    .Size(w * 0.68f, h * 0.60f),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(w * 0.14f),
            style = Stroke(width = s),
        )
        // 손잡이 — 몸통 위로 솟은 반원.
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.14f),
            size =
                androidx.compose.ui.geometry
                    .Size(w * 0.36f, h * 0.40f),
            style = Stroke(width = s),
        )
        // 오른쪽 위 ＋ 배지.
        val cx = w * 0.80f
        val cy = h * 0.24f
        val arm = w * 0.15f
        drawLine(tint, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = s)
        drawLine(tint, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = s)
    }
}
