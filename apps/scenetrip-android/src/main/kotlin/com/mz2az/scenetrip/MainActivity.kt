package com.mz2az.scenetrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.onboarding.OnboardingView
import com.mz2az.scenetrip.onboarding.SplashView
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.iosTypography

/**
 * 앱의 유일한 액티비티. iOS 의 `SceneTripApp.swift` 에 해당한다.
 *
 * **화면은 Compose 로 짓는다.** SwiftUI 와 같은 선언형이라 iOS 코드가 구조를 유지한
 * 채 옮겨진다 — `@State` 가 `remember`, `VStack` 이 `Column` 이 되는 식이다. 뷰 +
 * XML 로 가면 같은 화면에 RecyclerView·Adapter·ViewHolder·DiffUtil 이 붙어 코드가
 * 두세 배가 되고, 그만큼 두 앱의 구조가 갈려 "iOS 와 같은 규칙" 을 지키기 어려워진다.
 *
 * 클라이언트 ID 는 여기서 넣지 않는다. SDK 가 **매니페스트의
 * com.naver.maps.map.NCP_KEY_ID 를 스스로 읽는다** (AndroidManifest.xml).
 * iOS 는 코드에서 넣으므로(SceneTripApp.swift) 두 앱의 모양이 갈리지만, 코드 주입을
 * 시도했더니 SDK 안에서 죽었다 —
 *   java.lang.NullPointerException: String.replace(...) on a null object
 *   at NaverMapSdk$NcpKeyClient.a  ← setClient 안쪽
 * 각 플랫폼 SDK 가 정상으로 삼는 경로가 다르다고 보고 여기서는 문서 경로를 따른다.
 * 값이 소스에 박히지 않는 것은 양쪽 같다.
 *
 * **`Activity` 가 아니라 `ComponentActivity` 다.** `setContent` 가 액티비티에
 * 생명주기·저장상태·`ViewModelStore` 를 요구하는데 맨 `Activity` 에는 없다.
 *
 * 지도 생명주기 콜백(onStart·onResume·…)을 여기서 넘기지 않는 것도 그래서다 —
 * `NaverMapCanvas` 가 스스로 관찰한다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // **iOS 에는 타이틀바가 없다.** 안드로이드 기본 테마는 검은 ActionBar 를
        // 얹는데, 그것 하나로 두 앱이 다른 제품처럼 보인다. 테마는 매니페스트에서
        // NoActionBar 로 지정하고, 여기서는 상태바 뒤까지 그리게 한다 — iOS 가
        // 지도를 상태바 아래까지 채우는 것과 같다.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 확인용 뒷문 — iOS `simctl launch … -initialTab profile`과 짝이다.
        // `adb shell am start -n com.mz2az.scenetrip/.MainActivity -e initialTab route
        // --el openCourseId 26` 처럼 부른다. 인자가 없으면 기본값(홈)을 그대로 둔다.
        TabRouter.applyInitialTab(
            tab = intent.getStringExtra("initialTab"),
            openCourseId = intent.getLongExtra("openCourseId", -1L).takeIf { it > 0 },
        )

        setContent { SceneTripApp() }
    }
}

/**
 * 검색 탭 (MZ2AZ-194).
 *
 * 동작 규칙은 iOS 가 이미 확정했으므로 새로 정하지 않고 그대로 옮긴다
 * (볼트 `(3주차)경로탭 개발/01_검색 탭 확정 동작 (버그정리 후).md`).
 */
@Composable
fun SceneTripApp() {
    // **MaterialTheme 의 기본 색을 쓰지 않는다.** 기본값은 보라 계열이라 iOS 의
    // systemBlue 와 갈린다. 색은 전부 `ui/IOSTheme.kt` 에서 명시로 가져온다 —
    // 테마는 글꼴 기본값 정도로만 남긴다.
    MaterialTheme(typography = iosTypography()) {
        Surface(modifier = Modifier.fillMaxSize(), color = IOS.systemBackground) {
            AppRoot()
        }
    }
}

/** 앱을 열었을 때의 순서. iOS `Onboarding/AppRoot.swift`를 옮긴 것이다. */
private enum class AppStage { SPLASH, LESSONS, APP }

/**
 * 진짜 앱은 처음부터 아래에 깔려 있다.
 *
 * 스플래시를 **덮개로** 얹는다. `RootTabs`를 나중에 만들면 스플래시가 로딩에
 * 더해지지만, 밑에 깔아 두면 그동안 지도 인증과 인기 촬영지 호출이 끝난다 —
 * 덮개가 걷힐 때 이미 그려져 있는 화면이 나오는 것과, 그때부터 회색 지도가
 * 뜨는 것은 체감이 다르다.
 */
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val onboardingFlag = remember { OnboardingFlag(context) }
    var stage by remember { mutableStateOf(AppStage.SPLASH) }

    Box(modifier = Modifier.fillMaxSize()) {
        RootTabs()

        // 들어올 때는 애니메이션이 없어야 한다 — 그냥 페이드로 두면 앱을 연 첫
        // 0.32초 동안 스플래시가 서서히 나타나면서 밑에 깔린 흰 화면이 비친다
        // (iOS 실측). 스플래시·온보딩 모두 나갈 때만 페이드한다.
        AnimatedVisibility(
            visible = stage == AppStage.SPLASH,
            enter = EnterTransition.None,
            exit = fadeOut(tween(320)),
        ) {
            SplashView(
                onDone = {
                    stage = if (onboardingFlag.hasSeen) AppStage.APP else AppStage.LESSONS
                },
            )
        }

        AnimatedVisibility(
            visible = stage == AppStage.LESSONS,
            enter = EnterTransition.None,
            exit = fadeOut(tween(320)),
        ) {
            OnboardingView(
                onboardingFlag = onboardingFlag,
                onDone = { stage = AppStage.APP },
            )
        }
    }
}
