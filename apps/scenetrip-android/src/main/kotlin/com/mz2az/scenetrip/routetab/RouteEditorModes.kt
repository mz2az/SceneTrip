package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.coach.coachTarget
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.searchtab.PlaceDetailView
import com.mz2az.scenetrip.searchtab.ScenePopup
import com.mz2az.scenetrip.ui.ChevronLeftIcon
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.FullScreenDialogWindow
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.ListBulletIcon
import com.mz2az.scenetrip.ui.MapIcon
import kotlinx.coroutines.launch

// 계획 화면의 **보기/편집 · 지도 켜고 끄기 · 장소 상세** 부품 (MZ2AZ-547 P4 E1·E4·E6, 웹 `route/editor/RouteEditor.tsx`, iOS `RouteEditorModes.swift`).
// 규칙(무엇을 묻고 언제 지도를 맞추나)은 `RouteEditorRules.kt` 에 있고 여기와 `RouteEditorView` 는 그 답을 화면에 옮긴다.

/**
 * 확인용 뒷문(MZ2AZ-547 P4, iOS `-editorMode`·`-editorMap`·`-editorAdd`). `MainActivity` 가 인텐트 값으로 채운다.
 * `-e editorMode edit` 은 저장된 코스도 편집으로, `--ez editorMap true` 는 지도를 펼친 채로, `-e editorAdd menu|saved|map` 은
 * ＋ 메뉴·보관함에서 추가·지도에서 추가를 연 채로 연다. 한 번 쓰면 비운다(다음에 여는 코스까지 따라가지 않게).
 */
object RouteEditorDebug {
    var mode: String? = null
    var mapOpen: Boolean = false
    var add: String? = null

    /** 이번 편집 화면이 쓸 값을 꺼내고 비운다. */
    internal fun take(): Triple<String?, Boolean, String?> {
        val taken = Triple(mode, mapOpen, add)
        mode = null
        mapOpen = false
        add = null
        return taken
    }
}

/** 보기의 머리줄 — ‹ 뒤로 · 코스 이름(못 고침) · 「편집」(E1). iOS `viewBar`. */
@Composable
internal fun RouteViewBar(
    title: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                // 편집 머리줄과 같은 68 — 보기 ↔ 편집에서 아래가 들썩이지 않게.
                .height(68.dp)
                .background(IOS.systemBackground)
                .padding(horizontal = 8.dp),
    ) {
        Text(
            title,
            style = IOS.headline,
            color = IOS.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 220.dp),
        )
        val backLabel = tr("뒤로")
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .semantics { contentDescription = backLabel }
                    .clickable(role = Role.Button, onClick = onBack),
        ) {
            ChevronLeftIcon(IOS.accent, Modifier.size(18.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onEdit)
                    .padding(horizontal = 8.dp),
        ) {
            Text(tr("편집"), fontSize = 17.sp, color = IOS.accent)
        }
    }
}

/** 「지도」/「목록만」 알약 — 왼쪽 아래(16, 16), 검은 바탕(E4). 오른쪽은 해태와 ＋ 가 세로로 쓴다. */
@Composable
internal fun RouteMapPill(
    toggle: RouteMapToggle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = toggle.pill == RouteMapToggle.Pill.LIST_ONLY
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .padding(16.dp)
                // 처음 안내(C4) 첫 단계가 가리킨다 — 여백 안쪽 알약만.
                .coachTarget("editor-map")
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(IOS.label)
                .semantics { selected = toggle.isOpen }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
    ) {
        if (open) {
            ListBulletIcon(IOS.systemBackground, Modifier.size(15.dp))
        } else {
            MapIcon(IOS.systemBackground, Modifier.size(15.dp))
        }
        Text(if (open) tr("목록만") else tr("지도"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.systemBackground)
    }
}

/**
 * 줄 사진의 ⓘ 로 연 장소 상세(E6) — 화면 전체. 담기(보관함)는 검색 탭과 같은 장바구니에 쓴다.
 * 장면 카드를 누르면 검색 탭처럼 장면 창이 뜬다.
 */
@Composable
internal fun RoutePlaceDetailCover(
    place: PlaceSummary,
    cart: CartStore,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val placesApi = remember { PlacesApi(API_BASE) }
    var scene by remember { mutableStateOf<Scene?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        Box(Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
            PlaceDetailView(
                summary = place,
                saved = cart.contains(place.id),
                detailOf = { id -> apiResult { placesApi.getPlace(id) } },
                onBack = onClose,
                onToggleSave = {
                    scope.launch { if (cart.contains(place.id)) cart.remove(place.id) else cart.add(place.id, null) }
                },
                onOpenScene = { scene = it },
            )
            scene?.let { ScenePopup(scene = it, placeName = place.name, onClose = { scene = null }) }
        }
    }
}
