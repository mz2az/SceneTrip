package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.SocialIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 정한 여행 {@code /me/trip} — 요청부터 실제 DB 까지(계약 1.25.0, 계획 {@code trip-memory.md} §2·§3·§4).
 *
 * <p>명세에서 나온 기대: 넣으면 읽히고, 다시 넣으면 통째로 바뀌고(계정 하나에 여행 하나), 지우기는 없어도 204, 남의 코스·없는 코스는 400 {@code
 * TRIP_COURSE_INVALID}, 코스를 지우면 여행은 남고 {@code courseId} 만 빠진다. 비회원이 가입 계정으로 합쳐질 때 가입 계정에 여행이 없으면 옮겨
 * 오고 있으면 가입 계정 것이 남는다. 탈퇴하면 여행도 사라진다.
 */
@DisplayName("API 통합 — 정한 여행 /me/trip")
class TripApiTest extends ApiIntegrationTest {

  @Autowired SignInService signIn;

  private final String install = UUID.randomUUID().toString();

  // ── 넣기·읽기·지우기 ───────────────────────────────────────────────────

  @Test
  @DisplayName("처음엔 404 TRIP_NOT_FOUND → PUT 200 → GET 에 같은 값")
  void putThenGet() throws Exception {
    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));

    mvc.perform(putTrip(guest(install), "  부산  ", 2, "relaxed", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("relaxed"))
        .andExpect(jsonPath("$.courseId").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").exists());

    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("relaxed"))
        .andExpect(jsonPath("$.courseId").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").exists());
  }

  @Test
  @DisplayName("다시 PUT 하면 통째로 바뀐다 — 여행은 하나뿐")
  void putOverwrites() throws Exception {
    long course = createCourse(guest(install));
    mvc.perform(putTrip(guest(install), "부산", 2, "relaxed", course)).andExpect(status().isOk());

    mvc.perform(putTrip(guest(install), "제주", 5, "packed", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("제주"))
        .andExpect(jsonPath("$.days").value(5))
        .andExpect(jsonPath("$.pace").value("packed"))
        .andExpect(jsonPath("$.courseId").doesNotExist());

    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("제주"))
        .andExpect(jsonPath("$.days").value(5))
        .andExpect(jsonPath("$.pace").value("packed"))
        .andExpect(jsonPath("$.courseId").doesNotExist());
  }

  @Test
  @DisplayName("지우기는 204 → GET 404 → 또 지워도 204, 처음 보는 설치본이 지워도 204")
  void deleteIsIdempotent() throws Exception {
    mvc.perform(putTrip(guest(install), "부산", 2, "normal", null)).andExpect(status().isOk());

    mvc.perform(delete("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNoContent());
    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    mvc.perform(delete("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNoContent());

    mvc.perform(delete("/me/trip").header("X-Install-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("다른 설치본에는 내 여행이 보이지 않는다")
  void tripIsPerAccount() throws Exception {
    mvc.perform(putTrip(guest(install), "부산", 2, "normal", null)).andExpect(status().isOk());

    mvc.perform(get("/me/trip").header("X-Install-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
  }

  @Test
  @DisplayName("토큰과 설치 UUID 가 함께 오면 토큰의 계정 것이다")
  void tokenWinsOverInstallId() throws Exception {
    UUID me = member("TEST_tr" + tag.substring(0, 6));
    String otherInstall = UUID.randomUUID().toString();

    mvc.perform(putTrip(asMember(me, otherInstall), "강릉", 3, "normal", null))
        .andExpect(status().isOk());

    // 설치 UUID 만으로는(새 비회원) 보이지 않고, 토큰으로는 보인다.
    mvc.perform(get("/me/trip").header("X-Install-Id", otherInstall))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/me/trip")
                .header("X-Install-Id", UUID.randomUUID().toString())
                .header("Authorization", bearer(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("강릉"));
  }

  // ── 코스 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("내 코스는 courseId 로 붙는다")
  void ownCourseAccepted() throws Exception {
    long course = createCourse(guest(install));

    mvc.perform(putTrip(guest(install), "부산", 2, "normal", course))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.courseId").value(course));
    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.courseId").value(course));
  }

  @Test
  @DisplayName("남의 코스는 400 TRIP_COURSE_INVALID — 여행은 생기지 않는다")
  void foreignCourseRejected() throws Exception {
    String stranger = UUID.randomUUID().toString();
    long theirs = createCourse(guest(stranger));

    mvc.perform(putTrip(guest(install), "부산", 2, "normal", theirs))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TRIP_COURSE_INVALID"));

    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
  }

  @Test
  @DisplayName("없는 코스도 400 TRIP_COURSE_INVALID")
  void missingCourseRejected() throws Exception {
    mvc.perform(putTrip(guest(install), "부산", 2, "normal", Long.MAX_VALUE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TRIP_COURSE_INVALID"));
  }

  @Test
  @DisplayName("코스를 지우면 여행은 남고 courseId 만 빠진다")
  void deletingCourseClearsCourseId() throws Exception {
    long course = createCourse(guest(install));
    mvc.perform(putTrip(guest(install), "부산", 2, "normal", course)).andExpect(status().isOk());

    mvc.perform(delete("/courses/{id}", course).header("X-Install-Id", install))
        .andExpect(status().isNoContent());

    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("normal"))
        .andExpect(jsonPath("$.courseId").doesNotExist());
  }

  // ── 비회원 → 가입 계정 합치기 ─────────────────────────────────────────

  @Test
  @DisplayName("합치기: 가입 계정에 여행이 있으면 가입 계정 것이 남는다")
  void mergeKeepsMembersTrip() throws Exception {
    SocialIdentity identity = identity();
    UUID member = signIn.signIn(identity, UUID.randomUUID()).userId();
    mvc.perform(putTrip(asMember(member, UUID.randomUUID().toString()), "서울", 4, "packed", null))
        .andExpect(status().isOk());

    UUID guestInstall = UUID.randomUUID();
    mvc.perform(putTrip(guest(guestInstall.toString()), "부산", 2, "relaxed", null))
        .andExpect(status().isOk());

    SignInService.SignedIn merged = signIn.signIn(identity, guestInstall);
    assertThat(merged.userId()).isEqualTo(member);
    assertThat(merged.merged()).isTrue();

    mvc.perform(
            get("/me/trip")
                .header("X-Install-Id", guestInstall.toString())
                .header("Authorization", bearer(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("서울"))
        .andExpect(jsonPath("$.days").value(4))
        .andExpect(jsonPath("$.pace").value("packed"));
  }

  @Test
  @DisplayName("합치기: 가입 계정에 여행이 없으면 비회원의 여행(코스 포함)이 옮겨 온다")
  void mergeMovesGuestsTripWhenMemberHasNone() throws Exception {
    SocialIdentity identity = identity();
    UUID member = signIn.signIn(identity, UUID.randomUUID()).userId();

    UUID guestInstall = UUID.randomUUID();
    long course = createCourse(guest(guestInstall.toString()));
    mvc.perform(putTrip(guest(guestInstall.toString()), "부산", 2, "relaxed", course))
        .andExpect(status().isOk());

    SignInService.SignedIn merged = signIn.signIn(identity, guestInstall);
    assertThat(merged.userId()).isEqualTo(member);
    assertThat(merged.merged()).isTrue();

    // 코스도 합치기로 가입 계정에 옮겨 가므로 courseId 는 그대로 이어진다.
    mvc.perform(
            get("/me/trip")
                .header("X-Install-Id", guestInstall.toString())
                .header("Authorization", bearer(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("부산"))
        .andExpect(jsonPath("$.days").value(2))
        .andExpect(jsonPath("$.pace").value("relaxed"))
        .andExpect(jsonPath("$.courseId").value(course));
  }

  // ── 탈퇴 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("회원 탈퇴(DELETE /me)하면 그 계정의 여행 행이 사라진다")
  void memberDeletionRemovesTrip() throws Exception {
    UUID me = member("TEST_dl" + tag.substring(0, 6));
    mvc.perform(putTrip(asMember(me, UUID.randomUUID().toString()), "부산", 2, "normal", null))
        .andExpect(status().isOk());
    assertThat(tripRows(me)).isEqualTo(1);

    mvc.perform(delete("/me").header("Authorization", bearer(me)))
        .andExpect(status().isNoContent());

    assertThat(tripRows(me)).isZero();
  }

  @Test
  @DisplayName("비회원이 기록을 지우면(DELETE /me) 여행도 지워진다 — 같은 설치본은 새 비회원이라 404")
  void guestDeletionRemovesTrip() throws Exception {
    mvc.perform(putTrip(guest(install), "부산", 2, "normal", null)).andExpect(status().isOk());
    UUID guestAccount = deviceOwner(UUID.fromString(install));
    assertThat(tripRows(guestAccount)).isEqualTo(1);

    mvc.perform(delete("/me").header("X-Install-Id", install)).andExpect(status().isNoContent());

    assertThat(tripRows(guestAccount)).isZero();
    mvc.perform(get("/me/trip").header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────

  private interface Caller {
    MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request);
  }

  private static Caller guest(String installId) {
    return request -> request.header("X-Install-Id", installId);
  }

  private Caller asMember(UUID user, String installId) {
    String token = bearer(user);
    return request -> request.header("X-Install-Id", installId).header("Authorization", token);
  }

  private MockHttpServletRequestBuilder putTrip(
      Caller caller, String city, int days, String pace, Long courseId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("city", city);
    body.put("days", days);
    body.put("pace", pace);
    if (courseId != null) {
      body.put("courseId", courseId);
    }
    return caller.apply(
        put("/me/trip")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private long createCourse(Caller caller) throws Exception {
    return body(mvc.perform(
                caller.apply(
                    post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "dayCount",
                                    2,
                                    "origin",
                                    "self",
                                    "title",
                                    "TEST-여행코스-" + tag)))))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asLong();
  }

  private SocialIdentity identity() {
    return new SocialIdentity(
        "google", "TEST-trip-" + tag + "-" + UUID.randomUUID(), "trip@example.com", "여행자");
  }

  private UUID deviceOwner(UUID installUuid) {
    return jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = :i")
        .param("i", installUuid)
        .query(UUID.class)
        .single();
  }

  private long tripRows(UUID user) {
    return jdbc.sql("SELECT count(*) FROM user_trip WHERE user_id = :u")
        .param("u", user)
        .query(Long.class)
        .single();
  }
}
