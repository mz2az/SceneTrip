package com.mz2az.scenetrip.sceneapi.moderation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고가 들어오면 무엇이 신고됐는지 Slack 으로 알린다(MZ2AZ-569, 계획 {@code moderation-operator.md} §3).
 *
 * <p>SigNoz 알람은 「몇 건」 만 알려 무엇이 신고됐는지 몰랐다. 여기서는 종류·번호·이유·작성자·본문 앞부분·설명·바로가기를 담는다. 웹훅 주소({@code
 * scenetrip.moderation.report-slack-webhook-url})는 비밀값이고, 비어 있으면 보내지 않는다. 신고 응답을 기다리게 하지 않게 별도
 * 스레드에서 보내고, 실패해도 로그만 남긴다.
 */
@Component
public class ReportNotifier {

  /** 알림 한 건의 재료. {@code postId} 는 글이면 그 글, 댓글이면 달린 글. {@code placeId} 는 장소 리뷰만. */
  public record Report(
      ReportStore.Target target,
      long targetId,
      String reason,
      String detail,
      String authorNickname,
      String excerpt,
      Long postId,
      Long placeId) {}

  private static final Logger log = LoggerFactory.getLogger(ReportNotifier.class);

  private static final Map<String, String> REASONS =
      Map.of(
          "spam", "스팸",
          "abuse", "욕설·혐오",
          "sexual", "성적인 내용",
          "personal_info", "개인정보 노출",
          "inappropriate_nickname", "부적절한 닉네임",
          "other", "기타");

  private final JdbcClient jdbc;
  private final URI webhook;
  private final String appBaseUrl;
  private final HttpClient http;
  private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
  private final JsonMapper json = JsonMapper.builder().build();

  public ReportNotifier(
      JdbcClient jdbc,
      @Value("${scenetrip.moderation.report-slack-webhook-url:}") String webhookUrl,
      @Value("${scenetrip.moderation.app-base-url:https://app.scenetrip.io}") String appBaseUrl) {
    this.jdbc = jdbc;
    this.webhook = parse(webhookUrl);
    this.appBaseUrl = appBaseUrl;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** 웹훅 주소를 한 번만 읽는다. 비었거나 깨졌으면 알림을 끈다 — 주소는 비밀값이라 로그에 남기지 않는다. */
  private static URI parse(String raw) {
    String url = raw == null ? "" : raw.strip();
    if (url.isEmpty()) {
      log.info("신고 Slack 웹훅이 없다 — 신고 알림은 SigNoz 건수 알람만 간다");
      return null;
    }
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme())) {
        throw new IllegalArgumentException("https 가 아니다");
      }
      return uri;
    } catch (IllegalArgumentException e) {
      log.warn("신고 Slack 웹훅 주소 형식이 틀렸다 — 알림을 끈다(주소는 비밀값이라 적지 않는다)");
      return null;
    }
  }

  /** 새 신고가 적혔다. 웹훅이 없으면 아무 일도 하지 않는다. 던지지 않는다. */
  public void reportRecorded(
      ReportStore.Target target, long targetId, String reason, String detail) {
    if (webhook == null) {
      return;
    }
    sender.execute(
        () -> {
          try {
            Report report = load(target, targetId, reason, detail);
            String body = json.writeValueAsString(Map.of("text", slackText(report, appBaseUrl)));
            HttpResponse<Void> res =
                http.send(
                    HttpRequest.newBuilder(webhook)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
              log.warn(
                  "[신고] 알림 실패 status={} target={} id={}", res.statusCode(), target.value, targetId);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            log.warn("[신고] 알림 실패 target={} id={}: {}", target.value, targetId, e.toString());
          }
        });
  }

  /** 신고 기록의 사본(작성자·본문 앞부분)과 바로가기에 쓸 글·장소 번호를 모은다. */
  private Report load(ReportStore.Target target, long targetId, String reason, String detail) {
    record Snap(String nickname, String excerpt) {}
    Snap snap =
        jdbc.sql(
                """
                SELECT u.nickname, r.excerpt
                FROM content_report r LEFT JOIN app_user u ON u.id = r.author_id
                WHERE r.target_type = :type AND r.target_id = :id
                ORDER BY r.created_at DESC LIMIT 1
                """)
            .param("type", target.value)
            .param("id", targetId)
            .query((rs, i) -> new Snap(rs.getString("nickname"), rs.getString("excerpt")))
            .optional()
            .orElse(new Snap(null, null));
    Long postId =
        switch (target) {
          case POST -> targetId;
          case COMMENT -> lookup("SELECT post_id FROM community_comment WHERE id = :id", targetId);
          case REVIEW -> null;
        };
    Long placeId =
        target == ReportStore.Target.REVIEW
            ? lookup("SELECT place_id FROM review WHERE id = :id", targetId)
            : null;
    return new Report(
        target, targetId, reason, detail, snap.nickname(), snap.excerpt(), postId, placeId);
  }

  private Long lookup(String sql, long id) {
    Optional<Long> found =
        jdbc.sql(sql)
            .param("id", id)
            .query((rs, i) -> (Long) rs.getObject(1, Long.class))
            .optional();
    return found.orElse(null);
  }

  /**
   * 사용자가 쓴 글을 Slack 문법으로 해석하지 않게 한다 — 그대로 두면 신고 설명의 {@code <!channel>} 이 채널 전원을 부르고 {@code
   * <https://…|바로가기>} 가 가짜 링크가 된다(검증 지적). Slack 권고대로 {@code & < >} 만 바꾼다.
   */
  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Slack 본문. 없는 값의 줄은 뺀다 — 작성자는 탈퇴했으면 「(탈퇴)」 로 남긴다. */
  public static String slackText(Report r, String appBaseUrl) {
    String kind =
        switch (r.target()) {
          case POST -> "글";
          case COMMENT -> "댓글";
          case REVIEW -> "리뷰";
        };
    List<String> lines = new ArrayList<>();
    lines.add(
        "🚨 신고 접수 — %s #%d · 이유 %s"
            .formatted(kind, r.targetId(), REASONS.getOrDefault(r.reason(), r.reason())));
    lines.add("작성자: " + (r.authorNickname() == null ? "(탈퇴)" : escape(r.authorNickname())));
    if (r.excerpt() != null && !r.excerpt().isBlank()) {
      lines.add("내용: " + escape(r.excerpt()));
    }
    if (r.detail() != null && !r.detail().isBlank()) {
      lines.add("설명: " + escape(r.detail()));
    }
    String link =
        switch (r.target()) {
          case POST, COMMENT ->
              r.postId() == null ? null : appBaseUrl + "/community/post/" + r.postId();
          case REVIEW -> r.placeId() == null ? null : appBaseUrl + "/search/place/" + r.placeId();
        };
    if (link != null) {
      lines.add("바로가기: " + link);
    }
    return String.join("\n", lines);
  }
}
