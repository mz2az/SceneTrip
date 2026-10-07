package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 사진 저장소 — 서명은 망 없이 만들어진다(가짜 고정 자격 증명), 옮기기는 목 {@link S3Client} 로 본다. 실제 AWS 를 부르지 않는다.
 *
 * <p>계약: 올릴 주소는 10 분이고 형식·크기가 서명에 들어간다. 보여 줄 주소는 한 시간이다.
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
  @DisplayName("보여 줄 주소 — 그 버킷·그 키로 GET, 만료 한 시간")
  void presignedGet() {
    URI url = storage.viewUrl("reviews/abc.webp");

    assertThat(url.getHost() + url.getPath()).contains(BUCKET).endsWith("/reviews/abc.webp");
    Map<String, String> q = query(url);
    assertThat(q).containsEntry("X-Amz-Expires", "3600");
    assertThat(q).containsKey("X-Amz-Signature");
    assertThat(S3PhotoStorage.VIEW_TTL).isEqualTo(Duration.ofHours(1));
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
