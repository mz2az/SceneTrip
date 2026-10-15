package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 코스 이름 금칙어 거르기(MZ2AZ-438) — 코스 이름은 여행후기에 붙으면 모두에게 보인다. 계약 1.19.0 {@code POST /courses}·{@code PUT
 * /courses/{courseId}} 의 400 {@code TEXT_NOT_ALLOWED}(message 에 「코스 이름」).
 *
 * <p>명세에서 나온 기대: 금칙어가 든 이름은 400 이고 아무것도 저장되지 않는다(만들기는 코스가 생기지 않고, 고치기는 원래 코스 그대로). 이름이 없거나 비면 예전처럼
 * 통과. 고치기는 원래 검사(코스가 있는가·항목 모양)가 먼저라 없는 코스는 여전히 404 다.
 *
 * <p>스프링 없이 컨트롤러를 손으로 조립하고 진짜 {@link CourseStore}·PostgreSQL 을 쓴다. 비회원(설치 id)으로 부르고, 시험마다 만든 계정을
 * 지운다(코스는 계정과 함께 지워진다).
 */
@DisplayName("코스 이름 금칙어 — POST /courses · PUT /courses/{id} (실제 DB)")
class CourseTitleFilterIntegrationTest {

  private static JdbcClient jdbc;
  private static UserStore users;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(filled(32, (byte) 3), Duration.ofMinutes(30), Clock.systemUTC());
  private final CurrentAccount accounts = new CurrentAccount(request, tokens, users);
  private final CourseController courses = new CourseController(courseStore(), accounts);

  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM course WHERE user_id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── 만들기 ─────────────

  @Test
  @DisplayName(
      "만들기 — 금칙어 이름(그대로·띄워 쓰기·기호·전각)은 400 TEXT_NOT_ALLOWED, message 에 「코스 이름」, 코스가 생기지 않는다")
  void createWithBannedTitleIsRejected() {
    UUID install = guest();
    for (String title : List.of("씨발 여행", "씨 발 코스", "f.u.c.k trip", "ｆｕｃｋ 여행", "병신같은 코스")) {
      ApiException e =
          expect(
              () ->
                  courses.createCourse(
                      install, new CourseCreate(2, CourseOrigin.SELF).title(title), Lang.KO),
              HttpStatus.BAD_REQUEST,
              "TEXT_NOT_ALLOWED");
      assertThat(e.getMessage()).as(title).contains("코스 이름");
    }
    assertThat(courseCount(install)).isZero();
  }

  @Test
  @DisplayName("만들기 — 평범한 이름·예외 낱말(「시발점 여행」·「7시발 KTX 여행」)은 201 이고 그 이름으로 저장된다")
  void createWithNormalTitles() {
    UUID install = guest();
    for (String title :
        List.of("부산 2박 3일", "시발점 여행", "7시발 KTX 여행", "Scunthorpe trip", "도착 시 발권 코스")) {
      var response =
          courses.createCourse(
              install, new CourseCreate(1, CourseOrigin.SELF).title(title), Lang.KO);
      assertThat(response.getStatusCode()).as(title).isEqualTo(HttpStatus.CREATED);
      assertThat(response.getBody().getTitle()).isEqualTo(title);
    }
    assertThat(courseCount(install)).isEqualTo(5);
  }

  @Test
  @DisplayName("만들기 — 이름이 없거나(null) 비어 있으면 예전처럼 201(서버가 채운 이름)")
  void createWithoutTitle() {
    UUID install = guest();
    var none = courses.createCourse(install, new CourseCreate(1, CourseOrigin.SELF), Lang.KO);
    var blank =
        courses.createCourse(install, new CourseCreate(1, CourseOrigin.SELF).title("   "), Lang.KO);
    assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(none.getBody().getTitle()).isNotBlank();
    assertThat(blank.getBody().getTitle()).isNotBlank();
  }

  // ───────────── 고치기 ─────────────

  @Test
  @DisplayName("고치기 — 금칙어 이름은 400 TEXT_NOT_ALLOWED(「코스 이름」)이고 원래 이름·기간이 그대로다")
  void replaceWithBannedTitleKeepsCourse() {
    UUID install = guest();
    long id = create(install, "원래 이름", 3);

    ApiException e =
        expect(
            () -> courses.replaceCourse(install, id, replace("FUCK 여행", 1), Lang.KO),
            HttpStatus.BAD_REQUEST,
            "TEXT_NOT_ALLOWED");
    assertThat(e.getMessage()).contains("코스 이름");

    CourseDetail now = courses.getCourse(install, id, Lang.KO).getBody();
    assertThat(now.getTitle()).isEqualTo("원래 이름");
    assertThat(now.getDays()).hasSize(3);
  }

