package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationServerClient
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.ReportReason
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.EllipsisIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSFormSection
import com.mz2az.scenetrip.ui.IOSMenuItem
import com.mz2az.scenetrip.ui.IOSMenuPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.NoSignIcon
import com.mz2az.scenetrip.ui.ReportBubbleIcon
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

/*
 * 리뷰·여행후기의 「⋯」 — 「신고」 와 「이 사용자 차단」 (MZ2AZ-461, App Store 심사 지침 1.2).
 * iOS `Reviews/ModerationViews.swift` 를 옮긴 것이다.
 */

/** 목록 화면 하나가 쓰는 신고·차단 조종기. 시트·확인 창·안내는 같은 화면의 [ModerationSheets] 한 곳에 단다. */
@Composable
fun rememberModerationController(): ModerationController {
    val context = LocalContext.current
    return remember { ModerationController(ModerationServerClient(InstallIdentity.of(context))) }
}

/**
 * 리뷰·여행후기의 「⋯」. 자기 글에는 그리지 않는다([ModerationRules.showsMenu]) — 부르는 쪽이 거른다. 누르면 「신고」 와
 * (작성자가 있는 남의 글이면) 「이 사용자 차단」 이 뜨고, 고르면 [ModerationSheets] 가 시트·확인 창을 올린다.
 */
@Composable
fun ModerationMenu(
    target: ModerationTarget,
    canBlock: Boolean,
    controller: ModerationController,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = tr("신고·차단 메뉴")
    // 그림은 작아도 누르는 자리는 넉넉히(44dp).
    Box(
        modifier =
            modifier
                .size(44.dp)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }.clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        EllipsisIcon(IOS.secondaryLabel, Modifier.size(20.dp, 18.dp))
        if (open) {
            IOSMenuPopover(
                items =
                    buildList {
                        add(
                            IOSMenuItem(tr("신고"), icon = { tint, mod -> ReportBubbleIcon(tint, mod) }) {
                                controller.askReport(target)
                            },
                        )
                        if (canBlock) {
                            add(
                                IOSMenuItem(tr("이 사용자 차단"), destructive = true, icon = { tint, mod -> NoSignIcon(tint, mod) }) {
                                    controller.askBlock(target)
                                },
                            )
                        }
                    },
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 신고 시트·차단 확인 창·안내를 올릴 자리. 목록 화면에 한 번 단다.
 * [onBlocked] — 차단했다. 그 글을 지우고 목록을 다시 받는다(서버가 이후 목록에서 그 사람의 글을 뺀다).
 */
@Composable
fun ModerationSheets(
    controller: ModerationController,
    onBlocked: (ModerationTarget) -> Unit,
) {
    val ui by controller.ui.collectAsState()
    val scope = rememberCoroutineScope()
    ui.reporting?.let { target -> ReportSheet(target, controller) }
    ui.confirmingBlock?.let { target ->
        IOSAlert(
            tr("이 사용자를 차단할까요?"),
            tr("이 사람의 리뷰·후기가 더 이상 보이지 않아요. 마이페이지의 「차단한 사용자」에서 풀 수 있어요."),
            listOf(
                IOSAction(tr("취소"), IOSRole.CANCEL) {},
                IOSAction(tr("차단"), IOSRole.DESTRUCTIVE) {
                    scope.launch { if (controller.block(target)) onBlocked(target) }
                },
            ),
            onDismiss = controller::closeBlockConfirm,
        )
    }
    ui.notice?.let { notice ->
        IOSAlert(notice, null, listOf(IOSAction(tr("확인"), IOSRole.CANCEL) {}), onDismiss = controller::clearNotice)
    }
}

/** 신고 — 이유 여섯 중 하나와 설명(선택). 보내면 「접수됐어요」 를 보이고 닫는다. iOS `ReportSheet`. */
@Composable
private fun ReportSheet(
    target: ModerationTarget,
    controller: ModerationController,
) {
    val ui by controller.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var reason by remember(target) { mutableStateOf<ReportReason?>(null) }
    var detail by remember(target) { mutableStateOf("") }
    // 보낸 결과 — 알림으로 보인다.
    var outcome by remember(target) { mutableStateOf<ModerationRules.ReportOutcome?>(null) }
    val close = controller::closeReport

    // 신고 중에 세션이 풀려 로그인을 청하면 이 시트가 스스로 닫힌다(iOS `ReportSheetPresence`).
    LaunchedEffect(AuthStore.showingSignIn) { if (AuthStore.showingSignIn) close() }

    IOSSheet(listOf(SheetDetent.LARGE), onDismiss = close, interactiveDismissEnabled = !ui.sending) {
        Column(Modifier.fillMaxSize().background(IOS.systemGray6).imePadding()) {
            IOSSheetToolbar(
                title = tr("신고"),
                leading = tr("취소"),
                onLeading = close,
                trailing = tr("보내기"),
                trailingEnabled = reason != null,
                trailingBusy = ui.sending,
                onTrailing = {
                    val chosen = reason ?: return@IOSSheetToolbar
                    scope.launch { outcome = controller.report(target, chosen, detail) }
                },
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                IOSFormSection(tr("신고 이유"), card = IOS.systemBackground) {
                    ModerationRules.reasons.forEachIndexed { index, item ->
                        if (index >
                            0
                        ) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = IOS.separator.copy(alpha = 0.3f),
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = reason == item
                                    }.clickable { reason = item }
                                    .padding(horizontal = 16.dp),
                        ) {
                            Text(ModerationRules.label(item), fontSize = 17.sp, color = IOS.label, modifier = Modifier.weight(1f))
                            if (reason == item) CheckmarkIcon(IOS.accent, Modifier.size(16.dp))
                        }
                    }
                }
                FormFooter(tr("운영자가 확인하고 규칙을 어긴 글은 내려요."))
                IOSFormSection(tr("설명"), card = IOS.systemBackground) {
                    BasicTextField(
                        value = detail,
                        onValueChange = { detail = ModerationRules.capped(it) },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = IOS.body.copy(color = IOS.label),
                        cursorBrush = SolidColor(IOS.accent),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        decorationBox = { inner ->
                            if (detail.isEmpty()) Text(tr("자세한 내용 (선택)"), fontSize = 17.sp, color = IOS.tertiaryLabel)
                            inner()
                        },
                    )
                }
                FormFooter("${detail.length}/${ModerationRules.DETAIL_LIMIT}")
            }
        }
    }
    outcome?.let { shown ->
        IOSAlert(
            ModerationRules.message(shown),
            null,
            listOf(IOSAction(tr("확인"), IOSRole.CANCEL) { if (shown.finishes) close() }),
            onDismiss = { outcome = null },
        )
    }
}

@Composable
private fun FormFooter(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = IOS.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}
