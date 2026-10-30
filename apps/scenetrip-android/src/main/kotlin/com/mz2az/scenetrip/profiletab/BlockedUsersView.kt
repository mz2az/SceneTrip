package com.mz2az.scenetrip.profiletab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.BlockedUsersStore
import com.mz2az.scenetrip.data.BlockedUsersUi
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationServerClient
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.BlockedUser
import com.mz2az.scenetrip.ui.EnvelopeIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSEmptyState
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.NoSignIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom
import kotlinx.coroutines.launch

/**
 * 마이페이지 「차단한 사용자」 (MZ2AZ-461) — 내가 차단한 사람과 차단한 때, 줄마다 「차단 풀기」. iOS `ProfileTab/BlockedUsersView.swift`.
 *
 * 가입한 사람만 연다(차단은 로그인해야 한다). 풀면 그 사람의 리뷰·후기가 다음에 받을 때부터 다시 보인다.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BlockedUsersView(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BlockedUsersStore(ModerationServerClient(InstallIdentity.of(context))) }
    val ui by store.ui.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.reload() }
    // 로그아웃·탈퇴하면 남의 목록을 남겨 두지 않는다.
    LaunchedEffect(AuthStore.signedIn) { if (!AuthStore.signedIn) onClose() }

    IOSSheet(listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("차단한 사용자"), leading = tr("닫기"), onLeading = onClose)
            when {
                ui.phase == BlockedUsersUi.Phase.LOADING -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = IOS.accent)
                    }
                }

                ui.phase == BlockedUsersUi.Phase.FAILED -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(tr("차단 목록을 불러오지 못했어요"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                        Text(
                            tr("다시 시도"),
                            fontSize = 17.sp,
                            color = IOS.accent,
                            modifier =
                                Modifier
                                    .clickable { scope.launch { store.reload() } }
                                    .defaultMinSize(
                                        minHeight = 44.dp,
                                    ).padding(12.dp),
                        )
                    }
                }

                ui.items.isEmpty() -> {
                    IOSEmptyState(
                        tr("차단한 사용자가 없어요"),
                        tr("리뷰나 여행후기의 「⋯」 에서 작성자를 차단할 수 있어요"),
                    ) { NoSignIcon(IOS.secondaryLabel, it) }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                store.reload()
                                refreshing = false
                            }
                        },
                    ) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
                            items(ui.items, key = { it.id }) { user ->
                                BlockedUserRow(
                                    user,
                                    unblocking = user.id in ui.unblocking,
                                    onUnblock = {
                                        scope.launch {
                                            if (store.unblock(user)) {
                                                // 여행후기 목록이 그 사람의 글을 다시 보이게 받는다.
                                                CommunityStore.getInstance(context).invalidateAndRefreshInBackground()
                                            }
                                        }
                                    },
                                )
                                IOSListDivider(start = 16.dp)
                            }
                            item {
                                Text(
                                    tr("차단한 사람의 리뷰와 여행후기는 보이지 않아요. 차단한 사람에게는 알리지 않아요."),
                                    fontSize = 13.sp,
                                    color = IOS.secondaryLabel,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    ui.message?.let { message ->
        IOSAlert(message, null, listOf(IOSAction(tr("확인"), IOSRole.CANCEL) {}), onDismiss = store::clearMessage)
    }
}

@Composable
private fun BlockedUserRow(
    user: BlockedUser,
    unblocking: Boolean,
    onUnblock: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        // 닉네임과 차단한 날이 따로 읽히지 않게 한 줄로 묶는다.
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(user.nickname, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label, maxLines = 1)
            Text(ModerationRules.blockedOn(user), fontSize = 12.sp, color = IOS.secondaryLabel)
        }
        if (unblocking) {
            CircularProgressIndicator(Modifier.size(20.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
        } else {
            val label = tr("%s 차단 풀기").format(user.nickname)
            Text(
                tr("차단 풀기"),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.accent,
                modifier =
                    Modifier
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                        }.clickable(onClick = onUnblock)
                        .defaultMinSize(minHeight = 44.dp)
                        .padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * 마이페이지 「문의하기」 (MZ2AZ-461, App Store 1.2 — 연락처 공개). 주소를 **글자로** 보인다 — 메일 앱이 없는 기기에서도 주소를
 * 길게 눌러 복사할 수 있다. 옆의 「메일 쓰기」 는 메일 앱을 연다. 주소는 [ModerationRules.SUPPORT_EMAIL] 한 곳에 있다.
 * iOS `SupportContactRow`.
 */
@Composable
fun SupportContactRow(tint: androidx.compose.ui.graphics.Color) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.33.dp).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { EnvelopeIcon(tint, Modifier.size(18.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("문의하기"), fontSize = 15.sp, color = IOS.label)
            SelectionContainer {
                Text(ModerationRules.SUPPORT_EMAIL, fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        }
        val sendLabel = tr("메일 쓰기")
        Text(
            sendLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.accent,
            modifier =
                Modifier
                    .semantics {
                        // 접근성 힌트 — 어느 주소로 쓰는지 읽어 준다(iOS `accessibilityHint`).
                        contentDescription = "$sendLabel, ${ModerationRules.SUPPORT_EMAIL}"
                        role = Role.Button
                    }.clickable {
                        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse(ModerationRules.SUPPORT_MAIL_URI))
                        try {
                            context.startActivity(mail)
                        } catch (_: ActivityNotFoundException) {
                            // 메일 앱이 없다 — 주소가 글자로 보이니 길게 눌러 복사할 수 있다.
                        }
                    }.defaultMinSize(minHeight = 44.dp)
                    .padding(vertical = 12.dp),
        )
    }
}
