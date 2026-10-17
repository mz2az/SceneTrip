package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 통합 시험의 바탕 — 요청부터 실제 DB 까지(docs/project/plans/api-integration-tests.md).
 *
 * <p>앱 전체(필터·컨트롤러·서비스·Store)를 띄우고 MockMvc 로 요청한다. DB 는 다른 통합 시험과 같은 접속({@code just
 * test-integration} 이 넣는 환경변수 — 로컬 kind 또는 CI 서비스 컨테이너)이고, 그 주소가 localhost 가 아니면 시작하지 않는다.
 *
 * <p>시험마다 한 트랜잭션 안에서 돌고 끝나면 되돌린다 — MockMvc 의 요청은 시험과 같은 스레드라 앱의 질의가 이 트랜잭션에 묶인다. 그래서 픽스처 행({@code
 * TEST-…})을 지우는 정리 코드가 없다. 로컬은 전량 데이터, CI 는 표본이라 전체 개수에 기대지 않고 자기가 만든 행만 본다.
 *
 * <p>가짜는 바깥 API 뿐이다 — 카카오 길찾기, 챗봇 에이전트, 사진 저장소(S3), 네이버 장소 찾기. 스케줄 작업은 모두 끈다(시험 밖 스레드가 실제 DB 를 고치지
 * 않게). 스키마는 미리 올려 둔 DB 라 Flyway 도 끈다.
 */
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=false",
      // 시험 전용 서명 키(base64 32 바이트) — 실제 비밀값이 아니다.
      "scenetrip.auth.jwt-secret=VEVTVC1vbmx5LWFwaS1pbnRlZ3JhdGlvbi1rZXktMzJieXRlcyE=",
      "scenetrip.popularity.schedule-enabled=false",
      "scenetrip.media.deletion.schedule-enabled=false",
      "scenetrip.media.orphan-sweep.schedule-enabled=false",
      "scenetrip.guide.turn-log-purge-cron=-",
      "scenetrip.guest.purge-cron=-",
      "scenetrip.review.archive-purge-cron=-",
      "scenetrip.moderation.report-purge-cron=-",
      "scenetrip.naver.link-lookup.enabled=false",
      // 카카오 클라이언트는 가짜다 — 키가 비어 길찾기가 꺼진 상태로 뜨지 않게 아무 값이나 넣는다(실제 키가 아니다).
      "scenetrip.navigation.kakao.rest-key=TEST-not-a-key",
    })
@AutoConfigureMockMvc
@Transactional
abstract class ApiIntegrationTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = require("SCENETRIP_TEST_JDBC_URL");
    // 운영 DB 에 닿는 길을 막는다 — 노트북은 kind 포트포워드, CI 는 서비스 컨테이너, 둘 다 localhost 다.
    if (!url.startsWith("jdbc:postgresql://localhost:")
        && !url.startsWith("jdbc:postgresql://127.0.0.1:")) {
      throw new IllegalStateException("API 통합 시험은 localhost DB 에만 붙는다: " + url);
    }
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> require("SCENETRIP_TEST_DB_USER"));
    registry.add("spring.datasource.password", () -> require("SCENETRIP_TEST_DB_PASSWORD"));
  }

  private static String require(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name + " 환경변수가 없습니다. `just test-integration` 으로 실행하세요 (IntegrationDatabase 와 같은 접속).");
    }
    return value;
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokens tokens;

  @MockitoBean KakaoRoutingClient kakao;
  @MockitoBean GuideAgentClient agent;
  @MockitoBean PhotoStorage photos;
  @MockitoBean NaverPlaceClient naver;

  /** 이 시험 하나의 이름표 — 픽스처 이름·번호에 붙여 다른 행과 섞이지 않게 한다. */
  final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @BeforeEach
  void fakeStorage() {
    when(photos.available()).thenReturn(true);
    when(photos.viewUrl(anyString()))
        .thenAnswer(inv -> URI.create("https://media.test/" + inv.getArgument(0, String.class)));
    when(photos.presignUpload(anyString(), anyString(), anyLong(), any()))
        .thenAnswer(
            inv ->
                new PhotoStorage.PresignedUpload(
                    URI.create("https://media.test/upload/" + inv.getArgument(0, String.class)),
                    Map.of("Content-Type", inv.getArgument(1, String.class)),
                    Instant.now().plusSeconds(600)));
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  /** 바다 위(경도 124.5 근처) — 적재 데이터의 촬영지·편의시설과 겹치지 않는 자리. */
  static final double SEA_LNG = 124.5;

  static final double SEA_LAT = 34.0;

  long place(String koName, double lng, double lat) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('카페',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
            .param("lng", lng)
            .param("lat", lat)
            .query(Long.class)
            .single();
    placeI18n(id, "ko", koName, null);
    return id;
  }

  void placeI18n(long placeId, String lang, String name, String description) {
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name, description)"
                + " VALUES (:p, CAST(:l AS lang_code), :n, :d)")
        .param("p", placeId)
        .param("l", lang)
        .param("n", name)
        .param("d", description)
        .update();
  }

  long content(String koTitle, String description) {
    long id =
        jdbc.sql("INSERT INTO content (category, content_key) VALUES ('drama', :k) RETURNING id")
            .param("k", "TEST-" + tag + "-" + koTitle.hashCode())
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO content_i18n (content_id, lang, title, description)"
                + " VALUES (:c, 'ko', :t, :d)")
        .param("c", id)
        .param("t", koTitle)
        .param("d", description)
        .update();
    return id;
  }

  void link(long placeId, long contentId) {
    jdbc.sql("INSERT INTO place_content (place_id, content_id) VALUES (:p, :c)")
        .param("p", placeId)
        .param("c", contentId)
        .update();
  }

  long poi(String name, double lng, double lat) {
    String sourceId = "TEST-" + tag + "-" + name.hashCode();
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '관광지', 'sight')
            RETURNING id
            """)
        .param("s", sourceId)
        .param("n", name)
        .param("lng", lng)
        .param("lat", lat)
        .query(Long.class)
        .single();
  }

  /** 가입한 회원 — 닉네임이 있고 가입 시각이 찍혀 있다. */
  UUID member(String nickname) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO app_user (id, registered_at, nickname, nickname_confirmed)"
                + " VALUES (:id, now(), :n, true)")
        .param("id", id)
        .param("n", nickname)
        .update();
    return id;
  }

  String bearer(UUID user) {
    return "Bearer " + tokens.issue(user).value();
  }

  static JsonNode body(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString());
  }
}
