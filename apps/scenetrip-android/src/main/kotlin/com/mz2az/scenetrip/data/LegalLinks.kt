package com.mz2az.scenetrip.data

/**
 * 개인정보 처리방침·이용약관 주소와 문구 (MZ2AZ-455). iOS `Models/LegalLinks.swift` 와
 * `ProfileTabView.legalSection`·`SignInView.legalNotice` 를 옮긴 것이다.
 *
 * **주소는 여기 한 곳에만 둔다** — 게시 주소가 바뀌면 이 두 줄만 고친다. 마이페이지와 로그인 시트가
 * 둘 다 이 값을 읽는다. 영어판 주소는 아직 없다(한국어 한 장). 링크는 숨기지 않는다.
 */
object LegalLinks {
    const val PRIVACY = "https://scenetrip.io/privacy"
    const val TERMS = "https://scenetrip.io/terms"

    /** 마이페이지 섹션 제목과 줄 이름 — iOS 와 같은 한국어 원문이 번역표의 열쇠다. */
    const val SECTION_TITLE_KO = "약관 및 정책"
    const val PRIVACY_TITLE_KO = "개인정보 처리방침"
    const val TERMS_TITLE_KO = "이용약관"

    /** 로그인 시트의 가입 동의 안내. 로그인이 가입을 겸하므로 로그인 단추 바로 아래에 둔다. */
    const val SIGN_IN_NOTICE_KO = "계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다."

    /** 번역표에 있어야 하는 한국어 원문 전부. */
    val copyKeys: List<String> = listOf(SECTION_TITLE_KO, PRIVACY_TITLE_KO, TERMS_TITLE_KO, SIGN_IN_NOTICE_KO)
}

/**
 * 탈퇴 확인 문구 (MZ2AZ-452, 서버 MZ2AZ-466). 서버는 탈퇴 시 작성한 리뷰·여행후기와 사진도 지운다.
 * iOS `ProfileTabView` 의 알림과 같은 한국어 원문이다.
 *
 * 작성자가 없는 옛 글(`author == null`)의 「탈퇴한 사용자」 표시는 그대로다 — 이 문구와 무관하다.
 */
object AccountDeletionCopy {
    const val CONFIRM_KO = "장바구니·코스·찜과 이 기기의 발자취, 작성한 리뷰·여행후기와 사진이 모두 지워지고 되돌릴 수 없어요."
}

/** 번역표에 이 한국어 원문의 영어가 있는지 — 문구 누락을 단위 시험에서 잡는다. 화면은 [tr] 을 쓴다. */
fun englishOf(korean: String): String? = TRANSLATIONS_EN[korean]
