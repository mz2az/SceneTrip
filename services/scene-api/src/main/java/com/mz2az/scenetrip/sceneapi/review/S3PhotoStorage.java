package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * S3 의 사용자 사진 버킷({@code scenetrip-user-media-<계정>-<리전>-<환경>}, bootstrap 이 만든다 — 계획 {@code
 * review.md} §13).
 *
 * <p>자격 증명은 설정이 고른다({@link PhotoStorageConfiguration}): DEV·PRD 는 SDK 기본 사슬(EKS Pod Identity), 로컬은
 * kind 안 MinIO 의 고정값. 코드에 키가 없다.
 */
final class S3PhotoStorage implements PhotoStorage {

  /** 보여 줄 주소의 수명 — 계약이 앱에 「한 시간 뒤 만료」 라고 약속한다. */
  static final Duration VIEW_TTL = Duration.ofHours(1);

  private final S3Client s3;
  private final Supplier<S3Presigner> presigners;
  private final String bucket;

  S3PhotoStorage(S3Client s3, S3Presigner presigner, String bucket) {
    this(s3, () -> presigner, bucket);
  }

  /**
   * 서명기를 요청마다 고른다 — 로컬 MinIO 처럼 앱이 저장소를 부르는 주소가 요청마다 다를 때(시뮬레이터 localhost, 에뮬레이터 10.0.2.2). 서명에 주소가
   * 들어가 앱이 바꿀 수 없어서다({@link PhotoStorageConfiguration}).
   */
  S3PhotoStorage(S3Client s3, Supplier<S3Presigner> presigners, String bucket) {
    this.s3 = s3;
    this.presigners = presigners;
    this.bucket = bucket;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public URI viewUrl(String storageKey) {
    try {
      return presigners
          .get()
          .presignGetObject(
              r ->
                  r.signatureDuration(VIEW_TTL)
                      .getObjectRequest(g -> g.bucket(bucket).key(storageKey)))
          .url()
          .toURI();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public PresignedUpload presignUpload(
      String storageKey, String contentType, long bytes, Duration expiresIn) {
    PresignedPutObjectRequest p =
        presigners
            .get()
            .presignPutObject(
                r ->
                    r.signatureDuration(expiresIn)
                        .putObjectRequest(
                            o ->
                                o.bucket(bucket)
                                    .key(storageKey)
                                    .contentType(contentType)
                                    .contentLength(bytes)));
    // 서명에 들어간 헤더 중 앱이 붙여야 하는 것. host 는 주소에 이미 있다. 헤더 이름은 정해진 순서로.
    Map<String, String> headers = new TreeMap<>();
    p.signedHeaders()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("host")) {
                headers.put(canonical(name), String.join(",", values));
              }
            });
    try {
      return new PresignedUpload(p.url().toURI(), headers, p.expiration());
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void move(String fromKey, String toKey) {
    try {
      s3.copyObject(
          c ->
              c.sourceBucket(bucket)
                  .sourceKey(fromKey)
                  .destinationBucket(bucket)
                  .destinationKey(toKey));
    } catch (NoSuchKeyException e) {
      throw new MissingPhotoException(fromKey);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        throw new MissingPhotoException(fromKey);
      }
      throw e;
    }
    // 원본을 못 지워도 옮긴 것은 이미 있다 — 원본은 수명 규칙이 하루 뒤 지운다.
    try {
      s3.deleteObject(d -> d.bucket(bucket).key(fromKey));
    } catch (S3Exception e) {
      // 무시한다(위 주석)
    }
  }

  /** {@code content-type} → {@code Content-Type}. 앱 개발자가 읽기 쉽게. */
  private static String canonical(String name) {
    StringBuilder out = new StringBuilder();
    for (String part : name.split("-")) {
      if (!out.isEmpty()) {
        out.append('-');
      }
      out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }
}
