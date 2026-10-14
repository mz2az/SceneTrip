package com.mz2az.scenetrip.qa

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.searchtab.ScenePhotoRules
import com.mz2az.scenetrip.searchtab.ScenePhotoViewer
import java.net.URI

/** 별도 설치 식별자의 화면 시험 앱. 제품 계정·글·코스를 읽거나 바꾸지 않는다. */
class ScenePhotoQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("photoCase") ?: "ok"
        val sceneImageUrl =
            when (kind) {
                "missing" -> null
                "failure" -> URI("http://10.0.2.2:8898/broken.png")
                else -> URI("http://10.0.2.2:8898/photo.png")
            }
        val scene =
            Scene(
                contentId = 1,
                contentTitle = "Scene photo QA",
                posterUrl = URI("http://10.0.2.2:8898/photo.png"),
                sceneImageUrl = sceneImageUrl,
                sceneImageUrls = listOfNotNull(sceneImageUrl),
            )
        setContent {
            if (ScenePhotoRules.photo(scene) == null) {
                Text("No scene image · poster is not a fallback")
            } else {
                ScenePhotoViewer(scene, "Three panels · full frame", onClose = ::finish)
            }
        }
    }
}
