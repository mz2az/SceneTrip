package com.mz2az.scenetrip.auth

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent

/**
 * 로그인 화면 (MZ2AZ-336). iOS `Auth/SignInView.swift` 를 옮긴 것이다 — 반쯤 올라오는 시트.
 *
 * 로그인은 **가입을 겸한다** — 처음 보는 구글 계정이면 그 자리에서 계정이 만들어지고,
 * 이 기기에서 비회원으로 담아 둔 장바구니·코스·찜이 그 계정으로 옮겨 간다.
 *
 * [AuthStore.showingSignIn] 이 켜지면 뜬다. 앱 맨 위(`SceneTripApp`)에 한 번만 둔다 — 시트가
 * 따로 창(Dialog)이라 어느 화면·덮개 위에서든 올라온다.
 */
@Composable
fun SignInSheetHost() {
    if (!AuthStore.showingSignIn) return
    val activity = LocalContext.current as? Activity
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), onDismiss = { AuthStore.showingSignIn = false }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.haetae_joy),
                contentDescription = null,
                modifier = Modifier.padding(top = 34.dp).height(96.dp),
            )
            Text(
                tr("SceneTrip 로그인"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = IOS.label,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                tr("담아 둔 장바구니·코스·찜이 계정에 저장돼요.\n다른 기기에서도 그대로 이어집니다."),
                fontSize = 15.sp,
                color = IOS.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.weight(1f))

            AuthStore.message?.let {
                Text(
                    tr(it),
                    fontSize = 13.sp,
                    color = IOS.systemRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(IOS.systemBackground)
                        .border(1.dp, IOS.systemGray3, CircleShape)
                        .clickable(enabled = !AuthStore.busy && activity != null) {
                            activity?.let(AuthStore::signInWithGoogle)
                        },
            ) {
                if (AuthStore.busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = IOS.secondaryLabel, modifier = Modifier.size(18.dp))
                } else {
                    Text("G", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, color = GOOGLE_BLUE)
                }
                Text(tr("Google 로 계속하기"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            }

            Text(
                tr("나중에 할게요"),
                fontSize = 15.sp,
                color = IOS.secondaryLabel,
                modifier =
                    Modifier
                        .padding(top = 6.dp, bottom = 10.dp)
                        .clip(CircleShape)
                        .clickable { AuthStore.showingSignIn = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val GOOGLE_BLUE = Color(0xFF4285F4)
