package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 같은 저장 요청이 동시에 두 번 와도 이긴 글의 사진은 남는다 (MZ2AZ-498, 계약 {@code createPost}·{@code putMyPlaceReview},
 * errors.md {@code POST_PHOTO_INVALID}·{@code REVIEW_PHOTO_INVALID}).
 *
 * <p>정말로 두 스레드에서 같은 순간에 요청한다. 요청마다 자기 트랜잭션을 커밋하므로 바탕의 시험 트랜잭션 되돌림을 끄고({@code NOT_SUPPORTED}), 만든 행은
 * {@link #cleanUp()} 이 지운다.
 *
 * <p>사진 저장소는 메모리 속 가짜다 — S3 처럼 옮기기는 「복사 → 원본 삭제」 두 단계이고 그 사이가 원자적이지 않다. 실제 저장소의 지연을 흉내 내 그 사이를 잠깐
 * 멈춰, 두 요청이 모두 같은 원본을 복사하는 경우가 매번 생기게 한다.
 */
@DisplayName("API 통합 — 사진 두 번 동시 저장 (MZ2AZ-498)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PhotoDoubleSubmitApiTest extends ApiIntegrationTest {

  private static final int ROUNDS = 6;

  /** 글 하나에 붙이는 사진 수 — 앱은 여러 장을 한꺼번에 붙인다. */
  private static final int PHOTOS = 2;

  /**
   * 저장소 지연 흉내 — 복사와 원본 삭제 사이. 홀수 회차(동시)는 두 요청이 같은 만큼 멈춰 사진을 모두 옮기고 DB 저장도 거의 동시에 한다. 짝수 회차(엇갈림)는 첫
   * 사진을 둘 다 복사한 뒤 먼저 들어온 쪽이 짧게 멈춰 둘째 사진까지 먼저 옮기고, 늦은 쪽은 둘째 사진의 원본이 이미 없는 것을 본다.
   */
  private static final long EQUAL_PAUSE_MS = 300;

  private static final long FIRST_PAUSE_MS = 100;
  private static final long SECOND_PAUSE_MS = 800;

  /** 이번 회차가 엇갈린 지연인가(짝수 회차). */
  private volatile boolean staggered;

  /** 임시 원본마다 옮기기에 들어온 순서. */
  private final Map<String, AtomicInteger> moveEntries = new ConcurrentHashMap<>();

  /** 가짜 저장소에 있는 파일들(키 → 내용). */
  private final Map<String, String> objects = new ConcurrentHashMap<>();

  /** 시험 중 한 번이라도 나타난 키 — 정리 때 storage_deletion 을 지우는 데 쓴다. */
  private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();

  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<Long> createdPlaces = new ArrayList<>();

  @BeforeEach
  void inMemoryStorage() {
    doAnswer(
            inv -> {
              String from = inv.getArgument(0, String.class);
              String to = inv.getArgument(1, String.class);
              seenKeys.add(from);
              seenKeys.add(to);
              String data = objects.get(from);
              if (data == null) {
                throw new PhotoStorage.MissingPhotoException(from);
              }
              objects.put(to, data); // 복사
              if (from.startsWith("uploads/tmp/")) {
                int order =
                    moveEntries.computeIfAbsent(from, k -> new AtomicInteger()).incrementAndGet();
                long pause =
                    !staggered ? EQUAL_PAUSE_MS : order == 1 ? FIRST_PAUSE_MS : SECOND_PAUSE_MS;
                Thread.sleep(pause); // 실제 저장소의 왕복 시간
              }
              objects.remove(from); // 원본 삭제
              return null;
            })
        .when(photos)
        .move(anyString(), anyString());
    doAnswer(
            inv -> {
              String key = inv.getArgument(0, String.class);
              seenKeys.add(key);
              objects.remove(key); // 없는 키여도 성공(계약)
              return null;
            })
        .when(photos)
        .delete(anyString());
  }

  @AfterEach
  void cleanUp() {
    for (UUID user : createdUsers) {
      jdbc.sql("DELETE FROM community_post WHERE user_id = :u").param("u", user).update();
    }
    for (long place : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :p").param("p", place).update();
    }
    for (String key : seenKeys) {
      jdbc.sql("DELETE FROM storage_deletion WHERE storage_key = :k").param("k", key).update();
    }
    for (UUID user : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = :u").param("u", user).update();
    }
  }

  @Test
  @DisplayName("여행후기: 같은 글을 동시에 두 번 — 하나는 201, 하나는 400 POST_PHOTO_INVALID, 201 글의 사진은 저장소에 있다")
  void doublePostKeepsWinnersPhotos() throws Exception {
    SoftAssertions soft = new SoftAssertions();
    for (int round = 1; round <= ROUNDS; round++) {
      staggered = round % 2 == 0;
      UUID me = memberCommitted("TEST_dp" + round + tag.substring(0, 5));
      List<String> uploadKeys = uploadAll(me, "post");
      String json =
          JSON.writeValueAsString(
              Map.of(
                  "title",
                  "TEST-동시-" + tag + "-" + round,
                  "body",
                  "TEST 본문",
                  "photoKeys",
                  uploadKeys));

      List<MvcResult> results =
          concurrently(
              () ->
                  post("/posts")
                      .header("Authorization", bearer(me))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(json));

      String label = "회차 " + round + (staggered ? "(엇갈림)" : "(동시)") + " 응답 " + describe(results);
      for (MvcResult r : results) {
        if (r.getResponse().getStatus() != 201) {
          continue;
        }
        long postId = body(r).path("id").asLong();
        List<String> keys =
            jdbc.sql("SELECT storage_key FROM community_post_photo WHERE post_id = :p")
                .param("p", postId)
                .query(String.class)
                .list();
        assertWinnerPhotosAlive(soft, label + " — 201 글 " + postId, keys);
      }
      soft.assertThat(statuses(results)).as(label).containsExactlyInAnyOrder(201, 400);
      results.stream()
          .filter(r -> r.getResponse().getStatus() == 400)
          .forEach(
              r ->
                  soft.assertThat(code(r))
                      .as(label + " — 진 쪽 code")
                      .isEqualTo("POST_PHOTO_INVALID"));
    }
    soft.assertAll();
  }

  @Test
  @DisplayName("장소 리뷰: 같은 리뷰를 동시에 두 번 — 하나는 200, 하나는 400 REVIEW_PHOTO_INVALID, 200 리뷰의 사진은 저장소에 있다")
  void doubleReviewKeepsWinnersPhotos() throws Exception {
    SoftAssertions soft = new SoftAssertions();
    for (int round = 1; round <= ROUNDS; round++) {
      staggered = round % 2 == 0;
      UUID me = memberCommitted("TEST_dr" + round + tag.substring(0, 5));
      long p = place("TEST-동시리뷰-" + tag + "-" + round, SEA_LNG, SEA_LAT);
      createdPlaces.add(p);
      List<String> uploadKeys = uploadAll(me, "review");
      String json =
          JSON.writeValueAsString(Map.of("rating", 4, "body", "TEST 동시", "photoKeys", uploadKeys));

      List<MvcResult> results =
          concurrently(
              () ->
                  put("/places/{id}/reviews/me", p)
                      .header("Authorization", bearer(me))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(json));

      String label = "회차 " + round + (staggered ? "(엇갈림)" : "(동시)") + " 응답 " + describe(results);
      for (MvcResult r : results) {
        if (r.getResponse().getStatus() != 200) {
          continue;
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode photo : body(r).path("photos")) {
          keys.add(photo.path("key").asString());
        }
        assertWinnerPhotosAlive(soft, label + " — 200 리뷰", keys);
      }
      soft.assertThat(statuses(results)).as(label).containsExactlyInAnyOrder(200, 400);
      results.stream()
          .filter(r -> r.getResponse().getStatus() == 400)
          .forEach(
              r ->
                  soft.assertThat(code(r))
                      .as(label + " — 진 쪽 code")
                      .isEqualTo("REVIEW_PHOTO_INVALID"));
    }
    soft.assertAll();
  }

  // ── 도우미 ────────────────────────────────────────────────────────────

  private UUID memberCommitted(String nickname) {
    UUID id = member(nickname);
    createdUsers.add(id);
    return id;
  }

  private List<String> uploadAll(UUID user, String purpose) throws Exception {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < PHOTOS; i++) {
      keys.add(uploadAndPut(user, purpose));
    }
    return keys;
  }

  /** 올릴 주소를 받고(POST /uploads), 앱이 그 주소로 PUT 한 것처럼 가짜 저장소에 파일을 둔다. */
  private String uploadAndPut(UUID user, String purpose) throws Exception {
    JsonNode ticket =
        body(
            mvc.perform(
                    post("/uploads")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "purpose",
                                    purpose,
                                    "contentType",
                                    "image/jpeg",
                                    "bytes",
                                    1234))))
                .andExpect(status().isCreated())
                .andReturn());
    String key = ticket.path("key").asString();
    assertThat(key).as("올리기 창구가 key 를 준다").isNotBlank();
    objects.put(key, "jpeg-bytes-" + key);
    seenKeys.add(key);
    return key;
  }

  /** 같은 요청을 두 스레드에서 같은 순간에 보낸다. */
  private List<MvcResult> concurrently(java.util.function.Supplier<RequestBuilder> request)
      throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
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

  /** 이긴 쪽 사진: 보낸 장수 그대로이고, 저장소에 있고, 지울 목록(storage_deletion)에도 오르지 않았다. */
  private void assertWinnerPhotosAlive(SoftAssertions soft, String label, List<String> keys) {
    soft.assertThat(keys).as(label + " — 사진 수").hasSize(PHOTOS);
    soft.assertThat(objects.keySet())
        .as(label + " — 사진 %s 가 저장소에 있어야 한다. 저장소: %s", keys, objects.keySet())
        .containsAll(keys);
    for (String key : keys) {
      long queued =
          jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key = :k")
              .param("k", key)
              .query(Long.class)
              .single();
      soft.assertThat(queued).as(label + " — 사진 %s 가 지울 목록에 오르면 안 된다", key).isZero();
    }
  }

  private static String code(MvcResult r) {
    try {
      return body(r).path("code").asString();
    } catch (Exception e) {
      return "(본문을 읽지 못함)";
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
