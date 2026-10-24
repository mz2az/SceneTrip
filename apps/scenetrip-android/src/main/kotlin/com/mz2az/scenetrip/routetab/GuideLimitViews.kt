package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.data.usageNow
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.CheckCircleOutlineIcon
import com.mz2az.scenetrip.ui.HourglassIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.WarningTriangleIcon

/*
 * 가이드 대화창의 **오류 줄·한도 안내·남은 양** (MZ2AZ-481, iOS `RouteGuideSheet.failed`·`RouteGuideSheetLimit`).
 * 규칙은 [RouteGuideFailure]·[GuideLimit], 상태는 [RouteGuideSession.limit]·[RouteGuideSession.quota] 에 있다.
 */

/** 「다시 시도」 — 강조색 14% 알약에 ↻. iOS `retryButton`. 누르는 자리는 높이 44dp 이상이다. */
@Composable
fun GuideRetryChip(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(IOS.accent.copy(alpha = 0.14f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        ArrowClockwiseIcon(IOS.accent, Modifier.size(13.dp))
        Text(tr("다시 시도"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IOS.accent)
    }
}

/**
 * 실패 한 줄과, 다시 해 볼 만하면 「다시 시도」. **단추는 같은 질문을 같은 멱등 키로 다시 보낸다**([RouteGuideSession.retry]) —
 * 서버가 이미 답을 만들어 두었으면 그 답이 오고 모델을 다시 부르지 않는다. 서버가 키를 모르면 단추가 없다.
 */
@Composable
fun GuideFailureLine(
    failure: RouteGuideFailure,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(tr("AI 가이드 오류"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WarningTriangleIcon(IOS.systemOrange, Modifier.padding(top = 1.dp).size(15.dp))
            Text(failure.message, fontSize = 13.sp, color = IOS.systemOrange)
        }
        if (canRetry) GuideRetryChip(onRetry)
    }
}

/**
 * 한도(429 `GUIDE_LIMIT_REACHED`)에 걸린 동안의 안내와, 풀린 뒤의 한 줄.
 *
 * **자동으로 다시 보내지 않는다.** 풀리는 때를 알면 그때까지 전송을 잠그고 남은 분을 말한다. 풀리면 「이제 다시 물어볼 수 있어요」 와,
 * 걸렸던 질문이 남아 있으면 「다시 시도」(같은 멱등 키).
 */
@Composable
fun GuideLimitNotice(
    limit: GuideChat.Limit,
    canRetry: Boolean,
    now: Long,
    onRetry: () -> Unit,
    onOpenPlanner: (() -> Unit)?,
) {
    when (limit) {
        GuideChat.Limit.Clear -> {
            Unit
        }

        GuideChat.Limit.Lifted -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CheckCircleOutlineIcon(IOS.secondaryLabel, Modifier.size(15.dp))
                    Text(tr("이제 다시 물어볼 수 있어요"), fontSize = 13.sp, color = IOS.secondaryLabel)
                }
                if (canRetry) GuideRetryChip(onRetry)
            }
        }

        is GuideChat.Limit.Reached -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // 남은 분은 시간이 가면 준다 — 받은 순간의 숫자를 그대로 두지 않는다.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HourglassIcon(IOS.systemOrange, Modifier.padding(top = 1.dp).size(15.dp))
                    Text(limit.block.message(now), fontSize = 13.sp, color = IOS.systemOrange)
                }
                // 풀리는 때를 모르면 잠그지 않는다 — 걸렸던 질문을 바로 다시 보낼 수 있다(같은 멱등 키).
                if (canRetry) GuideRetryChip(onRetry)
                if (GuideLimit.PLANNER_HINT) GuidePlannerHint(onOpenPlanner)
            }
        }
    }
}

/**
 * 일정짜기 마법사 안내. **무료라고 말하지 않는다** — 지금 설정에서는 마법사도 모델을 부른다. 끄는 자리는
 * [GuideLimit.PLANNER_HINT].
 */
@Composable
private fun GuidePlannerHint(onOpenPlanner: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(tr("새 일정은 「AI 로 짜기」에서도 만들 수 있어요"), fontSize = 12.sp, color = IOS.secondaryLabel)
        if (onOpenPlanner != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .background(IOS.accent.copy(alpha = 0.14f))
                        .clickable(onClick = onOpenPlanner)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                SparklesIcon(IOS.accent, Modifier.size(13.dp))
                Text(tr("AI 로 짜기 열기"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IOS.accent)
            }
        }
    }
}

/**
 * 입력창 위 한 줄 — 「3번 더 물어볼 수 있어요 · 12분 뒤 다시 채워져요」.
 *
 * 서버가 마지막 답에 실어 준 값이다(`RateLimit-*`). 헤더가 없으면(옛 서버, 이번 실행에서 아직 안 물음) 줄이 없고, 넉넉할 때도
 * 없다. 한도 안내가 떠 있는 동안에는 그 안내가 같은 말을 하므로 숨긴다.
 */
@Composable
fun GuideRemainingLine(session: RouteGuideSession) {
    val now = usageNow()
    if (session.isLimited) return
    val text = GuideLimit.remaining(session.quota, now).text ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
    ) {
        BubblesIcon(IOS.secondaryLabel, Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, color = IOS.secondaryLabel)
    }
}
