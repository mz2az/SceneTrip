package com.mz2az.scenetrip.hometab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AccountDataReset
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.collectVisitStamps
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * 홈이 서버에서 받아 오는 것들 (`docs/project/plans/mobile-home-tab.md` §2).
 *
 * iOS `HomeTab/HomeTabModel.swift`를 옮긴 것이다. **네 요청을 나란히 보낸다** —
 * 줄줄이 기다리면 코스 상세(스탬프용)가 느릴 때 작품 선반까지 비어 있다. 하나가
 * 실패해도 나머지는 그린다 — 홈은 서버가 꺼져 있어도 죽지 않는다.
 */
class HomeTabModel(
    context: Context,
) {
    /** "지금 뜨는 작품" — 서버 인기순 첫 20개 (MZ2AZ-422), 맨 앞에 고정 작품 ([PinnedWorks], MZ2AZ-547 H6). */
    var works by mutableStateOf<List<ContentSummary>>(emptyList())
        private set

    /** "오늘의 성지" — 하루 동안 같은 곳. */
    var today by mutableStateOf<PlaceSummary?>(null)
        private set

    /** 홈 사진 카드의 도시 칩 — `GET /places/cities` 한 번, 서버 순서 그대로(MZ2AZ-547 H3). 못 받으면 비어 있고 카드는 그대로 뜬다. */
    var cities by mutableStateOf<List<PlaceCity>>(emptyList())
        private set

    var stamps by mutableStateOf<List<VisitStamp>>(emptyList())
        private set

    /** 작품 목록조차 못 받았나 — 화면이 "불러오지 못했습니다"를 띄우는 기준. */
    var failed by mutableStateOf(false)
        private set

    private val contentsApi = ContentsApi(API_BASE)
    private val placesApi = PlacesApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

    init {
        // 로그아웃·탈퇴 — 앞 계정의 스탬프를 먼저 비운다(다시 읽기가 실패해도 남지 않게, MZ2AZ-480).
        AccountDataReset.watch {
            stamps = emptyList()
        }
    }

    /**
     * **넷(오늘의 성지·스탬프·선반·도시)을 나란히 받고, 온 것부터 그린다** (MZ2AZ-372). 하나가 느려도 다른 것을 기다리지 않는다 — 오늘의 성지·스탬프가
     * 작품 선반 뒤로 밀리지 않는다.
     */
    suspend fun loadRest() =
        coroutineScope {
            launch { loadToday() }
            launch { loadStamps() }
            launch { loadShelf() }
            launch { loadCities() }
        }

    private suspend fun loadCities() {
        val list = withContext(Dispatchers.IO) { runCatching { placesApi.listPlaceCities() }.getOrNull() } ?: return
        cities = list.items
    }

    private suspend fun loadShelf() {
        val fresh = withContext(Dispatchers.IO) { runCatching { contentsApi.listContents(limit = SHELF_COUNT) }.getOrNull() }
        if (fresh != null) {
            // 고정 작품(케이팝 데몬 헌터스)을 맨 앞에 세운다(MZ2AZ-547 H6, [PinnedWorks]). 그 작품 받기가 실패하면 조용히 서버 순서다.
            works =
                PinnedWorks.apply(HomeShelf.from(fresh.items.orEmpty())) { id ->
                    withContext(Dispatchers.IO) { runCatching { contentsApi.getContent(id) }.getOrNull() }
                }
            failed = false
        } else {
            failed = works.isEmpty()
        }
    }

    private suspend fun loadToday() {
        val placeItems =
            withContext(Dispatchers.IO) { runCatching { placesApi.listPlaces(limit = 60) }.getOrNull() }?.items.orEmpty()
        if (placeItems.isEmpty()) return
        // 사진 있는 곳에서 고른다 — 카드 윗단이 96dp 그림 자리인데 사진이 없으면 그라데이션만 남아 빈 띠로 보인다.
        val pool = placeItems.filter { hasPhoto(it) }.ifEmpty { placeItems }
        // 연중 몇 번째 날인가로 고른다 — 무작위면 화면을 다시 그릴 때마다 성지가 바뀌어 「오늘의」 라는 말이 거짓이 된다.
        today = pool[LocalDate.now().dayOfYear % pool.size]
    }

    private suspend fun loadStamps() {
        val generation = AccountDataReset.generation
        val fetched = runCatching { collectVisitStamps(deviceId) }.getOrDefault(emptyList())
        if (generation == AccountDataReset.generation) stamps = fetched
    }

    companion object {
        /** 선반에 올리는 수 — 서버 인기순 첫 20편 (MZ2AZ-411·422). */
        const val SHELF_COUNT = 20

        /**
         * 화면에 실제로 뜨는 사진이 있는가. 주소가 있다고 그림이 나오는 것은 아니다 —
         * 시드에 호스트 없는 상대 경로가 섞여 있어 이미지 로더가 조용히 빈 자리를
         * 남긴다. 그래서 **절대 http(s) 주소**만 사진으로 친다.
         *
         * 값만 보는 순수 함수라 인스턴스 없이도 테스트할 수 있다.
         */
        fun hasPhoto(place: PlaceSummary): Boolean {
            val uri = place.imageUrl ?: return false
            val scheme = uri.scheme ?: return false
            return (scheme == "http" || scheme == "https") && uri.host != null
        }
    }
}
