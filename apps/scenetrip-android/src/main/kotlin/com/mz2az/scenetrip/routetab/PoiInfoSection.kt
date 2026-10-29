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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PoiInfo
import com.mz2az.scenetrip.searchtab.ExternalLinkIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.openExternalUrl

/**
 * 관광공사 안내 (MZ2AZ-470). 긴 개요·큰 글자도 카드 밖으로 밀어내지 않고 220dp 안에서 스크롤해 읽는다.
 * 값 없는 안내에는 공간을 잡지 않는다. iOS `PoiInfoSection`.
 */
@Composable
fun PoiInfoSection(info: PoiInfo?) {
    val rows = PoiInfoRules.rows(info)
    val homepage = PoiInfoRules.homepage(info)
    if (rows.isEmpty() && homepage == null) return
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label, style = IOS.caption, fontWeight = FontWeight.SemiBold, color = IOS.secondaryLabel)
                    row.languageTag?.let {
                        Text(
                            it,
                            style = IOS.caption2,
                            color = IOS.secondaryLabel,
                            modifier =
                                Modifier
                                    .background(
                                        IOS.systemGray5,
                                        RoundedCornerShape(50),
                                    ).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                // 서버가 준 자유 문장이다. 줄바꿈·문단을 보존하며 시각 계산이나 링크 감지를 하지 않는다.
                Text(row.text, style = IOS.caption, color = IOS.label)
            }
        }
        homepage?.let { url ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { openExternalUrl(context, url) },
            ) {
                ExternalLinkIcon(IOS.accent, Modifier.size(13.dp))
                Text(tr("홈페이지"), style = IOS.caption, fontWeight = FontWeight.SemiBold, color = IOS.accent)
            }
        }
    }
}
