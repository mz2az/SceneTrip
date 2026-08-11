package com.mz2az.scenetrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mz2az.scenetrip.searchtab.SearchTabScreen

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
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SearchTabScreen()
        }
    }
}
