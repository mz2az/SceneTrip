package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.auth.GuestDataDeletion
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSRole

/** 비회원의 설치 기록 삭제 문구 — iOS `ProfileDeviceDataSection` 과 같은 한국어 원문이다(MZ2AZ-471). */
object DeviceDataDeletionCopy {
    const val ROW_KO = "이 기기의 기록 지우기"
    const val CONFIRM_TITLE_KO = "이 기기의 기록을 지울까요?"
    const val CONFIRM_MESSAGE_KO = "이 기기의 장바구니·코스·찜이 모두 지워지고 되돌릴 수 없어요."
    const val DELETED_KO = "이 기기의 기록을 지웠어요."
    const val SESSION_REQUIRED_KO = "가입한 계정의 기록이에요. 로그인한 뒤 마이페이지에서 회원 탈퇴를 이용해 주세요."
    const val FAILED_KO = "기록을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요."
    const val FOOTER_KO = "이 기기의 발자취도 함께 지워져요."

    /** 번역표에 있어야 하는 한국어 원문 전부. */
    val copyKeys: List<String> = listOf(ROW_KO, CONFIRM_TITLE_KO, CONFIRM_MESSAGE_KO, DELETED_KO, SESSION_REQUIRED_KO, FAILED_KO, FOOTER_KO)

    /** 결과에 맞는 알림 한 줄. */
    fun message(outcome: GuestDataDeletion.Outcome): String =
        when (outcome) {
            GuestDataDeletion.Outcome.DELETED -> DELETED_KO
            GuestDataDeletion.Outcome.SESSION_REQUIRED -> SESSION_REQUIRED_KO
            GuestDataDeletion.Outcome.FAILED -> FAILED_KO
        }
}

/** 비회원의 설치 기록 삭제. 가입자의 회원 탈퇴와 구분해 확인한다(iOS `ProfileDeviceDataSection`). */
@Composable
fun ProfileDeviceDataSection() {
    if (!GuestDataDeletion.isVisible(signedIn = AuthStore.signedIn)) return
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    ProfileSection(null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable(enabled = !AuthStore.busy) { confirming = true }
                    .padding(horizontal = 16.dp),
        ) {
            Text(tr(DeviceDataDeletionCopy.ROW_KO), fontSize = 15.sp, color = IOS.systemRed)
            if (AuthStore.busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IOS.secondaryLabel)
            }
        }
    }
    Text(
        tr(DeviceDataDeletionCopy.FOOTER_KO),
        fontSize = 13.sp,
        color = IOS.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
    if (confirming) {
        IOSAlert(
            title = tr(DeviceDataDeletionCopy.CONFIRM_TITLE_KO),
            message = tr(DeviceDataDeletionCopy.CONFIRM_MESSAGE_KO),
            actions =
                listOf(
                    IOSAction(tr("취소"), IOSRole.CANCEL) {},
                    IOSAction(tr("지우기"), IOSRole.DESTRUCTIVE) {
                        AuthStore.deleteDeviceData { outcome -> message = tr(DeviceDataDeletionCopy.message(outcome)) }
                    },
                ),
            onDismiss = { confirming = false },
        )
    }
    message?.let {
        IOSAlert(
            title = it,
            message = null,
            actions = listOf(IOSAction(tr("확인"), IOSRole.CANCEL) {}),
            onDismiss = { message = null },
        )
    }
}
