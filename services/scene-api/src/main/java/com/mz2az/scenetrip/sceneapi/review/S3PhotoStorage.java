package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Presigner;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
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

  /** 보여 줄 주소가 받은 뒤로 적어도 사는 시간 — 계약이 앱에 「적어도 한 시간」 이라고 약속한다(MZ2AZ-562). */
  static final Duration VIEW_TTL = Duration.ofHours(1);

  /** 보여 줄 주소의 창 기본값. 설정 {@code scenetrip.media.view-window} 가 바꾼다. */
  static final Duration DEFAULT_VIEW_WINDOW = Duration.ofHours(1);

  /** SigV4 서명 주소의 최대 수명(7 일). 창 + {@link #VIEW_TTL} 이 이것을 넘으면 S3 가 주소를 거절한다. */
  private static final Duration MAX_PRESIGN = Duration.ofDays(7);

  private final S3Client s3;
  private final Supplier<S3Presigner> presigners;
  private final String bucket;
  private final Clock clock;
  private final Duration viewWindow;

  S3PhotoStorage(S3Client s3, S3Presigner presigner, String bucket) {
    this(s3, () -> presigner, bucket);
  }

  /**
   * 서명기를 요청마다 고른다 — 로컬 MinIO 처럼 앱이 저장소를 부르는 주소가 요청마다 다를 때(시뮬레이터 localhost, 에뮬레이터 10.0.2.2). 서명에 주소가
   * 들어가 앱이 바꿀 수 없어서다({@link PhotoStorageConfiguration}).
   */
  S3PhotoStorage(S3Client s3, Supplier<S3Presigner> presigners, String bucket) {
    this(s3, presigners, bucket, Clock.systemUTC(), DEFAULT_VIEW_WINDOW);
  }

  /**
   * 보여 줄 주소를 창마다 하나로 만든다(MZ2AZ-562). 서명 시각을 지금이 아니라 창의 시작으로 내려, 같은 창 안에서는 같은 키에 글자 하나 다르지 않은 주소가
   * 나간다 — 브라우저가 주소를 캐시 열쇠로 쓰므로 다시 받지 않는다.
   *
   * @param clock 창을 고를 시계. 시간대는 상관없다 — 창은 UTC epoch 의 배수에 맞춘다.
   * @param viewWindow 창 길이. 주소는 창 + {@link #VIEW_TTL} 동안 살고, 브라우저 캐시는 창 길이만큼이다.
   */
  S3PhotoStorage(
      S3Client s3,
      Supplier<S3Presigner> presigners,
      String bucket,
      Clock clock,
      Duration viewWindow) {
    if (viewWindow.isNegative()
        || viewWindow.getSeconds() < 1
        || viewWindow.getNano() != 0
        || viewWindow.plus(VIEW_TTL).compareTo(MAX_PRESIGN) > 0) {
      throw new IllegalArgumentException(
          "scenetrip.media.view-window 는 1 초 이상, 초 단위, 6 일 23 시간 이하여야 합니다: " + viewWindow);
    }
    this.s3 = s3;
    this.presigners = presigners;
    this.bucket = bucket;
    this.clock = clock;
    this.viewWindow = viewWindow;
  }

  @Override
  public boolean available() {
    return true;
  }

  /**
   * 보여 줄 주소. 서명 시각은 창의 시작, 만료는 창 + {@link #VIEW_TTL} 이라 창 안 언제 받아도 적어도 한 시간 산다(최대 창 + 한 시간). 주소에
   * {@code response-cache-control} 을 실어 S3 가 「창 길이만큼 캐시해도 된다」 고 답하게 한다(서명에 들어가 바꿀 수 없다).
   */
  @Override
  @SuppressWarnings("deprecation") // 서명기 덮어쓰기 — SignedAt 의 설명
  public URI viewUrl(String storageKey) {
    long w = viewWindow.getSeconds();
    Instant windowStart =
        Instant.ofEpochSecond(Math.floorDiv(clock.instant().getEpochSecond(), w) * w);
    Duration lifetime = viewWindow.plus(VIEW_TTL);
    try {
      return presigners
          .get()
          .presignGetObject(
              r ->
                  r.signatureDuration(lifetime)
                      .getObjectRequest(
                          g ->
                              g.bucket(bucket)
                                  .key(storageKey)
                                  .responseCacheControl("private, max-age=" + w)
                                  .overrideConfiguration(
                                      o -> o.signer(new SignedAt(windowStart, lifetime)))))
          .url()
          .toURI();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 정해 둔 시각으로 서명하는 서명기 — 보여 줄 주소 전용.
   *
   * <p>S3Presigner 는 서명 시각을 {@code Instant.now()} 로 박아 두고 시계를 받지 않는다(SDK 2.55). 서명 시각을 바꿀 수 있는 공개
   * 통로는 요청별 서명기 덮어쓰기({@code overrideConfiguration().signer}) 하나뿐이라, 거기에 SDK 의 S3 SigV4 서명기를 감싸 넣고
   * 시각 ({@code SIGNING_CLOCK})과 만료({@code PRESIGNER_EXPIRATION})만 바꾼다. 자격 증명·리전·주소는 S3Presigner 가
   * 고른 그대로다. 이 API 들은 deprecated 이지만 2.55 에서 동작하고 대체 수단이 없다 — SDK 를 올릴 때 {@code S3PhotoStorageTest}
   * 의 창 시험이 지켜 본다.
   */
  @SuppressWarnings("deprecation")
  private static final class SignedAt implements Signer, Presigner {
    private static final AwsS3V4Signer S3_V4 = AwsS3V4Signer.create();

    private final Instant signedAt;
    private final Instant expiresAt;

    SignedAt(Instant signedAt, Duration lifetime) {
      this.signedAt = signedAt;
      this.expiresAt = signedAt.plus(lifetime);
    }

    @Override
    public SdkHttpFullRequest presign(SdkHttpFullRequest request, ExecutionAttributes attributes) {
      ExecutionAttributes at = attributes.copy();
      at.putAttribute(
          AwsSignerExecutionAttribute.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC));
      at.putAttribute(AwsSignerExecutionAttribute.PRESIGNER_EXPIRATION, expiresAt);
      return S3_V4.presign(request, at);
    }

    @Override
    public SdkHttpFullRequest sign(SdkHttpFullRequest request, ExecutionAttributes attributes) {
      throw new UnsupportedOperationException("보여 줄 주소의 미리 서명에만 쓴다");
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

  /**
   * S3 는 없는 키를 지워도 성공을 돌려준다. PRD 버킷은 버전 관리가 켜져 있어 「삭제 표시」만 얹히고 원본은 이전 버전으로 남았다가 수명 규칙이 30 일 뒤
   * 지운다(계획 {@code post-photo-deletion.md} §1).
   */
  @Override
  public void delete(String storageKey) {
    s3.deleteObject(d -> d.bucket(bucket).key(storageKey));
  }

  @Override
  public byte[] read(String storageKey) {
    try {
      return s3.getObjectAsBytes(g -> g.bucket(bucket).key(storageKey)).asByteArray();
    } catch (NoSuchKeyException e) {
      throw new MissingPhotoException(storageKey);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        throw new MissingPhotoException(storageKey);
      }
      throw e;
    }
  }

  @Override
  public void put(String storageKey, byte[] bytes, String contentType) {
    s3.putObject(
        p -> p.bucket(bucket).key(storageKey).contentType(contentType),
        RequestBody.fromBytes(bytes));
  }

  @Override
  public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {
    s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix))
        .forEach(
            page ->
                onPage.accept(
                    page.contents().stream()
                        .map(o -> new StoredObject(o.key(), o.lastModified()))
                        .toList()));
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
