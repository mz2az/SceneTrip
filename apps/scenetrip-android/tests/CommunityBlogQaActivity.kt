package com.mz2az.scenetrip.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import com.mz2az.scenetrip.reviews.CardPhotoStrip
import com.mz2az.scenetrip.reviews.ReviewSubject
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoLicense
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
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
        // `long` — 세 줄로 접히는 제목과 한 줄을 넘는 공공누리 출처(2026-10-31 사용자 캡처: 제목이 작성자·출처 위로 겹쳤다).
        val postTitle = if (kind == "long") "Jung-gu, Daegu: A Drama Walk Through Gyesan Cathedral and Cheongna Hill" else "QA 블로그 제목"
        val photoCredit =
            if (kind ==
                "long"
            ) {
                "AI-converted image · Original: Korea Tourism Organization · KOGL Type 1"
            } else {
                "QA 원본 출처 · 합성 사진"
            }
        val blocks =
            listOf(
                TripPostBlock(TripPostBlockKind.text, text = "첫 문단 · QA 원문"),
                TripPostBlock(TripPostBlockKind.photo, photoIndex = 0, caption = "QA 사진", credit = photoCredit, aiGenerated = true),
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

                    // 코스 편집기 장소 카드의 사진 줄(iOS `CardPhotoStrip`) — 공공누리 3유형(네모 모서리·전체 보임)·일반·방문자 사진을 한 줄에.
                    "strip" -> {
                        val photo = URI("http://10.0.2.2:8898/photo.png")
                        val portrait = URI("http://10.0.2.2:8898/portrait.png")
                        val photos =
                            listOf(
                                Photo(
                                    photo,
                                    PhotoSource.official,
                                    credit = "AI-converted image · Original: Korea Tourism Organization · KOGL Type 3",
                                    license = PhotoLicense.kogl_type3,
                                ),
                                Photo(
                                    portrait,
                                    PhotoSource.official,
                                    credit = "AI-converted image · Original: Korea Tourism Organization · KOGL Type 3",
                                    license = PhotoLicense.kogl_type3,
                                ),
                                Photo(photo, PhotoSource.review, reviewId = 7),
                                Photo(portrait, PhotoSource.official),
                                Photo(photo, PhotoSource.official),
                                Photo(portrait, PhotoSource.official),
                            )
                        Column(Modifier.fillMaxSize().background(Color.White).padding(top = 200.dp)) {
                            CardPhotoStrip(ReviewSubject(1, "QA 장소", isPoi = true), photos, photos.size)
                        }
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
                                title = postTitle,
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
