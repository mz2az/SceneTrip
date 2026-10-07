package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 저장소 고르기 — 설정 {@code scenetrip.media.*} 여섯 값으로 정한다(계획 review.md §14).
 *
 * <ul>
 *   <li>bucket 이 비면 「없음」(올리기 503).
 *   <li>bucket 만 있으면(DEV·PRD) 진짜 S3 — 서명된 주소는 그 리전의 표준 S3 호스트, 자격 증명은 기본 사슬.
 *   <li>endpoint 가 있으면(로컬) 서버의 복사·삭제가 그 주소로 경로형으로 가고, access-key·secret-key 가 있으면 고정 자격 증명.
 *   <li>public-port 가 있으면(로컬) 앱에 줄 주소는 {@code http://<이 요청의 호스트>:<public-port>/<bucket>/<key>}.
 * </ul>
 *
 * <p>실제 AWS 를 부르지 않는다. 서명은 망 없이 만들어지고, 서버 쪽 호출은 JDK 내장 {@code HttpServer} 로 띄운 가짜 저장소(루프백)가 받는다.
 */
@DisplayName("PhotoStorageConfiguration — 설정으로 저장소를 고른다")
class PhotoStorageConfigurationTest {

  private static final String DEV_BUCKET = "scenetrip-user-media-000000000000-ap-northeast-2-dev";
  private static final String LOCAL_BUCKET = "scenetrip-user-media";
  private static final String LOCAL_KEY = "scenetrip";
  private static final String LOCAL_SECRET = "scenetrip-local-minio";

  private final PhotoStorageConfiguration config = new PhotoStorageConfiguration();

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  // ── 버킷 ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("버킷이 비었거나 공백뿐이면 available() 이 false 인 저장소 — 다른 값이 다 있어도")
  void blankBucketIsUnavailable() {
    for (String bucket : new String[] {"", "   "}) {
      PhotoStorage prod = config.photoStorage(bucket, "ap-northeast-2", "", "", "", "");
      PhotoStorage local =
          config.photoStorage(
              bucket, "us-east-1", "http://minio:9000", "9000", LOCAL_KEY, LOCAL_SECRET);

      for (PhotoStorage storage : List.of(prod, local)) {
        assertThat(storage.available()).isFalse();
        assertThat(storage).isNotInstanceOf(S3PhotoStorage.class);
      }
    }
  }

  @Test
  @DisplayName("저장소가 없으면 올릴 주소·옮기기는 크게 실패한다(불릴 일이 없어야 한다)")
  void unavailableStorageRefusesWork() {
    PhotoStorage storage = config.photoStorage("", "ap-northeast-2", "", "", "", "");

    assertThatThrownBy(
            () ->
                storage.presignUpload("uploads/tmp/a.jpg", "image/jpeg", 1, Duration.ofMinutes(10)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> storage.move("uploads/tmp/a.jpg", "reviews/a.jpg"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("버킷 이름이 있으면 S3 저장소, available() 은 true — DEV·PRD 모양이든 로컬 모양이든")
  void bucketSelectsS3() {
    PhotoStorage prod = config.photoStorage(DEV_BUCKET, "ap-northeast-2", "", "", "", "");
    PhotoStorage local =
        config.photoStorage(
            LOCAL_BUCKET, "us-east-1", "http://minio:9000", "9000", LOCAL_KEY, LOCAL_SECRET);

    for (PhotoStorage storage : List.of(prod, local)) {
      assertThat(storage).isInstanceOf(S3PhotoStorage.class);
      assertThat(storage.available()).isTrue();
    }
  }

  // ── DEV·PRD: 버킷만 ─────────────────────────────────────────────────────────

  /** 기본 사슬의 첫 고리(시스템 속성)에 가짜 키를 둔다 — 그 키가 서명에 나오면 기본 사슬을 쓴 것이다. 메타데이터 서버(망)까지 가지 않는다. */
  @Nested
  @DisplayName("DEV·PRD — 버킷만 있으면 진짜 S3 와 기본 자격 증명 사슬")
  class RealS3 {

    private static final String CHAIN_KEY = "AKIADEFAULTCHAINFAKE";

    private String savedKey;
    private String savedSecret;

    @BeforeEach
    void fakeDefaultChain() {
      savedKey = System.getProperty("aws.accessKeyId");
      savedSecret = System.getProperty("aws.secretAccessKey");
      System.setProperty("aws.accessKeyId", CHAIN_KEY);
      System.setProperty("aws.secretAccessKey", "default/chain/fake/secret");
    }

    @AfterEach
    void restore() {
      restoreProperty("aws.accessKeyId", savedKey);
      restoreProperty("aws.secretAccessKey", savedSecret);
    }

    @Test
    @DisplayName("올릴·볼 주소 — https, 그 리전의 표준 S3 호스트(가상 호스트형 또는 경로형), 그 리전으로 서명")
    void presignsAgainstRegionalS3Host() {
      PhotoStorage storage = config.photoStorage(DEV_BUCKET, "ap-northeast-2", "", "", "", "");

      URI view = storage.viewUrl("reviews/abc.webp");
      URI put =
          storage
              .presignUpload("uploads/tmp/a.jpg", "image/jpeg", 10, Duration.ofMinutes(10))
              .url();

      assertStandardS3(view, DEV_BUCKET, "ap-northeast-2", "reviews/abc.webp");
      assertStandardS3(put, DEV_BUCKET, "ap-northeast-2", "uploads/tmp/a.jpg");
    }

    @Test
    @DisplayName("리전은 설정을 따른다 — 다른 리전이면 그 리전의 호스트와 서명 범위")
    void regionFollowsSetting() {
      PhotoStorage storage = config.photoStorage("some-bucket", "us-west-2", "", "", "", "");

      assertStandardS3(
          storage.viewUrl("reviews/x.jpg"), "some-bucket", "us-west-2", "reviews/x.jpg");
    }

    @Test
    @DisplayName("access-key 가 비면 자격 증명은 기본 사슬에서 온다")
    void blankAccessKeyUsesDefaultChain() {
      PhotoStorage storage = config.photoStorage(DEV_BUCKET, "ap-northeast-2", "", "", "", "");

      assertThat(query(storage.viewUrl("reviews/abc.webp")).get("X-Amz-Credential"))
          .startsWith(CHAIN_KEY + "/");
    }

    @Test
    @DisplayName("요청 안에서 불려도 DEV·PRD 주소는 요청 호스트를 따르지 않는다(public-port 가 없으므로)")
    void ignoresRequestHostWithoutPublicPort() {
      bindRequest(serverName("10.0.2.2"));
      PhotoStorage storage = config.photoStorage(DEV_BUCKET, "ap-northeast-2", "", "", "", "");

      assertStandardS3(
          storage.viewUrl("reviews/abc.webp"), DEV_BUCKET, "ap-northeast-2", "reviews/abc.webp");
    }
  }

  // ── 로컬: endpoint (서버 쪽 호출) ────────────────────────────────────────────

  @Nested
  @DisplayName("로컬 endpoint — 서버의 복사·삭제는 그 주소로, 경로형, 고정 자격 증명")
  class Endpoint {

    private static HttpServer server;
    private static String endpoint;
    private static final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private static volatile int copyStatus;

    record Recorded(String method, String rawPath, String host, String auth, String copySource) {}

    @BeforeAll
    static void startFakeStore() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.add(
                new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-amz-copy-source")));
            if (exchange.getRequestMethod().equals("DELETE")) {
              exchange.sendResponseHeaders(204, -1);
              exchange.close();
              return;
            }
            String xml =
                copyStatus == 200
                    ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<CopyObjectResult><ETag>\"0123456789abcdef\"</ETag>"
                        + "<LastModified>2026-10-07T00:00:00.000Z</LastModified></CopyObjectResult>"
                    : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Error><Code>NoSuchKey</Code><Message>gone</Message></Error>";
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(copyStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          });
      server.start();
      endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeStore() {
      server.stop(0);
    }

    @BeforeEach
    void reset() {
      requests.clear();
      copyStatus = 200;
    }

    @Test
    @DisplayName("옮기기 — PUT /<bucket>/<새 키>(복사), 그다음 DELETE /<bucket>/<원래 키>, 둘 다 그 endpoint 로")
    void moveGoesToEndpointPathStyle() {
      PhotoStorage storage =
          config.photoStorage(LOCAL_BUCKET, "us-east-1", endpoint, "", LOCAL_KEY, LOCAL_SECRET);

      storage.move("uploads/tmp/x.jpg", "reviews/x.jpg");

      assertThat(requests).hasSize(2);
      Recorded copy = requests.get(0);
      Recorded delete = requests.get(1);
      assertThat(copy.method()).isEqualTo("PUT");
      assertThat(copy.rawPath()).isEqualTo("/" + LOCAL_BUCKET + "/reviews/x.jpg");
      assertThat(URLDecoder.decode(copy.copySource(), StandardCharsets.UTF_8))
          .isEqualTo(LOCAL_BUCKET + "/uploads/tmp/x.jpg");
      assertThat(delete.method()).isEqualTo("DELETE");
      assertThat(delete.rawPath()).isEqualTo("/" + LOCAL_BUCKET + "/uploads/tmp/x.jpg");
      for (Recorded r : requests) {
        // 경로형: 버킷이 호스트 이름에 붙지 않는다.
        assertThat(r.host()).isEqualTo("127.0.0.1:" + server.getAddress().getPort());
      }
    }

    @Test
    @DisplayName("access-key·secret-key 가 있으면 그 고정 자격 증명으로, 설정한 리전(us-east-1)으로 서명한다")
    void staticCredentialsAndRegionSignServerCalls() {
      PhotoStorage storage =
          config.photoStorage(LOCAL_BUCKET, "us-east-1", endpoint, "", LOCAL_KEY, LOCAL_SECRET);

      storage.move("uploads/tmp/y.jpg", "reviews/y.jpg");

      assertThat(requests).isNotEmpty();
      for (Recorded r : requests) {
        assertThat(r.auth())
            .startsWith("AWS4-HMAC-SHA256")
            .contains("Credential=" + LOCAL_KEY + "/")
            .contains("/us-east-1/s3/aws4_request");
      }
    }

    @Test
    @DisplayName("public-port 가 있어도 서버의 복사·삭제는 endpoint 로 간다(앱용 주소로 가지 않는다)")
    void publicPortDoesNotRedirectServerCalls() {
      bindRequest(serverName("localhost"));
      PhotoStorage storage =
          config.photoStorage(LOCAL_BUCKET, "us-east-1", endpoint, "9000", LOCAL_KEY, LOCAL_SECRET);

      storage.move("uploads/tmp/z.jpg", "reviews/z.jpg");

      assertThat(requests).extracting(Recorded::method).containsExactly("PUT", "DELETE");
      assertThat(requests)
          .allSatisfy(
              r -> assertThat(r.host()).isEqualTo("127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    @DisplayName("endpoint 의 저장소가 원본이 없다고(404) 하면 MissingPhotoException, 지우지 않는다")
    void missingSourceAtEndpoint() {
      copyStatus = 404;
      PhotoStorage storage =
          config.photoStorage(LOCAL_BUCKET, "us-east-1", endpoint, "", LOCAL_KEY, LOCAL_SECRET);

      assertThatThrownBy(() -> storage.move("uploads/tmp/gone.jpg", "reviews/gone.jpg"))
          .isInstanceOf(PhotoStorage.MissingPhotoException.class);
      assertThat(requests).extracting(Recorded::method).containsExactly("PUT");
    }
  }

  // ── 로컬: public-port (앱에 줄 주소) ─────────────────────────────────────────

  @Nested
  @DisplayName("로컬 public-port — 앱에 줄 주소는 http://<이 요청의 호스트>:<public-port>/<bucket>/<key>")
  class PublicPort {

    private PhotoStorage local() {
      return config.photoStorage(
          LOCAL_BUCKET, "us-east-1", "http://minio:9000", "9000", LOCAL_KEY, LOCAL_SECRET);
    }

    @Test
    @DisplayName("localhost:8081 로 온 요청 → 올릴·볼 주소 모두 http://localhost:9000/<bucket>/<key>")
    void localhostRequest() {
      MockHttpServletRequest request = serverName("localhost");
      request.setServerPort(8081);
      bindRequest(request);
      PhotoStorage storage = local();

      URI view = storage.viewUrl("reviews/abc.webp");
      URI put =
          storage
              .presignUpload("uploads/tmp/a.jpg", "image/jpeg", 10, Duration.ofMinutes(10))
              .url();

      assertLocal(view, "localhost", "reviews/abc.webp");
      assertLocal(put, "localhost", "uploads/tmp/a.jpg");
    }

    @Test
    @DisplayName("Host: 10.0.2.2:8081(안드로이드 에뮬레이터) → http://10.0.2.2:9000/…")
    void emulatorHostHeader() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/uploads");
      request.addHeader("Host", "10.0.2.2:8081");
      bindRequest(request);

      assertLocal(local().viewUrl("reviews/abc.webp"), "10.0.2.2", "reviews/abc.webp");
    }

    @Test
    @DisplayName("같은 와이파이의 폰(맥의 LAN IP) → 그 IP:9000")
    void lanIp() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/uploads");
      request.addHeader("Host", "192.168.0.23:8081");
      bindRequest(request);

      URI put =
          local().presignUpload("uploads/tmp/a.jpg", "image/png", 99, Duration.ofMinutes(10)).url();

      assertLocal(put, "192.168.0.23", "uploads/tmp/a.jpg");
    }

    @Test
    @DisplayName("이름 형식이 아닌 호스트(공백·슬래시·@·밑줄·IPv6 괄호·빈 값)는 localhost 로")
    void unsafeHostFallsBackToLocalhost() {
      for (String bad :
          new String[] {"evil host", "evil.example/x", "user@evil", "a_b", "[::1]", ""}) {
        bindRequest(serverName(bad));

        assertLocal(local().viewUrl("reviews/abc.webp"), "localhost", "reviews/abc.webp");
      }
    }

    @Test
    @DisplayName("요청 밖에서 불리면 localhost")
    void outsideRequestIsLocalhost() {
      RequestContextHolder.resetRequestAttributes();

      assertLocal(local().viewUrl("reviews/abc.webp"), "localhost", "reviews/abc.webp");
    }

    @Test
    @DisplayName("한 저장소가 요청마다 그 요청의 호스트로 서명한다 — 호스트가 다르면 주소와 서명이 다르다")
    void eachHostGetsItsOwnSignature() {
      PhotoStorage storage = local();

      bindRequest(serverName("localhost"));
      URI a = storage.viewUrl("reviews/abc.webp");
      bindRequest(serverName("10.0.2.2"));
      URI b = storage.viewUrl("reviews/abc.webp");
      bindRequest(serverName("localhost"));
      URI c = storage.viewUrl("reviews/abc.webp");

      assertLocal(a, "localhost", "reviews/abc.webp");
      assertLocal(b, "10.0.2.2", "reviews/abc.webp");
      assertLocal(c, "localhost", "reviews/abc.webp");
      // host 는 서명된 헤더다 — 앱이 호스트를 바꿔 쓸 수 없다.
      assertThat(query(a).get("X-Amz-SignedHeaders").split(";")).contains("host");
      assertThat(query(b).get("X-Amz-SignedHeaders").split(";")).contains("host");
      assertThat(query(a).get("X-Amz-Signature")).isNotEqualTo(query(b).get("X-Amz-Signature"));
    }

    @Test
    @DisplayName("고정 자격 증명과 설정한 리전(us-east-1)으로 서명한다")
    void signsWithStaticKeyAndRegion() {
      bindRequest(serverName("localhost"));

      String credential = query(local().viewUrl("reviews/abc.webp")).get("X-Amz-Credential");

      assertThat(credential).startsWith(LOCAL_KEY + "/").endsWith("/us-east-1/s3/aws4_request");
    }

    @Test
    @DisplayName("올릴 주소의 계약은 그대로 — 10 분, content-type·content-length 서명, host 는 필수 헤더에서 뺀다")
    void uploadContractUnchanged() {
      bindRequest(serverName("localhost"));

      PhotoStorage.PresignedUpload p =
          local()
              .presignUpload("uploads/tmp/a.jpg", "image/jpeg", 123_456L, Duration.ofMinutes(10));

      Map<String, String> q = query(p.url());
      assertThat(q).containsEntry("X-Amz-Expires", "600");
      assertThat(q.get("X-Amz-SignedHeaders").split(";"))
          .contains("host", "content-type", "content-length");
      assertThat(p.requiredHeaders())
          .containsEntry("Content-Type", "image/jpeg")
          .containsEntry("Content-Length", "123456");
      assertThat(p.requiredHeaders().keySet()).noneMatch(h -> h.equalsIgnoreCase("host"));
    }
  }

  // ── 도우미 ───────────────────────────────────────────────────────────────────

  private static MockHttpServletRequest serverName(String host) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/uploads");
    request.setServerName(host);
    return request;
  }

  private static void bindRequest(MockHttpServletRequest request) {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static void assertLocal(URI url, String host, String key) {
    assertThat(url.getScheme()).isEqualTo("http");
    assertThat(url.getHost()).isEqualTo(host);
    assertThat(url.getPort()).isEqualTo(9000);
    assertThat(url.getRawPath()).isEqualTo("/" + LOCAL_BUCKET + "/" + key);
    assertThat(query(url)).containsKey("X-Amz-Signature");
  }

  /**
   * 가상 호스트형({@code <bucket>.s3.<region>.amazonaws.com/<key>}) 또는 경로형({@code
   * s3.<region>…/<bucket>/<key>}).
   */
  private static void assertStandardS3(URI url, String bucket, String region, String key) {
    assertThat(url.getScheme()).isEqualTo("https");
    assertThat(url.getPort()).isEqualTo(-1);
    String regional = "s3." + region + ".amazonaws.com";
    if (url.getHost().equals(bucket + "." + regional)) {
      assertThat(url.getRawPath()).isEqualTo("/" + key);
    } else {
      assertThat(url.getHost()).isEqualTo(regional);
      assertThat(url.getRawPath()).isEqualTo("/" + bucket + "/" + key);
    }
    assertThat(query(url).get("X-Amz-Credential")).endsWith("/" + region + "/s3/aws4_request");
    assertThat(query(url)).containsKey("X-Amz-Signature");
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> out = new HashMap<>();
    Arrays.stream(url.getRawQuery().split("&"))
        .map(kv -> kv.split("=", 2))
        .forEach(
            kv ->
                out.put(
                    kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8)));
    return out;
  }
}
