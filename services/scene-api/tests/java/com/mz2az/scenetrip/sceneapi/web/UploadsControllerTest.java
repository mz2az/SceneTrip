package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 사진 올릴 주소 창구(계약 tag {@code uploads}, {@code POST /uploads})를 HTTP 로 본다 — 상태 코드·오류 코드·응답 모양.
 *
 * <p>{@link CurrentAccount} · {@link AccessTokens} 는 진짜다. 저장소({@link PhotoStorage})와 올리기 기록({@link
 * UploadStore})만 가짜이고, 서명 자체는 {@code S3PhotoStorageTest}, 기록의 SQL 은 통합 레인이 본다.
 */
@WebMvcTest(UploadsController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, UploadsControllerTest.Tokens.class})
@DisplayName("UploadsController — 사진 올릴 주소")
class UploadsControllerTest {

  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");
  private static final UUID USER = UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
  private static final long MAX = 10_485_760L;

  @TestConfiguration
  static class Tokens {
    @Bean
    AccessTokens accessTokens() {
      return new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.fixed(T0, ZoneOffset.UTC));
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens tokens;

  @MockitoBean private PhotoStorage storage;
  @MockitoBean private UploadStore uploads;
  @MockitoBean private UserStore users;

  @BeforeEach
  void setUp() {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(storage.available()).thenReturn(true);
    when(storage.presignUpload(anyString(), anyString(), anyLong(), any()))
        .thenAnswer(
            inv ->
                new PhotoStorage.PresignedUpload(
                    URI.create(
                        "https://bucket.example/" + inv.getArgument(0) + "?X-Amz-Signature=s"),
                    Map.of("Content-Type", (String) inv.getArgument(1)),
                    T0.plus((Duration) inv.getArgument(3))));
  }

  @Test
  @DisplayName("토큰이 없으면 401 SIGN_IN_REQUIRED — 주소를 만들지도 적지도 않는다")
  void withoutTokenIsSignInRequired() throws Exception {
    mvc.perform(postJson(body("image/jpeg", 1000), null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verifyNothingIssued();
  }

  @Test
  @DisplayName("201 — key 는 uploads/tmp/<uuid>.<확장자>, 주소·필수 헤더·만료(10 분)를 싣고, 누가 어떤 키를 받았는지 적는다")
  void issuesTicketAndRecordsIt() throws Exception {
    mvc.perform(postJson(body("image/jpeg", 2_000_000), bearer()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.matchesPattern(keyPattern("jpg"))))
        .andExpect(
            jsonPath("$.uploadUrl")
                .value(org.hamcrest.Matchers.startsWith("https://bucket.example/uploads/tmp/")))
        .andExpect(jsonPath("$.requiredHeaders.Content-Type").value("image/jpeg"))
        .andExpect(jsonPath("$.expiresAt").exists());

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(storage)
        .presignUpload(key.capture(), eq("image/jpeg"), eq(2_000_000L), eq(Duration.ofMinutes(10)));
    assertThat(key.getValue()).matches(keyPattern("jpg"));
    verify(uploads).record(USER, key.getValue(), "review", "image/jpeg", 2_000_000L);
  }

  @Test
  @DisplayName("목적 post(여행후기, 1.10.0) — 201 이고 기록에 목적이 post 로 남는다")
  void acceptsPostPurpose() throws Exception {
    mvc.perform(
            postJson(
                "{\"purpose\":\"post\",\"contentType\":\"image/png\",\"bytes\":500}", bearer()))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.key").value(org.hamcrest.Matchers.matchesPattern(keyPattern("png"))));

    verify(uploads).record(eq(USER), anyString(), eq("post"), eq("image/png"), eq(500L));
  }

  @Test
  @DisplayName("형식마다 확장자 — jpeg→jpg · png→png · heic→heic · webp→webp, 매번 새 키")
  void extensionFollowsType() throws Exception {
    Map<String, String> types =
        Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/heic", "heic",
            "image/webp", "webp");
    for (var e : types.entrySet()) {
      mvc.perform(postJson(body(e.getKey(), 10), bearer()))
          .andExpect(status().isCreated())
          .andExpect(
              jsonPath("$.key")
                  .value(org.hamcrest.Matchers.matchesPattern(keyPattern(e.getValue()))))
          .andExpect(jsonPath("$.requiredHeaders.Content-Type").value(e.getKey()));
    }
    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(storage, org.mockito.Mockito.times(4))
        .presignUpload(keys.capture(), anyString(), anyLong(), any());
    assertThat(keys.getAllValues()).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("응답의 expiresAt 은 저장소가 준 만료 시각 그대로(요청 시각 + 10 분)")
  void expiresAtIsTenMinutes() throws Exception {
    mvc.perform(postJson(body("image/png", 10), bearer()))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.expiresAt")
                .value(
                    org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("2026-10-07T00:10:00Z"),
                        org.hamcrest.Matchers.is("2026-10-07T00:10Z"))));
  }

