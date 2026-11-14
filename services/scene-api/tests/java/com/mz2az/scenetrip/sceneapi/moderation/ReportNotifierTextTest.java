package com.mz2az.scenetrip.sceneapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.moderation.ReportNotifier.Report;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 신고 알림의 Slack 본문({@link ReportNotifier#slackText}) — 계획 {@code moderation-operator.md}
 * §3(MZ2AZ-569).
 *
 * <p>줄 순서는 ① 머리 ② 작성자 ③ 내용 ④ 설명 ⑤ 바로가기이고, 없는 값의 줄은 뺀다. 작성자가 탈퇴했으면 줄을 빼지 않고 「(탈퇴)」 라 적는다. 링크는 글·댓글은
 * 글 화면, 장소 리뷰는 장소 화면, 편의시설 리뷰는 없다. 보내기(웹훅)는 여기서 보지 않는다 — 순수 함수만.
 */
@DisplayName("ReportNotifier.slackText — 신고 알림 본문")
class ReportNotifierTextTest {

  private static final String APP = "https://app.scenetrip.io";

  @Test
  @DisplayName("글 신고, 모든 값이 있으면 다섯 줄 — 머리·작성자·내용·설명·글 링크 순서")
  void postWithEverything() {
    Report r =
        new Report(ReportStore.Target.POST, 26L, "spam", "광고 글이에요", "여행자12", "제목 — 본문", 26L, null);
    assertThat(ReportNotifier.slackText(r, APP))
        .isEqualTo(
            String.join(
                "\n",
                "🚨 신고 접수 — 글 #26 · 이유 스팸",
                "작성자: 여행자12",
                "내용: 제목 — 본문",
                "설명: 광고 글이에요",
                "바로가기: https://app.scenetrip.io/community/post/26"));
  }

  @Test
  @DisplayName("댓글 신고 — 머리는 댓글 번호, 링크는 그 댓글이 달린 글")
  void commentLinksToItsPost() {
    Report r = new Report(ReportStore.Target.COMMENT, 501L, "abuse", null, "길동", "나쁜 말", 77L, null);
    assertThat(ReportNotifier.slackText(r, APP))
        .isEqualTo(
            String.join(
                "\n",
                "🚨 신고 접수 — 댓글 #501 · 이유 욕설·혐오",
                "작성자: 길동",
                "내용: 나쁜 말",
                "바로가기: https://app.scenetrip.io/community/post/77"));
  }

  @Test
  @DisplayName("장소 리뷰 신고 — 링크는 /search/place/<placeId>")
  void placeReviewLinksToPlace() {
    Report r = new Report(ReportStore.Target.REVIEW, 9L, "sexual", null, "닉", "리뷰 본문", null, 1234L);
    assertThat(ReportNotifier.slackText(r, APP))
        .isEqualTo(
            String.join(
                "\n",
                "🚨 신고 접수 — 리뷰 #9 · 이유 성적인 내용",
                "작성자: 닉",
                "내용: 리뷰 본문",
                "바로가기: https://app.scenetrip.io/search/place/1234"));
  }

  @Test
  @DisplayName("편의시설 리뷰(장소 없음) — 바로가기 줄이 없다")
  void poiReviewHasNoLink() {
    Report r =
        new Report(ReportStore.Target.REVIEW, 10L, "other", "이상해요", "닉", "편의시설 리뷰", null, null);
    String text = ReportNotifier.slackText(r, APP);
    assertThat(text)
        .isEqualTo(
            String.join("\n", "🚨 신고 접수 — 리뷰 #10 · 이유 기타", "작성자: 닉", "내용: 편의시설 리뷰", "설명: 이상해요"));
    assertThat(text).doesNotContain("바로가기");
  }

  @Test
  @DisplayName("작성자가 탈퇴했으면(닉네임 없음) 「작성자: (탈퇴)」 — 줄을 빼지 않는다")
  void withdrawnAuthor() {
    Report r = new Report(ReportStore.Target.POST, 5L, "spam", null, null, "제목 — 본문", 5L, null);
    assertThat(ReportNotifier.slackText(r, APP).split("\n"))
        .containsExactly(
            "🚨 신고 접수 — 글 #5 · 이유 스팸",
            "작성자: (탈퇴)",
            "내용: 제목 — 본문",
            "바로가기: https://app.scenetrip.io/community/post/5");
  }

  @Test
  @DisplayName("설명이 없으면 「설명:」 줄을 뺀다")
  void noDetailNoLine() {
    Report r = new Report(ReportStore.Target.POST, 5L, "spam", null, "닉", "본문", 5L, null);
    assertThat(ReportNotifier.slackText(r, APP)).doesNotContain("설명:");
  }

  @Test
  @DisplayName("내용 사본이 없거나 빈 글자면 「내용:」 줄을 뺀다 — 본문 없는 리뷰는 사본이 빈 글자다")
  void noExcerptNoLine() {
    Report nullExcerpt =
        new Report(ReportStore.Target.REVIEW, 3L, "spam", null, "닉", null, null, 1L);
    Report emptyExcerpt =
        new Report(ReportStore.Target.REVIEW, 3L, "spam", null, "닉", "", null, 1L);
    for (Report r : new Report[] {nullExcerpt, emptyExcerpt}) {
      assertThat(ReportNotifier.slackText(r, APP).split("\n"))
          .containsExactly(
              "🚨 신고 접수 — 리뷰 #3 · 이유 스팸",
              "작성자: 닉",
              "바로가기: https://app.scenetrip.io/search/place/1");
    }
  }

  @Test
  @DisplayName("댓글인데 글 번호를 모르면 바로가기 줄을 뺀다")
  void commentWithoutPostHasNoLink() {
    Report r = new Report(ReportStore.Target.COMMENT, 8L, "abuse", null, "닉", "댓글", null, null);
    assertThat(ReportNotifier.slackText(r, APP)).doesNotContain("바로가기");
  }

  @Test
  @DisplayName("이유 여섯 가지는 모두 한국어 이름으로 적힌다")
  void reasonLabels() {
    Map<String, String> labels =
        Map.of(
            "spam", "스팸",
            "abuse", "욕설·혐오",
            "sexual", "성적인 내용",
            "personal_info", "개인정보 노출",
            "inappropriate_nickname", "부적절한 닉네임",
            "other", "기타");
    labels.forEach(
        (reason, label) -> {
          Report r = new Report(ReportStore.Target.POST, 1L, reason, null, "닉", "본문", 1L, null);
          assertThat(ReportNotifier.slackText(r, APP).split("\n")[0])
              .isEqualTo("🚨 신고 접수 — 글 #1 · 이유 " + label);
        });
  }

  @Test
  @DisplayName("앱 주소는 설정값을 그대로 앞에 붙인다 — 개발 서버 주소")
  void usesConfiguredBaseUrl() {
    Report r = new Report(ReportStore.Target.POST, 42L, "spam", null, "닉", "본문", 42L, null);
    assertThat(ReportNotifier.slackText(r, "https://dev.scenetrip.io"))
        .endsWith("\n바로가기: https://dev.scenetrip.io/community/post/42");
  }

  @Test
  @DisplayName("사용자가 쓴 내용·설명의 & < > 는 이스케이프한다 — <!channel> 이나 가짜 링크가 작동하지 않게")
  void escapesSlackMarkup() {
    Report r =
        new Report(
            ReportStore.Target.POST,
            7L,
            "spam",
            "<!channel> 보세요 & <https://evil.example|바로가기>",
            "닉",
            "a<b>c",
            7L,
            null);
    String text = ReportNotifier.slackText(r, APP);
    assertThat(text).contains("내용: a&lt;b&gt;c");
    assertThat(text).contains("설명: &lt;!channel&gt; 보세요 &amp; &lt;https://evil.example|바로가기&gt;");
    assertThat(text).doesNotContain("<!channel>");
  }
}
