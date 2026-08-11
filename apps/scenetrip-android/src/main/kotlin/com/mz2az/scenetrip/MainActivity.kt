package com.mz2az.scenetrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mz2az.scenetrip.ui.IOS

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
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = IOS.systemBackground) {
            RootTabs()
        }
    }
}