  @Test
  @DisplayName("고치기 — 「시발점 여행」 은 200 이고 이름·기간이 바뀐다. 이름을 비우면 예전처럼 통과")
  void replaceWithNormalOrBlankTitle() {
    UUID install = guest();
    long id = create(install, "원래 이름", 3);

    var ok = courses.replaceCourse(install, id, replace("시발점 여행", 2), Lang.KO);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    CourseDetail now = courses.getCourse(install, id, Lang.KO).getBody();
    assertThat(now.getTitle()).isEqualTo("시발점 여행");
    assertThat(now.getDays()).hasSize(2);

    var blank = courses.replaceCourse(install, id, replace("  ", 1), Lang.KO);
    assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(courses.getCourse(install, id, Lang.KO).getBody().getTitle()).isNotBlank();
  }

  @Test
  @DisplayName("고치기 — 원래 검사가 먼저: 없는 코스·남의 코스는 금칙어 이름이어도 404 COURSE_NOT_FOUND")
  void replaceUnknownCourseIs404First() {
    UUID install = guest();
    expect(
        () -> courses.replaceCourse(install, Long.MAX_VALUE, replace("씨발 여행", 1), Lang.KO),
        HttpStatus.NOT_FOUND,
        "COURSE_NOT_FOUND");

    UUID owner = guest();
    long others = create(owner, "남의 코스", 1);
    expect(
        () -> courses.replaceCourse(install, others, replace("씨발 여행", 1), Lang.KO),
        HttpStatus.NOT_FOUND,
        "COURSE_NOT_FOUND");
    assertThat(courses.getCourse(owner, others, Lang.KO).getBody().getTitle()).isEqualTo("남의 코스");
  }

  @Test
  @DisplayName("고치기 — 원래 검사가 먼저: 모양이 틀린 항목(장소·편의시설·핀 중 아무것도 없음)은 금칙어 이름이어도 400 INVALID_PARAMETER")
  void replaceItemValidationRunsBeforeTitle() {
    UUID install = guest();
    long id = create(install, "원래 이름", 1);
    CourseReplace bad =
        new CourseReplace("씨발 여행", List.of(new CourseDayInput(List.of(new CourseItemInput()))));
    expect(
        () -> courses.replaceCourse(install, id, bad, Lang.KO),
        HttpStatus.BAD_REQUEST,
        "INVALID_PARAMETER");
    assertThat(courses.getCourse(install, id, Lang.KO).getBody().getTitle()).isEqualTo("원래 이름");
  }

  // ───────────── 도우미 ─────────────

  /** 새 설치 id 로 비회원 계정을 만들고 그 설치 id 를 돌려준다. */
  private UUID guest() {
    UUID install = UUID.randomUUID();
    createdUsers.add(users.resolve(install));
    return install;
  }

  private long create(UUID install, String title, int days) {
    return courses
        .createCourse(install, new CourseCreate(days, CourseOrigin.SELF).title(title), Lang.KO)
        .getBody()
        .getId();
  }

  private static CourseReplace replace(String title, int days) {
    List<CourseDayInput> list = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      list.add(new CourseDayInput(List.of()));
    }
    return new CourseReplace(title, list);
  }

  private long courseCount(UUID install) {
    return jdbc.sql("SELECT count(*) FROM course WHERE user_id = CAST(:u AS UUID)")
        .param("u", users.resolve(install).toString())
        .query(Long.class)
        .single();
  }

  private static ApiException expect(Runnable call, HttpStatus status, String code) {
    ApiException[] caught = new ApiException[1];
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(status);
              assertThat(e.getCode()).isEqualTo(code);
              caught[0] = e;
            });
    return caught[0];
  }

  private static CourseStore courseStore() {
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    return new CourseStore(
        IntegrationDatabase.jdbcClient(),
        DeployedTravel.estimator(),
        dwell,
        IntegrationDatabase.transactions());
  }

  private static byte[] filled(int n, byte b) {
    byte[] out = new byte[n];
    Arrays.fill(out, b);
    return out;
  }
}
