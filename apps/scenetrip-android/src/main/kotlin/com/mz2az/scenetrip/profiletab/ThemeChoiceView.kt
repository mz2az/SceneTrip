package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.AppThemeMode
import com.mz2az.scenetrip.data.AppThemeStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent

/** iOS ThemeChoiceView의 폼·선택 체크·완료 버튼을 따른다. */
@Composable
fun ThemeChoiceView(
    store: AppThemeStore,
    onClose: () -> Unit,
) {
    IOSSheet(listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemGray6)) {
            IOSSheetToolbar(title = tr("화면 모드"), trailing = tr("완료"), onTrailing = onClose)
            Column(Modifier.padding(20.dp).clip(RoundedCornerShape(12.dp)).background(IOS.systemBackground)) {
                AppThemeMode.entries.forEachIndexed { index, mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clickable { store.choose(mode) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(AppThemeStore.label(mode), color = IOS.label, style = IOS.body)
                        Spacer(Modifier.weight(1f))
                        if (store.mode == mode) CheckmarkIcon(IOS.accent, Modifier.size(18.dp))
                    }
                    if (index < AppThemeMode.entries.lastIndex) {
                        com.mz2az.scenetrip.ui
                            .IOSListDivider(start = 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.43f
        drawCircle(color, radius, style = Stroke(size.minDimension * 0.08f))
        drawArc(
            color,
            -90f,
            180f,
            useCenter = true,
            topLeft =
                androidx.compose.ui.geometry
                    .Offset(center.x - radius, center.y - radius),
            size =
                androidx.compose.ui.geometry
                    .Size(radius * 2, radius * 2),
        )
    }
}
