package com.mz2az.scenetrip.newcourse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteEditorDraft
import com.mz2az.scenetrip.routetab.RouteSpan
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.ui.BagIcon
import com.mz2az.scenetrip.ui.IOS
import java.time.LocalDate

/**
 * 담은 곳으로 — 「며칠 다녀오나요?」 만 묻고, 보관함의 담은 곳 전부를 날마다 나눠 넣은 편집 화면을 연다
 * (MZ2AZ-547 A4, 웹 `proto/ProtoPlanFlows.tsx` `ProtoSavedPlan`, iOS `SavedPlanView`).
 *
 * 시·도 묶음 → 가까운 순으로 잇고([SavedPlanRules.arrange]) 날마다 고르게 나눈다([SavedPlanRules.splitEven]). 고르기·빼기·순서는
 * 편집 화면에서. 담은 곳이 없으면 빈 코스로 시작한다. 저장 전 초안이다(isNew).
 */
@Composable
fun SavedPlanView(
    routes: RouteStore,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val cart = remember { CartStore(context) }
    var span by remember { mutableStateOf(RouteSpan.ONE_NIGHT) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var draft by remember { mutableStateOf<RouteEditorDraft?>(null) }
    LaunchedEffect(Unit) { cart.refresh() }

    val current = draft
    if (current != null) {
        DraftEditor(routes, current, onClose)
        return
    }
    val items = cart.items.filter { it.delisted != true }

    BackHandler(onBack = onClose)
    NewCourseScreen(
        onBack = onClose,
        footer = {
            NewCoursePrimaryButton(tr("코스 만들기")) {
                val days = SavedPlanRules.splitEven(SavedPlanRules.arrange(SavedPlanRules.places(items)), span.days)
                draft =
                    RouteEditorDraft(
                        RouteCourse(
                            title = tr("내 코스", "코스 제목"),
                            startDate = date,
                            days = days.map { places -> RouteDay(stops = places.map { RouteStop(place = it) }) },
                        ),
                    )
            }
        },
    ) {
        NewCourseTitle(tr("며칠 다녀오나요?"))
        TripScheduleGroup(span = span, onSpan = { span = it }, date = date, onDate = { date = it })
        NewCourseGroup(
            label = tr("넣을 곳"),
            footnote =
                if (items.isEmpty()) {
                    tr("담아 둔 곳이 없어 빈 코스로 시작해요. 다음 화면에서 하나씩 넣으면 돼요")
                } else {
                    tr("가까운 곳끼리 날마다 나눠 넣어요. 빼거나 순서를 바꾸는 건 다음 화면에서 할 수 있어요")
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val photos =
                    items
                        .mapNotNull { it.imageUrl?.toString() }
                        .filter { NewCourseText.usablePhoto(it) }
                        .take(4)
                if (photos.isEmpty()) {
                    BagIcon(IOS.secondaryLabel, Modifier.size(20.dp))
                } else {
                    // 사진 겹침 — 8 씩 겹친다.
                    Box {
                        photos.forEachIndexed { index, url ->
                            val shape = RoundedCornerShape(NewCourseStyle.radiusS)
                            NewCoursePhoto(
                                url,
                                Modifier
                                    .padding(start = (index * 24).dp)
                                    .size(32.dp)
                                    .clip(shape)
                                    .border(2.dp, IOS.systemBackground, shape),
                            )
                        }
                    }
                }
                Text(tr("담아 둔 곳"), fontSize = 15.sp, color = IOS.label)
                Spacer(Modifier.weight(1f))
                Text(tr("%d곳").format(items.size), fontSize = 15.sp, color = IOS.secondaryLabel)
            }
        }
    }
}
