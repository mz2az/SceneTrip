package com.mz2az.scenetrip.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.mz2az.scenetrip.communitytab.CommunityArticlePreview
import com.mz2az.scenetrip.communitytab.CommunityComposeState
import com.mz2az.scenetrip.communitytab.CommunityComposeView
import com.mz2az.scenetrip.communitytab.CommunityPhotoSource
import com.mz2az.scenetrip.communitytab.CommunityPostView
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import com.mz2az.scenetrip.ui.SceneTripTheme
import java.net.URI

/** 별도 QA 앱의 합성 초안/사진만 사용. 제품 계정·실제 게시·업로드를 시작하지 않는다. */
class CommunityBlogQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("qaCase") ?: "compose"
        val language = if (intent.getStringExtra("language") == "en") "en" else "ko"
        getSharedPreferences("scenetrip", MODE_PRIVATE).edit().putString("scenetrip.language", language).apply()
        AppLanguage.getInstance(this)
        val url =
            when (kind) {
                "retry" -> "http://10.0.2.2:8898/flaky.png?case=${System.nanoTime()}"
                "portrait" -> "http://10.0.2.2:8898/portrait.png"
                else -> "http://10.0.2.2:8898/photo.png"
            }
        val story = "첫 문단 · QA 원문\n\n둘째 문단 · 사진 뒤의 원문"
        val blocks =
            listOf(
                TripPostBlock(TripPostBlockKind.text, text = "첫 문단 · QA 원문"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "QA 사진", credit = "QA 원본 출처 · 합성 사진", aiGenerated = true),
                TripPostBlock(TripPostBlockKind.text, text = "둘째 문단 · 사진 뒤의 원문"),
            )
        setContent {
            val scope = rememberCoroutineScope()
            val store = remember { CommunityStore.getInstance(this) }
            val draft =
                remember {
                    CommunityComposeState(this, scope, store).apply {
                        title = "QA 블로그 제목"
                        this.story = story
                        photos.load(
                            listOf(
                                ReviewPhoto("uploads/tmp/qa-first.jpg", URI(url)),
                                ReviewPhoto("uploads/tmp/qa-second.jpg", URI("http://10.0.2.2:8898/photo.png")),
                            ),
                        )
                        placements = mapOf(photos.photos.first().id to CommunityPhotoPlacement(1, "QA 사진", "QA 원본 출처 · 합성 사진", true))
                    }
                }
            SceneTripTheme {
                when (kind) {
                    "compose" -> {
                        CommunityComposeView(onDismiss = ::finish, suppliedDraft = draft)
                    }

                    // 대표 한 장만 받아 캐시나 두 번째 본문 요청 없이 404→재시도200을 검사한다.
                    "retry" -> {
                        CommunityArticlePreview(
                            "QA 블로그 제목",
                            blocks.filter {
                                it.kind == TripPostBlockKind.text
                            },
                            listOf(CommunityPhotoSource.Remote(url)),
                            null,
                            ::finish,
                        )
                    }

                    "pending", "failed" -> {
                        CommunityArticlePreview(
                            "QA 블로그 제목",
                            blocks,
                            listOf(
                                if (kind ==
                                    "pending"
                                ) {
                                    CommunityPhotoSource.Pending
                                } else {
                                    CommunityPhotoSource.Failed
                                },
                            ),
                            null,
                            ::finish,
                        )
                    }

                    else -> {
                        CommunityPostView(
                            CommunityPost(
                                title = "QA 블로그 제목",
                                body = story,
                                author = "QA 작성자",
                                remotePhotos = listOf(url),
                                bodyBlocks =
                                    if (kind ==
                                        "legacy"
                                    ) {
                                        null
                                    } else {
                                        blocks
                                    },
                            ),
                            ::finish,
                        )
                    }
                }
            }
        }
    }
}