  @Test
  @DisplayName("받지 않는 형식은 400 UPLOAD_TYPE_UNSUPPORTED — gif·대문자·매개변수 붙은 것·빈 문자열·아무 문자열")
  void unsupportedTypeIsRejected() throws Exception {
    for (String type :
        List.of(
            "image/gif",
            "IMAGE/JPEG",
            "image/jpeg; charset=binary",
            "image/jpg",
            "application/pdf",
            "",
            "not a type")) {
      mvc.perform(postJson(body(type, 10), bearer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("UPLOAD_TYPE_UNSUPPORTED"));
    }
    verifyNothingIssued();
  }

  @Test
  @DisplayName("크기가 1 ~ 10,485,760 밖이면 400 UPLOAD_TOO_LARGE")
  void sizeOutOfRangeIsRejected() throws Exception {
    for (long bytes : List.of(0L, -1L, MAX + 1, Long.MAX_VALUE)) {
      mvc.perform(postJson(body("image/jpeg", bytes), bearer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
    }
    verifyNothingIssued();
  }

  @Test
  @DisplayName("크기 경계 — 1 바이트와 정확히 10,485,760 바이트는 받는다")
  void sizeBoundariesAreAccepted() throws Exception {
    mvc.perform(postJson(body("image/webp", 1), bearer())).andExpect(status().isCreated());
    mvc.perform(postJson(body("image/webp", MAX), bearer())).andExpect(status().isCreated());

    verify(storage).presignUpload(anyString(), eq("image/webp"), eq(1L), any());
    verify(storage).presignUpload(anyString(), eq("image/webp"), eq(MAX), any());
  }

  @Test
  @DisplayName("저장소가 없는 서버면 503 UPLOAD_UNAVAILABLE — 형식·크기 오류는 그보다 먼저 400")
  void unavailableStorage() throws Exception {
    when(storage.available()).thenReturn(false);

    mvc.perform(postJson(body("image/jpeg", 10), bearer()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("UPLOAD_UNAVAILABLE"));
    mvc.perform(postJson(body("image/gif", 10), bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_TYPE_UNSUPPORTED"));
    mvc.perform(postJson(body("image/jpeg", MAX + 1), bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));

    verifyNothingIssued();
  }

  @Test
  @DisplayName("토큰 없음이 무엇보다 먼저 — 형식이 틀려도 저장소가 없어도 401")
  void signInComesFirst() throws Exception {
    when(storage.available()).thenReturn(false);

    mvc.perform(postJson(body("image/gif", 0), null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  // ───────────── 도우미 ─────────────

  private void verifyNothingIssued() {
    verify(storage, never()).presignUpload(anyString(), anyString(), anyLong(), any());
    verify(uploads, never()).record(any(), anyString(), anyString(), anyString(), anyLong());
  }

  private static String keyPattern(String ext) {
    return "^uploads/tmp/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\."
        + ext
        + "$";
  }

  private static String body(String contentType, long bytes) {
    return "{\"purpose\":\"review\",\"contentType\":\""
        + contentType
        + "\",\"bytes\":"
        + bytes
        + "}";
  }

  private static MockHttpServletRequestBuilder postJson(String json, String authorization) {
    MockHttpServletRequestBuilder req =
        post("/uploads").contentType(MediaType.APPLICATION_JSON).content(json);
    if (authorization != null) {
      req.header("Authorization", authorization);
    }
    return req;
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }

  private static byte[] filled(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }
}
