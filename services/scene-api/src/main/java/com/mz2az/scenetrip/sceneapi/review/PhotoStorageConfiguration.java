package com.mz2az.scenetrip.sceneapi.review;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 사진 저장소를 고른다 — 버킷 이름이 있으면 S3(또는 S3 대역), 없으면 {@link NoPhotoStorage}.
 *
 * <p>버킷이 없어도 서버는 뜬다. 카카오 키·JWT 키와 같은 규칙이다(application.yaml) — 키 없는 개발자가 검색·코스까지 못 쓰면 안 된다. 그때 사진
 * 올리기만 503 이다.
 *
 * <p><b>DEV·PRD</b> 는 버킷 이름만 있다 — 진짜 S3, 자격 증명은 SDK 기본 사슬(EKS Pod Identity). <b>로컬</b> 은 클러스터 안
 * MinIO 라 셋이 더 붙는다(platform/kubernetes/scene-api/configmap.yaml, docs/project/plans/review.md §14):
 *
 * <ul>
 *   <li>{@code endpoint} — 서버가 저장소와 직접 대화할 주소(클러스터 안의 이름 {@code minio:9000}). 경로형 주소를 쓴다 — MinIO 는
 *       버킷을 호스트 이름으로 받지 않는다.
 *   <li>{@code public-port} — 앱에 줄 서명된 주소의 포트. 호스트는 <b>이 요청이 들어온 주소</b>를 따른다. 앱이 서버를 {@code
 *       localhost:8081} 로 불렀으면 {@code localhost:9000}, {@code 10.0.2.2:8081} 이면 {@code
 *       10.0.2.2:9000}. 서명에 주소가 들어가 앱이 고칠 수 없으므로 처음부터 앱이 닿는 주소로 서명해야 한다.
 *   <li>{@code access-key}·{@code secret-key} — MinIO 의 로컬 고정값. 비어 있으면 기본 사슬.
 * </ul>
 */
@Configuration
class PhotoStorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PhotoStorageConfiguration.class);

  @Bean
  PhotoStorage photoStorage(
      @Value("${scenetrip.media.bucket:}") String bucket,
      @Value("${scenetrip.media.region:ap-northeast-2}") String region,
      @Value("${scenetrip.media.endpoint:}") String endpoint,
      @Value("${scenetrip.media.public-port:}") String publicPort,
      @Value("${scenetrip.media.access-key:}") String accessKey,
      @Value("${scenetrip.media.secret-key:}") String secretKey) {
    if (bucket.isBlank()) {
      log.info("사진 저장소가 설정되지 않았습니다(scenetrip.media.bucket) — 사진 올리기는 503 입니다");
      return new NoPhotoStorage();
    }
    Region r = Region.of(region);
    AwsCredentialsProvider credentials =
        accessKey.isBlank()
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

    // HTTP 클라이언트는 JDK 의 것 하나만 둔다(MODULE.bazel 이 Netty·Apache 를 뺐다). 명시해 고른다 —
    // 클래스패스에서 찾게 두면 무엇이 잡힐지가 의존성 변화에 따라 조용히 바뀐다.
    var s3 =
        S3Client.builder()
            .region(r)
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create());
    if (!endpoint.isBlank()) {
      s3.endpointOverride(URI.create(endpoint)).serviceConfiguration(pathStyle);
    }

    Supplier<S3Presigner> presigners;
    if (publicPort.isBlank()) {
      var p = S3Presigner.builder().region(r).credentialsProvider(credentials);
      if (!endpoint.isBlank()) {
        p.endpointOverride(URI.create(endpoint)).serviceConfiguration(pathStyle);
      }
      S3Presigner presigner = p.build();
      presigners = () -> presigner;
    } else {
      // 앱이 부른 호스트마다 서명기 하나 — 몇 개 안 된다(localhost · 10.0.2.2 · 맥의 IP).
      Map<String, S3Presigner> byHost = new ConcurrentHashMap<>();
      presigners =
          () ->
              byHost.computeIfAbsent(
                  requestHost(),
                  host ->
                      S3Presigner.builder()
                          .region(r)
                          .credentialsProvider(credentials)
                          .endpointOverride(URI.create("http://" + host + ":" + publicPort))
                          .serviceConfiguration(pathStyle)
                          .build());
    }
    log.info(
        "사진 저장소: {} 의 {} ({}){}",
        endpoint.isBlank() ? "S3" : endpoint,
        bucket,
        region,
        publicPort.isBlank() ? "" : " — 앱용 주소는 요청 호스트:" + publicPort);
    return new S3PhotoStorage(s3.build(), presigners, bucket);
  }

  /** 이 요청이 들어온 호스트 이름(포트 없이). 요청 밖에서 불리면 localhost. */
  private static String requestHost() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest request = a.getRequest();
      String host = request.getServerName();
      // Host 헤더는 보내는 쪽 마음이다. 이름·IPv4 글자만 받는다 — 그 밖이면 주소를 만들 수 없다. 이 모드는 로컬에서만
      // 켜지고, 남의 호스트로 서명해 줘도 그 서명은 그 요청자 자신의 올리기 하나에만 쓸 수 있다.
      if (host != null && host.matches("[A-Za-z0-9.-]{1,253}")) {
        return host;
      }
    }
    return "localhost";
  }
}
