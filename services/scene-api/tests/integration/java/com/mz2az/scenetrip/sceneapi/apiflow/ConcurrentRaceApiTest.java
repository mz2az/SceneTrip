package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 같은 순간에 겹친 요청 네 가지 (MZ2AZ-500) — 어느 쪽이 이기든 500 이 없고, 끝난 뒤의 상태가 계약과 맞는다.
 *
 * <ol>
 *   <li>같은 코스의 편집 완료({@code replaceCourse}) 셋이 동시에 — 각각 200 이거나 계약의 4xx, 최종 코스는 성공한 편집 하나와 똑같다.
 *   <li>5일 코스 3일차에서 「5일차로 이동」({@code updateCourseProgress})과 「3일로 줄이기」가 동시에 — 이동이 이기면 줄이기는 {@code
 *       409 COURSE_SHORTER_THAN_PROGRESS}, 줄이기가 이기면 이동은 {@code 400 INVALID_PARAMETER}(errors.md),
 *       끝난 코스의 지금 일차는 기간을 넘지 않는다.
 *   <li>같은 리프레시 토큰으로 로그아웃({@code signOut})과 갱신({@code refreshSession})이 동시에 — 끝난 뒤 그 사슬의 토큰은 하나도 쓸
 *       수 없다. 갱신이 200 이었으면 그 새 토큰으로 다시 갱신하면 {@code 401} 이다(차례로 하면 지금도 그렇다).
 *   <li>탈퇴({@code deleteMe})와 같은 사람의 리뷰 쓰기({@code putMyPlaceReview})가 동시에 — 500 이 없고, 탈퇴가 204 면 그
 *       사람의 리뷰는 남지 않는다(계약: 탈퇴는 작성한 리뷰도 지운다). 리뷰 쪽은 200(탈퇴 전에 끝남) 또는 401.
 *   <li>탈퇴와 같은 사람의 여행후기 쓰기({@code createPost}, 사진 없음)가 동시에 — 500 이 없고, 탈퇴가 204 면 그 사람의 글은 남지
 *       않는다(계약: 탈퇴는 작성한 여행후기도 지운다). 글 쪽은 201(탈퇴 전에 끝남) 또는 401.
 * </ol>
 *
 * <p>정말로 여러 스레드에서 같은 순간에 요청한다({@link PhotoDoubleSubmitApiTest} 와 같은 방식). 요청마다 자기 트랜잭션을 커밋하므로 바탕의 시험
 * 트랜잭션 되돌림을 끄고({@code NOT_SUPPORTED}), 만든 행은 {@link #cleanUp()} 이 지운다. 경합은 매번 나지 않으므로 회차를 여러 번 돌리고
 * 결과를 세어 한꺼번에 본다.
 */
@DisplayName("API 통합 — 동시에 겹친 요청 (MZ2AZ-500)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentRaceApiTest extends ApiIntegrationTest {

  private static final int ROUNDS = 15;

  /** 편집 완료 경합에 겹치는 요청 수. */
  private static final int EDITORS = 3;

  /** 편집 완료 경합의 응답으로 계약이 정한 상태 — 200 과 {@code replaceCourse} 의 4xx. */
  private static final Set<Integer> REPLACE_STATUSES = Set.of(200, 400, 401, 404, 409);

  @Autowired RefreshTokenStore refreshTokens;

  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<UUID> installs = new ArrayList<>();
  private final List<Long> createdPlaces = new ArrayList<>();
  private final List<String> postTitles = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    // 설치 UUID 로 생긴 비회원(코스 주인, 로그아웃 뒤 새로 짝지어진 비회원)도 지운다 — 코스·토큰은 계정을 따라 CASCADE 로 지워진다.
    List<UUID> owners = new ArrayList<>(createdUsers);
    for (UUID install : installs) {
      owners.addAll(
          jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = :i")
              .param("i", install)
              .query(UUID.class)
              .list());
    }
    for (long place : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :p").param("p", place).update();
    }
    // 탈퇴로 글쓴이가 끊긴 글도 남지 않게 — 회차마다 붙인 제목으로 지운다.
    for (String title : postTitles) {
      jdbc.sql("DELETE FROM community_post WHERE title = :t").param("t", title).update();
    }
    for (UUID user : owners) {
      jdbc.sql("DELETE FROM community_post WHERE user_id = :u").param("u", user).update();
    }
    for (UUID user : owners) {
      jdbc.sql("DELETE FROM app_user WHERE id = :u").param("u", user).update();
    }
  }

  // ── 1. 편집 완료 셋이 동시에 ──────────────────────────────────────────

  @Test
  @DisplayName("같은 빈 코스에 편집 완료 셋이 동시에 — 500 이 없고, 최종 코스는 200 받은 편집 하나와 똑같다")
  void concurrentFullEditsNeverFail() throws Exception {
    List<Long> places = new ArrayList<>();
    for (int i = 0; i < EDITORS; i++) {
      long p = place("TEST-동시편집-" + tag + "-" + i, SEA_LNG + i * 0.001, SEA_LAT);
      createdPlaces.add(p);
      places.add(p);
    }

    Map<String, Integer> outcomes = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    for (int round = 1; round <= ROUNDS; round++) {
      String install = newInstall();
      long courseId = createCourse(install, 2);

      // 편집 k: 제목이 다르고, 같은 장소 셋을 서로 다른 자리에 담는다.
      List<Edit> edits = new ArrayList<>();
      for (int k = 0; k < EDITORS; k++) {
        edits.add(
            new Edit(
                "TEST-편집" + k + "-" + tag,
                List.of(
                    List.of(places.get(k), places.get((k + 1) % EDITORS)),
                    List.of(places.get((k + 2) % EDITORS)))));
      }
      List<Supplier<RequestBuilder>> requests = new ArrayList<>();
      for (Edit edit : edits) {
        String json = edit.json();
        requests.add(
            () ->
                put("/courses/{id}", courseId)
                    .header("X-Install-Id", install)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));
      }
      List<MvcResult> results = concurrently(requests);
      count(outcomes, statuses(results).stream().sorted().toList().toString());

      List<String> brief = new ArrayList<>();
      for (MvcResult r : results) {
        int st = r.getResponse().getStatus();
        brief.add(st == 200 ? "200" : st + " " + code(r));
      }
      String label = "회차 " + round + " 응답 " + brief;
      List<Edit> succeeded = new ArrayList<>();
      for (int k = 0; k < EDITORS; k++) {
        int st = results.get(k).getResponse().getStatus();
        if (st == 200) {
          succeeded.add(edits.get(k));
        } else if (!REPLACE_STATUSES.contains(st) || code(results.get(k)).isBlank()) {
          failures.add(label + " — 편집" + k + " 이 계약에 없는 응답 " + st);
        }
      }
      if (succeeded.isEmpty()) {
        failures.add(label + " — 성공한 편집이 하나도 없다(최종 코스가 보낸 편집 어느 것과도 같을 수 없다)");
        continue;
      }
      Edit stored = readCourse(install, courseId);
      if (!succeeded.contains(stored)) {
        failures.add(label + " — 최종 코스 " + stored + " 가 200 받은 편집 " + succeeded + " 어느 것과도 다르다");
      }
    }
    assertThat(failures).as("동시 편집 %d 회차, 응답 분포(정렬한 상태 묶음 → 회차 수) %s", ROUNDS, outcomes).isEmpty();
  }

  // ── 2. 줄이기와 일차 이동이 동시에 ────────────────────────────────────

  @Test
  @DisplayName(
      "5일 코스 3일차: 5일차 이동과 3일로 줄이기가 동시에 — 이동이 이기면 409 COURSE_SHORTER_THAN_PROGRESS, 줄이기가 이기면"
          + " 400 INVALID_PARAMETER, 지금 일차는 기간을 넘지 않는다")
  void shrinkRacingDayMove() throws Exception {
    Map<String, Integer> outcomes = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    for (int round = 1; round <= ROUNDS; round++) {
      String install = newInstall();
      long courseId = createCourse(install, 5);
      mvc.perform(progress(install, courseId, 3)).andExpect(status().isOk());

      String shrink =
          JSON.writeValueAsString(
              Map.of(
                  "title",
                  "TEST-줄이기-" + tag,
                  "days",
                  List.of(
                      Map.of("items", List.of()),
                      Map.of("items", List.of()),
                      Map.of("items", List.of()))));
      List<MvcResult> results =
          concurrently(
              List.of(
                  () -> progress(install, courseId, 5),
                  () ->
                      put("/courses/{id}", courseId)
                          .header("X-Install-Id", install)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(shrink)));
      MvcResult move = results.get(0);
      MvcResult cut = results.get(1);
      int moveStatus = move.getResponse().getStatus();
      int cutStatus = cut.getResponse().getStatus();
      String outcome =
          "이동 " + moveStatus + " " + code(move) + " / 줄이기 " + cutStatus + " " + code(cut);
      count(outcomes, outcome);

      JsonNode course =
          body(
              mvc.perform(get("/courses/{id}", courseId).header("X-Install-Id", install))
                  .andExpect(status().isOk())
                  .andReturn());
      int dayCount = course.path("dayCount").asInt();
      int currentDayNo = course.path("currentDayNo").asInt();
      String label =
          "회차 "
              + round
              + " — "
              + outcome
              + ", 최종 dayCount="
              + dayCount
              + " currentDayNo="
              + currentDayNo;

      boolean moveWon =
          moveStatus == 200
              && cutStatus == 409
              && "COURSE_SHORTER_THAN_PROGRESS".equals(code(cut))
              && dayCount == 5
              && currentDayNo == 5;
      boolean shrinkWon =
          cutStatus == 200
              && moveStatus == 400
              && "INVALID_PARAMETER".equals(code(move))
              && dayCount == 3
              && currentDayNo == 3;
      if (!moveWon && !shrinkWon) {
        failures.add(label);
      }
      if (currentDayNo > dayCount) {
        failures.add(label + " — 지금 일차가 기간을 넘었다");
      }
    }
    assertThat(failures).as("줄이기 × 일차 이동 %d 회차, 결과 분포 %s", ROUNDS, outcomes).isEmpty();
  }

  // ── 3. 로그아웃과 갱신이 동시에 ───────────────────────────────────────

  @Test
  @DisplayName("같은 리프레시 토큰으로 로그아웃과 갱신이 동시에 — 갱신이 준 새 토큰도 쓸 수 없다(401)")
  void signOutRacingRefreshLeavesNoLiveToken() throws Exception {
    Map<String, Integer> outcomes = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    for (int round = 1; round <= ROUNDS; round++) {
      UUID me = memberCommitted("TEST_so" + round + tag.substring(0, 5));
      String install = newInstall();
      String token = refreshTokens.issue(me, UUID.fromString(install)).value();
      String tokenJson = JSON.writeValueAsString(Map.of("refreshToken", token));

      List<MvcResult> results =
          concurrently(
              List.of(
                  () ->
                      post("/auth/sign-out")
                          .header("X-Install-Id", install)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(tokenJson),
                  () -> refresh(install, tokenJson)));
      MvcResult signOut = results.get(0);
      MvcResult refreshed = results.get(1);
      int signOutStatus = signOut.getResponse().getStatus();
      int refreshStatus = refreshed.getResponse().getStatus();
      String label = "회차 " + round + " — 로그아웃 " + signOutStatus + " / 갱신 " + refreshStatus;

      if (signOutStatus != 204) {
        failures.add(label + " — 로그아웃은 언제나 204 여야 한다: " + describe(List.of(signOut)));
      }
      if (refreshStatus == 200) {
        String newToken = body(refreshed).path("refreshToken").asString();
        int again =
            mvc.perform(refresh(install, JSON.writeValueAsString(Map.of("refreshToken", newToken))))
                .andReturn()
                .getResponse()
                .getStatus();
        count(outcomes, "갱신 200 → 새 토큰으로 다시 갱신 " + again);
        if (again != 401) {
          failures.add(label + " — 로그아웃 뒤에도 갱신이 준 새 토큰이 살아 있다(다시 갱신 " + again + ")");
        }
      } else if (refreshStatus == 401) {
        count(outcomes, "갱신 401");
      } else {
        count(outcomes, "갱신 " + refreshStatus);
        failures.add(label + " — 갱신은 200 또는 401 이어야 한다: " + describe(List.of(refreshed)));
      }
    }
    assertThat(failures).as("로그아웃 × 갱신 %d 회차, 결과 분포 %s", ROUNDS, outcomes).isEmpty();
  }

  // ── 4. 탈퇴와 리뷰 쓰기가 동시에 ──────────────────────────────────────

  @Test
  @DisplayName("탈퇴와 같은 사람의 리뷰 쓰기가 동시에 — 500 이 없고, 탈퇴 204 뒤 그 사람의 리뷰가 남지 않는다")
  void deleteMeRacingReviewWrite() throws Exception {
    Map<String, Integer> outcomes = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    String reviewJson =
        JSON.writeValueAsString(Map.of("rating", 4, "body", "TEST 탈퇴경합", "photoKeys", List.of()));
    for (int round = 1; round <= ROUNDS; round++) {
      UUID me = memberCommitted("TEST_dm" + round + tag.substring(0, 5));
      long p = place("TEST-탈퇴리뷰-" + tag + "-" + round, SEA_LNG, SEA_LAT);
      createdPlaces.add(p);
      String auth = bearer(me);

      List<MvcResult> results =
          concurrently(
              List.of(
                  () -> delete("/me").header("Authorization", auth),
                  () ->
                      put("/places/{id}/reviews/me", p)
                          .header("Authorization", auth)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(reviewJson)));
      MvcResult deletion = results.get(0);
      MvcResult review = results.get(1);
      int deleteStatus = deletion.getResponse().getStatus();
      int reviewStatus = review.getResponse().getStatus();
      long remaining =
          jdbc.sql("SELECT count(*) FROM review WHERE place_id = :p")
              .param("p", p)
              .query(Long.class)
              .single();
      long orphaned =
          jdbc.sql("SELECT count(*) FROM review WHERE place_id = :p AND user_id IS NULL")
              .param("p", p)
              .query(Long.class)
              .single();
      String outcome =
          "탈퇴 "
              + deleteStatus
              + " / 리뷰 "
              + reviewStatus
              + (reviewStatus >= 400 ? " " + code(review) : "")
              + " / 남은 리뷰 "
              + remaining
              + (orphaned > 0 ? "(작성자 없음 " + orphaned + ")" : "");
      count(outcomes, outcome);
      String label = "회차 " + round + " — " + outcome;

      if (deleteStatus != 204) {
        failures.add(label + " — 탈퇴는 204 여야 한다: " + describe(List.of(deletion)));
      }
      if (reviewStatus != 200 && reviewStatus != 401) {
        failures.add(label + " — 리뷰 쓰기는 200 또는 401 이어야 한다: " + describe(List.of(review)));
      }
      if (deleteStatus == 204 && remaining != 0) {
        failures.add(label + " — 탈퇴했는데 그 사람의 리뷰가 남았다");
      }
    }
    assertThat(failures).as("탈퇴 × 리뷰 쓰기 %d 회차, 결과 분포 %s", ROUNDS, outcomes).isEmpty();
  }

  // ── 5. 탈퇴와 여행후기 쓰기가 동시에 ──────────────────────────────────

  @Test
  @DisplayName("탈퇴와 같은 사람의 여행후기 쓰기가 동시에 — 500 이 없고, 탈퇴 204 뒤 그 사람의 글이 남지 않는다")
  void deleteMeRacingPostWrite() throws Exception {
    Map<String, Integer> outcomes = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    for (int round = 1; round <= ROUNDS; round++) {
      UUID me = memberCommitted("TEST_dp" + round + tag.substring(0, 5));
      String title = "TEST-탈퇴글-" + tag + "-" + round;
      postTitles.add(title);
      String postJson =
          JSON.writeValueAsString(
              Map.of("title", title, "body", "TEST 탈퇴경합", "photoKeys", List.of()));
      String auth = bearer(me);

      List<MvcResult> results =
          concurrently(
              List.of(
                  () -> delete("/me").header("Authorization", auth),
                  () ->
                      post("/posts")
                          .header("Authorization", auth)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(postJson)));
      MvcResult deletion = results.get(0);
      MvcResult written = results.get(1);
      int deleteStatus = deletion.getResponse().getStatus();
      int postStatus = written.getResponse().getStatus();
      // 제목이 회차마다 달라 이 사람의 글만 센다 — 탈퇴로 글쓴이가 끊긴(user_id NULL) 글도 포함한다.
      long remaining =
          jdbc.sql("SELECT count(*) FROM community_post WHERE title = :t")
              .param("t", title)
              .query(Long.class)
              .single();
      long orphaned =
          jdbc.sql("SELECT count(*) FROM community_post WHERE title = :t AND user_id IS NULL")
              .param("t", title)
              .query(Long.class)
              .single();
      String outcome =
          "탈퇴 "
              + deleteStatus
              + " / 글 "
              + postStatus
              + (postStatus >= 400 ? " " + code(written) : "")
              + " / 남은 글 "
              + remaining
              + (orphaned > 0 ? "(글쓴이 없음 " + orphaned + ")" : "");
      count(outcomes, outcome);
      String label = "회차 " + round + " — " + outcome;

      if (deleteStatus != 204) {
        failures.add(label + " — 탈퇴는 204 여야 한다: " + describe(List.of(deletion)));
      }
      if (postStatus != 201 && postStatus != 401) {
        failures.add(label + " — 글쓰기는 201 또는 401 이어야 한다: " + describe(List.of(written)));
      }
      if (deleteStatus == 204 && remaining != 0) {
        failures.add(label + " — 탈퇴했는데 그 사람의 글이 남았다");
      }
    }
    assertThat(failures).as("탈퇴 × 여행후기 쓰기 %d 회차, 결과 분포 %s", ROUNDS, outcomes).isEmpty();
  }

  // ── 도우미 ────────────────────────────────────────────────────────────

  /** 편집 하나 — 제목과 일차별 촬영지 번호(방문 순서대로). 최종 코스와 비교할 때도 같은 모양으로 읽는다. */
  private record Edit(String title, List<List<Long>> days) {
    String json() {
      List<Map<String, Object>> dayInputs = new ArrayList<>();
      for (List<Long> day : days) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (long placeId : day) {
          items.add(Map.of("placeId", placeId));
        }
        dayInputs.add(Map.of("items", items));
      }
      return JSON.writeValueAsString(Map.of("title", title, "days", dayInputs));
    }
  }

  private Edit readCourse(String install, long courseId) throws Exception {
    JsonNode course =
        body(
            mvc.perform(get("/courses/{id}", courseId).header("X-Install-Id", install))
                .andExpect(status().isOk())
                .andReturn());
    List<List<Long>> days = new ArrayList<>();
    for (JsonNode day : course.path("days")) {
      List<Long> items = new ArrayList<>();
      for (JsonNode item : day.path("items")) {
        items.add(item.path("placeId").asLong());
      }
      days.add(items);
    }
    return new Edit(course.path("title").asString(), days);
  }

  private String newInstall() {
    UUID install = UUID.randomUUID();
    installs.add(install);
    return install.toString();
  }

  private UUID memberCommitted(String nickname) {
    UUID id = member(nickname);
    createdUsers.add(id);
    return id;
  }

  private long createCourse(String install, int dayCount) throws Exception {
    return body(mvc.perform(
                post("/courses")
                    .header("X-Install-Id", install)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "dayCount",
                                dayCount,
                                "origin",
                                "self",
                                "title",
                                "TEST-경합-" + tag))))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asLong();
  }

  private static RequestBuilder progress(String install, long courseId, int dayNo) {
    return put("/courses/{id}/progress", courseId)
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("status", "active", "currentDayNo", dayNo)));
  }

  /** 갱신 — 앱은 모든 요청에 설치 UUID 를 싣는다. 요청 한도를 접속 주소 한 칸이 아니라 설치본마다 세게 하려고 함께 보낸다. */
  private static RequestBuilder refresh(String install, String tokenJson) {
    return post("/auth/refresh")
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(tokenJson);
  }

  /** 요청들을 각자의 스레드에서 같은 순간에 보낸다. 결과는 보낸 순서 그대로. */
  private List<MvcResult> concurrently(List<Supplier<RequestBuilder>> requests) throws Exception {
    int n = requests.size();
    CyclicBarrier start = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (Supplier<RequestBuilder> request : requests) {
        futures.add(
            pool.submit(
                () -> {
                  RequestBuilder built = request.get();
                  start.await(10, TimeUnit.SECONDS);
                  return mvc.perform(built).andReturn();
                }));
      }
      List<MvcResult> results = new ArrayList<>();
      for (Future<MvcResult> f : futures) {
        results.add(f.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void count(Map<String, Integer> outcomes, String key) {
    outcomes.merge(key, 1, Integer::sum);
  }

  private static String code(MvcResult r) {
    try {
      return body(r).path("code").asString();
    } catch (Exception e) {
      return "";
    }
  }

  private static List<Integer> statuses(List<MvcResult> results) {
    return results.stream().map(r -> r.getResponse().getStatus()).toList();
  }

  private static String describe(List<MvcResult> results) throws Exception {
    List<String> parts = new ArrayList<>();
    for (MvcResult r : results) {
      String text = r.getResponse().getContentAsString();
      parts.add(
          r.getResponse().getStatus()
              + " "
              + (text.length() > 160 ? text.substring(0, 160) + "…" : text));
    }
    return parts.toString();
  }
}
