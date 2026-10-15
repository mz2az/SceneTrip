package com.mz2az.scenetrip.sceneapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 금칙어 거르기 — 계획 {@code community-moderation.md} §2, 목록 파일 {@code moderation/banned-words.txt}·{@code
 * allowed-words.txt} 의 주석이 정한 규칙(MZ2AZ-438).
 *
 * <p>규칙: 소문자로 바꾸고, 기호를 빼고, 한 글자씩 띄워 쓴 덩어리는 붙이고, 낱말 사이 띄어쓰기는 남긴다. 금칙어는 한 낱말(또는 붙인 덩어리) 안에서 찾고, 예외
 * 낱말을 먼저 지운 뒤 본다. 흔한 낱말과 겹치는 말은 목록에 없다.
 *
 * <p>구현이 아니라 목록 파일 주석과 계획에서 뽑은 입력이다 — 실제 서버 리소스(클래스패스의 목록)를 그대로 읽는다.
 */
@DisplayName("금칙어 거르기 — 정규화·예외 낱말·잘못 걸림")
class TextFilterTest {

  @Test
  @DisplayName("목록의 말은 그대로·대소문자 섞어도·낱말 안에 들어 있어도 걸린다")
  void bannedWordsAreRejected() {
    assertRejected(
        List.of(
            "씨발",
            "이 집 씨발 맛없음",
            "씨발놈아 꺼져",
            "병신같은 서비스",
            "개새끼",
            "ㅅㅂ 진짜",
            "지랄하네",
            "FUCK",
            "Fuck you",
            "this place is shit",
            "SHITTY food",
            "what a motherfucker",
            "Bitch"));
  }

  @Test
  @DisplayName("기호를 끼워 피한 것(「s.h.i.t」·「씨.발」·「씨-발」·「fuck!!!」)은 기호를 빼고 본다")
  void symbolsAreDropped() {
    assertRejected(List.of("s.h.i.t", "씨.발", "씨-발", "씨_발", "f*u*c*k", "fuck!!!", "#씨발#"));
  }

  @Test
  @DisplayName("한 글자씩 띄워 쓴 것(「씨 발」·「f u c k」·「ㅅ ㅂ」)은 붙여서 본다 — 줄바꿈으로 띄워도")
  void singleCharacterRunsAreJoined() {
    assertRejected(List.of("씨 발", "정말 씨 발 이다", "f u c k", "s h i t", "ㅅ ㅂ", "씨 발 놈", "씨\n발"));
  }

  @Test
  @DisplayName("예외 낱말(「시발점」·「시발역」·Scunthorpe·retardant·shitzu)은 걸리지 않는다")
  void allowedWordsPass() {
    assertAllowed(
        List.of(
            "시발점",
            "이번 여행의 시발점은 서울역이었다",
            "시발역에서 출발",
            "시발택시 전시",
            "Scunthorpe United",
            "fire retardant",
            "my shitzu loved it",
            "SHIHTZU friendly cafe"));
  }

  @Test
  @DisplayName("예외 낱말을 지운 뒤에도 남은 금칙어는 걸린다 — 예외 낱말에 붙여 쓰면 빠져나가지 못한다")
  void allowedWordDoesNotShieldNeighbour() {
    assertRejected(List.of("시발점씨발", "씨발시발점", "scunthorpefuck"));
  }

  @Test
  @DisplayName("낱말 사이 띄어쓰기는 남긴다 — 「도착 시 발권」 은 「시발」 이 아니다")
  void wordBoundariesKept() {
    assertAllowed(List.of("도착 시 발권", "출발 시 발생하는 요금", "오후 3시 발권 창구"));
  }

  @Test
  @DisplayName("숫자 바로 뒤의 「시발」(N시 출발)은 통과한다 — 전각 숫자도. 숫자와 떨어지거나 숫자가 아니면 걸린다")
  void departureTimeAfterDigitsPasses() {
    assertAllowed(
        List.of(
            "오전 7시발 KTX",
            "10시발 버스를 탔다",
            "서울역 6시발 첫차",
            "막차는 23시발",
            "7시발",
            "2026년 7월 3일 9시발 열차",
            "７시발 KTX"));
    assertRejected(List.of("7 시발", "칠시발", "7시발 씨발"));
  }

