package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 사진 저장소 — 서명은 망 없이 만들어진다(가짜 고정 자격 증명), 옮기기는 목 {@link S3Client} 로 본다. 실제 AWS 를 부르지 않는다.
 *
 * <p>계약: 올릴 주소는 10 분이고 형식·크기가 서명에 들어간다. 보여 줄 주소는 적어도 한 시간 산다 — 창(기본 한 시간)마다 같은 주소라 브라우저가
 * 캐시한다(MZ2AZ-562).
 */
@DisplayName("S3PhotoStorage — 서명된 올릴·볼 주소와 옮기기")
class S3PhotoStorageTest {

  private static final String BUCKET = "scenetrip-user-media-000000000000-ap-northeast-2-dev";

  private final S3Presigner presigner =
      S3Presigner.builder()
          .region(Region.AP_NORTHEAST_2)
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create("AKIAFAKEFAKEFAKEFAKE", "fake/secret/for/tests")))
          .build();
  private final S3Client s3 = mock(S3Client.class, Mockito.CALLS_REAL_METHODS);
  private final S3PhotoStorage storage = new S3PhotoStorage(s3, presigner, BUCKET);

  @AfterEach
  void close() {
    presigner.close();
  }

  @Test
  @DisplayName("있다 — available() 은 true")
  void isAvailable() {
    assertThat(storage.available()).isTrue();
  }

  @Test
  @DisplayName("올릴 주소 — 그 버킷·그 키로 PUT, 서명에 content-type·content-length, 만료 10 분, host 는 필수 헤더에서 뺀다")
  void presignedPut() {
    String key = "uploads/tmp/5f0c7e7e-1111-4222-8333-444455556666.jpg";
    Instant before = Instant.now();

    PhotoStorage.PresignedUpload p =
        storage.presignUpload(key, "image/jpeg", 123_456L, Duration.ofMinutes(10));

    URI url = p.url();
    assertThat(url.getScheme()).isEqualTo("https");
    assertThat(url.getHost() + url.getPath()).contains(BUCKET).endsWith("/" + key);
    Map<String, String> q = query(url);
    assertThat(q).containsEntry("X-Amz-Expires", "600");
    assertThat(q.get("X-Amz-SignedHeaders").split(";"))
        .contains("host", "content-type", "content-length");
    assertThat(q.get("X-Amz-Credential")).startsWith("AKIAFAKEFAKEFAKEFAKE/");
    assertThat(q).containsKey("X-Amz-Signature");

    assertThat(p.requiredHeaders())
        .containsEntry("Content-Type", "image/jpeg")
        .containsEntry("Content-Length", "123456");
    assertThat(p.requiredHeaders().keySet()).noneMatch(h -> h.equalsIgnoreCase("host"));

    assertThat(p.expiresAt())
        .isBetween(
            before.plus(Duration.ofMinutes(10)).minusSeconds(5),
            Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
  }

  @Test
  @DisplayName("올릴 주소 — 형식이나 크기가 다르면 서명이 달라진다(저장소가 다른 파일을 거절하는 근거)")
  void signatureCoversTypeAndLength() {
    String key = "uploads/tmp/a.png";
    String jpeg =
        query(storage.presignUpload(key, "image/jpeg", 100, Duration.ofMinutes(10)).url())
            .get("X-Amz-Signature");
    String png =
        query(storage.presignUpload(key, "image/png", 100, Duration.ofMinutes(10)).url())
            .get("X-Amz-Signature");
    String bigger =
        query(storage.presignUpload(key, "image/jpeg", 101, Duration.ofMinutes(10)).url())
            .get("X-Amz-Signature");

    assertThat(jpeg).isNotEqualTo(png).isNotEqualTo(bigger);
  }

  @Test
  @DisplayName("보여 줄 주소 — 그 버킷·그 키로 GET. 기본 창 한 시간: 만료 2 시간(창 + 한 시간), 브라우저 캐시 한 시간")
  void presignedGet() {
    URI url = storage.viewUrl("reviews/abc.webp");

    assertThat(url.getHost() + url.getPath()).contains(BUCKET).endsWith("/reviews/abc.webp");
    Map<String, String> q = query(url);
    // MZ2AZ-562 전에는 3600 이었다. 서명 시각을 창의 시작으로 내리므로, 창 안 어느 때 받아도 한 시간은 살도록 창 길이를 더한다.
    assertThat(q).containsEntry("X-Amz-Expires", "7200");
    assertThat(q).containsEntry("response-cache-control", "private, max-age=3600");
    assertThat(q).containsKey("X-Amz-Signature");
    // 계약의 「한 시간」 은 이제 「적어도 한 시간」 이다.
    assertThat(S3PhotoStorage.VIEW_TTL).isEqualTo(Duration.ofHours(1));
  }

  // ── 보여 줄 주소를 창마다 하나로 (MZ2AZ-562) ─────────────────────────────────
  //
  // 구현이 따를 모양(시험이 이것으로 만든다):
  //
  //   S3PhotoStorage(S3Client s3, Supplier<S3Presigner> presigners, String bucket,
  //                  Clock clock, Duration viewWindow)
  //
  // - 서명 시각 = clock.instant() 를 UTC epoch 기준 viewWindow 의 배수로 내린 값(창의 시작). 시계의 시간대와 무관하다.
  // - X-Amz-Date = 창의 시작, X-Amz-Expires = viewWindow + VIEW_TTL(한 시간).
  // - 주소에 response-cache-control=「private, max-age=<viewWindow 초>」 를 싣는다(S3 응답 헤더 덮어쓰기, 서명에 들어간다).
  // - 기존 세 인자 생성자 둘은 그대로 두고 Clock.systemUTC() · 한 시간 창을 쓴다.
  // - 올릴 주소(presignUpload)는 바뀌지 않는다 — 지금 시각으로 서명하고 캐시 헤더도 없다.

  private static final Duration HOUR = Duration.ofHours(1);

  /** 시험이 앞으로 돌리는 시계. */
  private static final class MovableClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    MovableClock(Instant now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    void set(Instant at) {
      now = at;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
      return new MovableClock(now, z);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private S3PhotoStorage windowed(Clock clock, Duration window) {
    return new S3PhotoStorage(s3, () -> presigner, BUCKET, clock, window);
  }

  @Test
  @DisplayName("같은 창(한 시간) 안에서 같은 키를 두 번 받으면 글자 하나 다르지 않은 같은 주소다 — 브라우저가 다시 받지 않는다")
  void sameWindowSameUrl() {
    MovableClock clock = new MovableClock(Instant.parse("2026-11-13T05:10:00Z"), ZoneOffset.UTC);
    S3PhotoStorage s = windowed(clock, HOUR);

    URI first = s.viewUrl("reviews/abc.webp");
    clock.set(Instant.parse("2026-11-13T05:59:59Z"));
    URI second = s.viewUrl("reviews/abc.webp");
    clock.set(Instant.parse("2026-11-13T05:00:00Z"));
    URI atStart = s.viewUrl("reviews/abc.webp");

    assertThat(second).isEqualTo(first);
    assertThat(atStart).isEqualTo(first);
  }

  @Test
  @DisplayName("다음 창이면 다른 주소, 서명 시각은 그 창의 시작")
  void nextWindowNewUrl() {
    MovableClock clock = new MovableClock(Instant.parse("2026-11-13T05:59:59Z"), ZoneOffset.UTC);
    S3PhotoStorage s = windowed(clock, HOUR);

    URI before = s.viewUrl("reviews/abc.webp");
    clock.set(Instant.parse("2026-11-13T06:00:00Z"));
    URI after = s.viewUrl("reviews/abc.webp");

    assertThat(after).isNotEqualTo(before);
    assertThat(query(before)).containsEntry("X-Amz-Date", "20261113T050000Z");
    assertThat(query(after)).containsEntry("X-Amz-Date", "20261113T060000Z");
    assertThat(query(after).get("X-Amz-Signature"))
        .isNotEqualTo(query(before).get("X-Amz-Signature"));
  }

  @Test
  @DisplayName("서명 시각은 창의 시작, 만료는 창 + 한 시간(7200 초) — 창 끝에 받아도 한 시간 넘게 산다")
  void signedAtWindowStartValidAnHourPastWindowEnd() {
    S3PhotoStorage s =
        windowed(Clock.fixed(Instant.parse("2026-11-13T05:42:17Z"), ZoneOffset.UTC), HOUR);

    Map<String, String> q = query(s.viewUrl("reviews/abc.webp"));

    assertThat(q).containsEntry("X-Amz-Date", "20261113T050000Z");
    assertThat(q).containsEntry("X-Amz-Expires", "7200");
    assertThat(q.get("X-Amz-Credential"))
        .startsWith("AKIAFAKEFAKEFAKEFAKE/20261113/ap-northeast-2/s3/aws4_request");
    // 창의 마지막 순간(05:59:59)에 받아도 07:00 까지 — 한 시간 넘게 남는다.
    Instant expires = Instant.parse("2026-11-13T05:00:00Z").plusSeconds(7200);
    assertThat(Duration.between(Instant.parse("2026-11-13T05:59:59Z"), expires))
        .isGreaterThanOrEqualTo(HOUR);
  }

  @Test
  @DisplayName("주소에 response-cache-control=「private, max-age=3600」(창 길이) 이 실린다")
  void carriesCacheControl() {
    S3PhotoStorage s =
        windowed(Clock.fixed(Instant.parse("2026-11-13T05:10:00Z"), ZoneOffset.UTC), HOUR);

    URI url = s.viewUrl("reviews/abc.webp");
    Map<String, String> q = query(url);

    assertThat(q).containsEntry("response-cache-control", "private, max-age=3600");
    assertThat(q).containsKey("X-Amz-Signature");
  }

  @Test
  @DisplayName("창 길이를 바꾸면(15 분) 창의 시작·만료(창 + 한 시간 = 4500 초)·max-age(900) 가 따라간다")
  void customWindow() {
    Duration quarter = Duration.ofMinutes(15);
    MovableClock clock = new MovableClock(Instant.parse("2026-11-13T05:20:00Z"), ZoneOffset.UTC);
    S3PhotoStorage s = windowed(clock, quarter);

    URI a = s.viewUrl("reviews/abc.webp");
    clock.set(Instant.parse("2026-11-13T05:29:59Z"));
    URI b = s.viewUrl("reviews/abc.webp");
    clock.set(Instant.parse("2026-11-13T05:30:00Z"));
    URI c = s.viewUrl("reviews/abc.webp");

    Map<String, String> q = query(a);
    assertThat(q).containsEntry("X-Amz-Date", "20261113T051500Z");
    assertThat(q).containsEntry("X-Amz-Expires", "4500");
    assertThat(q).containsEntry("response-cache-control", "private, max-age=900");
    assertThat(b).isEqualTo(a);
    assertThat(c).isNotEqualTo(a);
    assertThat(query(c)).containsEntry("X-Amz-Date", "20261113T053000Z");
  }

  @Test
  @DisplayName("창은 UTC epoch 의 배수에 맞춘다 — 시계의 시간대(서울)가 무엇이든 같은 순간이면 같은 주소")
  void windowAlignedToUtcEpoch() {
    Instant at = Instant.parse("2026-11-13T05:42:17Z");
    URI utc = windowed(Clock.fixed(at, ZoneOffset.UTC), HOUR).viewUrl("reviews/abc.webp");
    URI seoul =
        windowed(Clock.fixed(at, ZoneId.of("Asia/Seoul")), HOUR).viewUrl("reviews/abc.webp");

    assertThat(seoul).isEqualTo(utc);
    assertThat(query(seoul)).containsEntry("X-Amz-Date", "20261113T050000Z");

    // 90 분 창: epoch 부터 90 분씩 — 2026-11-13T04:30Z 가 경계다(epoch 초 1794544200 = 90×60×332323).
    URI ninety =
        windowed(Clock.fixed(at, ZoneOffset.UTC), Duration.ofMinutes(90))
            .viewUrl("reviews/abc.webp");
    assertThat(query(ninety)).containsEntry("X-Amz-Date", "20261113T043000Z");
    assertThat(query(ninety)).containsEntry("X-Amz-Expires", String.valueOf(90 * 60 + 3600));
  }

  @Test
  @DisplayName("키가 다르면 같은 창이라도 주소가 다르다")
  void differentKeyDifferentUrl() {
    S3PhotoStorage s =
        windowed(Clock.fixed(Instant.parse("2026-11-13T05:10:00Z"), ZoneOffset.UTC), HOUR);

    assertThat(s.viewUrl("reviews/a.webp")).isNotEqualTo(s.viewUrl("reviews/b.webp"));
  }

  @Test
  @DisplayName("올릴 주소는 그대로 — 창·캐시 헤더와 무관하게 지금 시각으로 10 분, response-cache-control 없음")
  void uploadUnaffectedByWindow() {
    // 시계를 먼 과거에 둔다 — 올릴 주소가 이 시계를 따르면 만료 시각이 지금과 어긋난다.
    S3PhotoStorage s =
        windowed(Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC), HOUR);
    Instant before = Instant.now();

    PhotoStorage.PresignedUpload p =
        s.presignUpload("uploads/tmp/a.jpg", "image/jpeg", 10, Duration.ofMinutes(10));

    Map<String, String> q = query(p.url());
    assertThat(q).containsEntry("X-Amz-Expires", "600");
    assertThat(q).doesNotContainKey("response-cache-control");
    assertThat(p.expiresAt())
        .isBetween(
            before.plus(Duration.ofMinutes(10)).minusSeconds(5),
            Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
  }

  @Test
  @DisplayName("옮기기 — 같은 버킷 안에서 복사한 뒤 원본을 지운다")
  void moveCopiesThenDeletes() {
    doReturn(CopyObjectResponse.builder().build())
        .when(s3)
        .copyObject(any(CopyObjectRequest.class));
    doReturn(DeleteObjectResponse.builder().build())
        .when(s3)
        .deleteObject(any(DeleteObjectRequest.class));

    storage.move("uploads/tmp/x.jpg", "reviews/x.jpg");

    ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
    ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    InOrder order = inOrder(s3);
    order.verify(s3).copyObject(copy.capture());
    order.verify(s3).deleteObject(delete.capture());
    assertThat(copy.getValue().sourceBucket()).isEqualTo(BUCKET);
    assertThat(copy.getValue().sourceKey()).isEqualTo("uploads/tmp/x.jpg");
    assertThat(copy.getValue().destinationBucket()).isEqualTo(BUCKET);
    assertThat(copy.getValue().destinationKey()).isEqualTo("reviews/x.jpg");
    assertThat(delete.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(delete.getValue().key()).isEqualTo("uploads/tmp/x.jpg");
  }

  @Test
  @DisplayName("옮기기 — 원본이 없으면(NoSuchKey) MissingPhotoException, 지우지 않는다")
  void moveOfMissingSourceNoSuchKey() {
    doThrow(NoSuchKeyException.builder().statusCode(404).message("no").build())
        .when(s3)
        .copyObject(any(CopyObjectRequest.class));

    assertThatThrownBy(() -> storage.move("uploads/tmp/gone.jpg", "reviews/gone.jpg"))
        .isInstanceOf(PhotoStorage.MissingPhotoException.class)
        .hasMessageContaining("uploads/tmp/gone.jpg");
    verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  @DisplayName("옮기기 — 원본이 없으면(404 S3Exception) MissingPhotoException, 다른 S3 오류는 그대로 올라간다")
  void moveOfMissingSource404AndOtherErrors() {
    doThrow(S3Exception.builder().statusCode(404).message("not found").build())
        .when(s3)
        .copyObject(any(CopyObjectRequest.class));
    assertThatThrownBy(() -> storage.move("uploads/tmp/a.jpg", "reviews/a.jpg"))
        .isInstanceOf(PhotoStorage.MissingPhotoException.class);

    Mockito.reset(s3);
    doThrow(S3Exception.builder().statusCode(403).message("denied").build())
        .when(s3)
        .copyObject(any(CopyObjectRequest.class));
    assertThatThrownBy(() -> storage.move("uploads/tmp/a.jpg", "reviews/a.jpg"))
        .isInstanceOf(S3Exception.class)
        .isNotInstanceOf(PhotoStorage.MissingPhotoException.class);
  }

  @Test
  @DisplayName("옮기기 — 복사 뒤 원본 삭제가 실패해도 옮기기는 성공이다(원본은 수명 규칙이 지운다)")
  void moveSurvivesDeleteFailure() {
    doReturn(CopyObjectResponse.builder().build())
        .when(s3)
        .copyObject(any(CopyObjectRequest.class));
    doThrow(S3Exception.builder().statusCode(500).message("boom").build())
        .when(s3)
        .deleteObject(any(DeleteObjectRequest.class));

    storage.move("uploads/tmp/y.jpg", "reviews/y.jpg");

    verify(s3).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("지우기 — 그 버킷의 그 키를 지운다")
  void deleteRemovesThatKey() {
    doReturn(DeleteObjectResponse.builder().build())
        .when(s3)
        .deleteObject(any(DeleteObjectRequest.class));

    storage.delete("posts/p1/a.jpg");

    ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3).deleteObject(delete.capture());
    assertThat(delete.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(delete.getValue().key()).isEqualTo("posts/p1/a.jpg");
  }

  @Test
  @DisplayName("지우기 — 저장소가 실패하면 삼키지 않고 던진다(부른 쪽이 그 키를 지울 목록에 남긴다)")
  void deleteFailureIsThrown() {
    // PhotoStorage.delete Javadoc: 저장소가 실패하면 던진다. move 안의 원본 삭제와 달리 삼키지 않는다.
    doThrow(S3Exception.builder().statusCode(503).message("slow down").build())
        .when(s3)
        .deleteObject(any(DeleteObjectRequest.class));

    assertThatThrownBy(() -> storage.delete("posts/p1/a.jpg")).isInstanceOf(S3Exception.class);
  }

  @Test
  @DisplayName("쪽 목록 — 그 버킷·그 접두사로 묻고, 쪽마다 키·마지막 수정 시각을 넘긴다(다음 쪽 토큰을 따라간다)")
  void listPagesWalksEveryPage() {
    Instant t1 = Instant.parse("2026-10-01T00:00:00Z");
    Instant t2 = Instant.parse("2026-10-02T00:00:00Z");
    Instant t3 = Instant.parse("2026-10-03T00:00:00Z");
    List<ListObjectsV2Request> asked = new ArrayList<>();
    doAnswer(
            inv -> {
              ListObjectsV2Request r = inv.getArgument(0);
              asked.add(r);
              if (r.continuationToken() == null) {
                return ListObjectsV2Response.builder()
                    .contents(object("reviews/a.jpg", t1), object("reviews/b.jpg", t2))
                    .isTruncated(true)
                    .nextContinuationToken("page-2")
                    .build();
              }
              return ListObjectsV2Response.builder()
                  .contents(object("reviews/c.jpg", t3))
                  .isTruncated(false)
                  .build();
            })
        .when(s3)
        .listObjectsV2(any(ListObjectsV2Request.class));

    List<List<PhotoStorage.StoredObject>> pages = new ArrayList<>();
    storage.listPages("reviews/", pages::add);

    assertThat(pages)
        .containsExactly(
            List.of(
                new PhotoStorage.StoredObject("reviews/a.jpg", t1),
                new PhotoStorage.StoredObject("reviews/b.jpg", t2)),
            List.of(new PhotoStorage.StoredObject("reviews/c.jpg", t3)));
    assertThat(asked).allSatisfy(r -> assertThat(r.bucket()).isEqualTo(BUCKET));
    assertThat(asked).allSatisfy(r -> assertThat(r.prefix()).isEqualTo("reviews/"));
    assertThat(asked.get(1).continuationToken()).isEqualTo("page-2");
  }

  @Test
  @DisplayName("쪽 목록 — 저장소가 실패하면 던진다")
  void listPagesFailureIsThrown() {
    // PhotoStorage.listPages Javadoc: 저장소가 실패하면 던진다.
    doThrow(S3Exception.builder().statusCode(500).message("boom").build())
        .when(s3)
        .listObjectsV2(any(ListObjectsV2Request.class));

    assertThatThrownBy(() -> storage.listPages("posts/", page -> {}))
        .isInstanceOf(S3Exception.class);
  }

  private static S3Object object(String key, Instant lastModified) {
    return S3Object.builder().key(key).lastModified(lastModified).build();
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> out = new HashMap<>();
    Arrays.stream(url.getRawQuery().split("&"))
        .map(kv -> kv.split("=", 2))
        .forEach(
            kv ->
                out.put(
                    kv[0],
                    java.net.URLDecoder.decode(
                        kv.length > 1 ? kv[1] : "", java.nio.charset.StandardCharsets.UTF_8)));
    return out;
  }
}
