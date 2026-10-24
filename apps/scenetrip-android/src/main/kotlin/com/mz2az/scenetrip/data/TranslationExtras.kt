package com.mz2az.scenetrip.data

/** 시스템 문구 등 Android에만 있는 표현. 공통 문구는 iOS 번역표에서 생성한다. */
internal val TRANSLATIONS_EN_EXTRA =
    mapOf(
        "인기순" to "Most popular",
        "연결된 촬영지가 없습니다" to "No filming locations are linked yet",
        "시스템 설정" to "System",
        "초기화" to "Reset",
        "제작사 공식 페이지" to "Official producer page",
        "외부 브라우저를 열지 못했어요" to "Could not open the browser",
        "방문 기록을 저장하는 중" to "Saving visit",
        "방문 기록을 저장하지 못했어요 · 다시 저장" to "Could not save visit · Try again",
        "‘%s’에 대한 결과 없음" to "No results for “%s”",
        "철자를 확인하거나 새로운 검색을 시도하십시오." to "Check the spelling or try a new search.",
        "이 기기에 로그인된 구글 계정이 없어요. 기기 설정에서 계정을 추가해 주세요" to "No Google account is signed in on this device. Add one in Settings.",
        "뒤로" to "Back",
        "빼기" to "Remove",
        "앱 안의 길찾기는 잠시 쉬고 있어요. 지도 앱으로 이어서 안내받을 수 있어요" to "In-app directions are taking a break. Continue in a map app.",
        "약 %d분 뒤 다시 시도할 수 있어요" to "Try again in about %d minutes.",
        "카카오맵 길찾기" to "Directions in KakaoMap",
        "네이버 지도 길찾기" to "Directions in Naver Map",
        "앱에서 다시 시도" to "Try again in the app",
        "지도를 열 수 없어요. 잠시 뒤 다시 시도해 주세요" to "Couldn't open the map. Please try again later.",
        // 이용 통계 꼬리말 (MZ2AZ-475) — iOS 문구에서 ATT(광고 추적 허용) 문장만 뺐다.
        "꺼도 앱 기능은 그대로예요." to "Everything still works when this is off.",
        // iOS 번역표에서는 빠졌지만 Android 주변 지도(PoiClusterPins)가 아직 쓰는 문구다.
        "편의시설 %d곳" to "%d nearby places",
        "현재 지도에서 받은 결과 중 %d곳" to "%d of the places loaded on this map",
        "지역 전체 개수가 아니에요. 장소를 골라 정보를 확인하세요." to "This is not the area's total. Choose a place to see its details.",
    )