  @Test
  @DisplayName("「시발」 은 ! 줄 — 한 글자씩 띄운 덩어리(「도착 시 발 아파요」)는 통과, 제 낱말로 쓰면 걸린다. ! 없는 「씨발」 은 띄워도 걸린다")
  void exclamationLineOnlyInsideRealWords() {
    assertAllowed(List.of("도착 시 발 아파요", "3 시 발 열차", "그 시 발 앞", "시 발"));
    assertRejected(List.of("시발", "시발 진짜", "아 시발", "시발놈", "SiBal 시발!", "씨 발"));
  }

  @Test
  @DisplayName("NFKC 먼저 — 전각 영문(ｆｕｃｋ·ＳＨＩＴ)과 분해된 한글 자모(씨발)는 걸리고, 분해된 「시발점」 은 통과한다")
  void nfkcNormalizationCatchesEvasions() {
    assertRejected(
        List.of(
            "ｆｕｃｋ",
            "ＳＨＩＴ ｈｏｔｅｌ",
            "\u110A\u1175\u1107\u1161\u11AF",
            "정말 \u110A\u1175\u1107\u1161\u11AF 이다",
            "\u1107\u1167\u11BC\u1109\u1175\u11AB"));
    assertAllowed(List.of("\u1109\u1175\u1107\u1161\u11AF\u110C\u1165\u11B7"));
  }

  @Test
  @DisplayName("새 금칙어 「씨팔」·「씨빨」 — 그대로·낱말 안·한 글자씩 띄워도 걸린다")
  void newBannedWords() {
    assertRejected(List.of("씨팔", "씨빨 진짜", "씨팔놈", "씨 팔", "씨.빨"));
  }

  @Test
  @DisplayName("「아저씨·아가씨」 뒤에 붙은 빨·팔은 예외 — 아저씨빨리요·아저씨빨래·아가씨팔찌는 통과, 씨빨·씨팔·씨팔놈은 걸린다")
  void ajeossiAgassiExceptions() {
    assertAllowed(List.of("기사 아저씨빨리요", "아저씨빨래 널어 주셨어요", "아가씨팔찌 샀어요", "아저씨팔 다치셨대요", "아가씨빨간 원피스"));
    assertRejected(List.of("씨빨", "씨팔", "씨팔놈", "아저씨빨리요 씨팔", "아저씨씨팔"));
  }

  @Test
  @DisplayName("새 예외 낱말 — 병신년·shitake·snigger 는 통과, 「병신」 은 여전히 걸린다")
  void newAllowedWords() {
    assertAllowed(List.of("병신년 역사 이야기", "Shitake mushroom soup", "sniggering kids", "SNIGGER"));
    assertRejected(List.of("병신", "병신같다"));
  }

  @Test
  @DisplayName("흔한 낱말과 겹치는 말은 목록에 없다 — 보지 마·미친 맛·새끼 고양이·아가씨바다·Fukuoka")
  void commonWordsPass() {
    assertAllowed(
        List.of(
            "야경은 놓치면 안 돼요, 보지 마세요 후회합니다",
            "미친 맛이에요",
            "새끼 고양이가 있는 카페",
            "아가씨바다 풍경",
            "Fukuoka trip",
            "후쿠오카 여행"));
  }

  @Test
  @DisplayName("평범한 여행 문장은 걸리지 않는다 — 한국어·영어")
  void ordinaryTravelTextPasses() {
    assertAllowed(
        List.of(
            "경복궁 야경이 정말 좋았어요! 사진 찍기 최고 :)",
            "드라마 「도깨비」 촬영지, 주문진 방파제. 바람이 세요~",
            "주차는 근처 공영주차장(유료) 이용",
            "A must-visit spot. The cocktail bar next door is great.",
            "We took the bus from Seoul Station; classes of kids everywhere.",
            "Assessment: 5/5, the assistant at the desk was kind.",
            "Hitchcock fans will love the Essex-style cottage",
            "Mississippi mud pie at the cafe",
            "I am a fan",
            "a b c d 순서대로 보세요",
            ""));
  }

  private static void assertRejected(List<String> inputs) {
    SoftAssertions soft = new SoftAssertions();
    for (String input : inputs) {
      soft.assertThat(TextFilter.allows(input)).as("걸려야 한다: [%s]", input).isFalse();
    }
    soft.assertAll();
  }

  private static void assertAllowed(List<String> inputs) {
    SoftAssertions soft = new SoftAssertions();
    for (String input : inputs) {
      soft.assertThat(TextFilter.allows(input)).as("통과해야 한다: [%s]", input).isTrue();
    }
    soft.assertAll();
    assertThat(inputs).isNotEmpty();
  }
}
