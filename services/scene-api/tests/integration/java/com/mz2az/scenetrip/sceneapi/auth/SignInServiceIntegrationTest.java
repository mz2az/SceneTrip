package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rotated;
import com.mz2az.scenetrip.sceneapi.auth.SignInService.SignedIn;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입·로그인·합치기를 <b>진짜 PostgreSQL</b> 위에서 명세(계약 {@code POST /auth/google}, 계획 §5 「로그인 한 번에 일어나는
 * 일」·「합치기」· 「트랜잭션과 동시성」, {@link SignInService}·{@link AccountLinkStore} 의 클래스 설명, 마이그레이션
 * V8·V9·V15)에 비춰 본다.
 *
 * <p>계정 사이에서 데이터가 움직이는 자리라 「옮겨졌다」 만이 아니라 「겹치지 않았다」·「남지 않았다」·「건드리지 않았다」 를 함께 본다.
 */
@DisplayName("SignInService · AccountLinkStore — 가입·로그인·합치기 (실제 DB)")
class SignInServiceIntegrationTest {

  private static final Duration REFRESH_TTL = Duration.ofDays(60);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static AccountLinkStore links;
  private static RefreshTokenStore refreshTokens;
  private static SignInService service;

  private static long[] places;
  private static long[] contents;

  private final Set<UUID> createdUsers = new LinkedHashSet<>();
  private final Set<UUID> installs = new LinkedHashSet<>();
  private final Set<String> subjects = new LinkedHashSet<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
    refreshTokens = new RefreshTokenStore(jdbc, transactions, REFRESH_TTL, Clock.systemUTC());
    service = new SignInService(users, links, refreshTokens, transactions);
    places = ids("SELECT id FROM place ORDER BY id LIMIT 5", 5);
    contents = ids("SELECT id FROM content ORDER BY id LIMIT 3", 3);
  }

  @AfterEach
  void cleanUp() {
    // 로그인이 만든 계정(설치본을 옮겨 받은 새 비회원 등)은 설치본과 신분으로 찾아 함께 지운다.
    Set<UUID> all = new LinkedHashSet<>(createdUsers);
    for (UUID install : installs) {
      deviceOwner(install).ifPresent(all::add);
    }
    for (String subject : subjects) {
      jdbc.sql("SELECT user_id FROM user_identity WHERE subject = :s")
          .param("s", subject)
          .query(UUID.class)
          .list()
          .forEach(all::add);
    }
    // merged_into 에는 CASCADE 가 없어 가리키는 쪽을 먼저 끊어야 지울 수 있다.
    for (UUID id : all) {
      jdbc.sql(
              "UPDATE app_user SET merged_into = NULL"
                  + " WHERE id = CAST(:id AS UUID) OR merged_into = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
    for (UUID id : all) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── 가입 ─────────────

  @Test
  @DisplayName(
      "처음 보는 신분이면 이 설치본의 비회원 계정이 그대로 가입 계정이 된다 — 같은 id, 데이터 그대로, isNewUser=true·merged=false")
  void firstSignInRegistersGuestInPlace() {
    UUID install = newInstall();
    UUID guest = guestOf(install);
    long courseId = insertCourse(guest, "upcoming", null, 2, places[0], places[1]);
    savePlace(guest, places[2], contents[0]);
    saveContent(guest, contents[1]);
    SocialIdentity identity = identity("first@example.com", "김여행");

    SignedIn result = service.signIn(identity, install);

    assertThat(result.userId()).isEqualTo(guest);
    assertThat(result.isNewUser()).isTrue();
    assertThat(result.merged()).isFalse();
    assertThat(isRegistered(guest)).isTrue();
    assertThat(identityOwner(identity)).isEqualTo(guest);
    assertThat(courseOwner(courseId)).isEqualTo(guest);
    assertThat(courseItemCount(courseId)).isEqualTo(2);
    assertThat(savedPlaces(guest)).containsExactly(places[2]);
    assertThat(savedContents(guest)).containsExactly(contents[1]);
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(guest, true));
    assertThat(mergedInto(guest)).isNull();
    assertRefreshTokenBelongsTo(result.refreshToken(), guest);
    assertNoPartialState();
  }

  @Test
  @DisplayName("설치본 행이 아직 없어도 로그인된다 — 새 비회원을 만들어 그것을 가입시킨다")
  void firstSignInOnUnknownInstall() {
    UUID install = newInstall();
    SocialIdentity identity = identity("new@example.com", "새사람");

    SignedIn result = service.signIn(identity, install);

    assertThat(result.isNewUser()).isTrue();
    assertThat(result.merged()).isFalse();
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(result.userId(), true));
    assertThat(identityOwner(identity)).isEqualTo(result.userId());
    assertRefreshTokenBelongsTo(result.refreshToken(), result.userId());
  }

  @Test
  @DisplayName("같은 설치본·같은 신분으로 다시 로그인하면 같은 계정, isNewUser=false·merged=false, 신분은 여전히 하나")
  void sameInstallSameIdentityAgain() {
    UUID install = newInstall();
    UUID guest = guestOf(install);
    SocialIdentity identity = identity("a@example.com", "김여행");
    service.signIn(identity, install);

    SignedIn again = service.signIn(identity, install);

    assertThat(again.userId()).isEqualTo(guest);
    assertThat(again.isNewUser()).isFalse();
    assertThat(again.merged()).isFalse();
    assertThat(identityCount(identity)).isEqualTo(1);
    assertRefreshTokenBelongsTo(again.refreshToken(), guest);
  }

  // ───────────── 이메일·이름 ─────────────

  @Test
  @DisplayName("다시 로그인할 때 새 이메일·새 이름이 오면 바뀐다")
  void laterSignInRefreshesEmailAndName() {
    UUID install = newInstall();
    SocialIdentity first = identity("old@example.com", "옛이름");
    service.signIn(first, install);

    service.signIn(
        new SocialIdentity("google", first.subject(), "new@example.com", "새이름"), install);

    assertThat(identityColumn(first, "email")).isEqualTo("new@example.com");
    assertThat(identityColumn(first, "display_name")).isEqualTo("새이름");
  }

  @Test
  @DisplayName("다시 로그인할 때 이름·이메일이 비어 오면 저장된 것을 지우지 않는다")
  void laterSignInWithoutNameKeepsName() {
    UUID install = newInstall();
    SocialIdentity first = identity("keep@example.com", "김여행");
    service.signIn(first, install);

    service.signIn(new SocialIdentity("google", first.subject(), null, null), install);

    assertThat(identityColumn(first, "display_name")).isEqualTo("김여행");
    assertThat(identityColumn(first, "email")).isEqualTo("keep@example.com");
  }

  @Test
  @DisplayName("다른 설치본에서 로그인(합치기)할 때도 새 이메일이 반영된다")
  void mergeSignInRefreshesEmail() {
    SocialIdentity identity = identity("phone@example.com", "김여행");
    service.signIn(identity, newInstall());

    service.signIn(
        new SocialIdentity("google", identity.subject(), "tablet@example.com", null), newInstall());

    assertThat(identityColumn(identity, "email")).isEqualTo("tablet@example.com");
    assertThat(identityColumn(identity, "display_name")).isEqualTo("김여행");
  }

  // ───────────── 합치기 ─────────────

  @Test
  @DisplayName(
      "다른 설치본에서 가입한 신분이면 이 설치본의 비회원 데이터를 그 계정으로 합친다 — 코스(아이템째)·장바구니·찜, 겹친 것은 하나만, G 는 비고"
          + " merged_into=X")
  void mergeMovesGuestDataIntoExistingAccount() {
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    SocialIdentity identity = identity("x@example.com", "엑스");
    service.signIn(identity, installA);
    long xCourse = insertCourse(x, "upcoming", null, 1, places[0]);
    savePlace(x, places[0], null);
    savePlace(x, places[1], contents[0]);
    saveContent(x, contents[0]);

    UUID installB = newInstall();
    UUID g = guestOf(installB);
    long gCourse = insertCourse(g, "upcoming", null, 2, places[2], places[3]);
    long gCourse2 = insertCourse(g, "upcoming", null, 1, places[4]);
    savePlace(g, places[1], null); // X 에도 있다 — 겹친다
    savePlace(g, places[2], contents[1]); // G 에만 있다
    saveContent(g, contents[0]); // 겹친다
    saveContent(g, contents[2]); // G 에만 있다

    SignedIn result = service.signIn(identity, installB);

    assertThat(result.userId()).isEqualTo(x);
    assertThat(result.merged()).isTrue();
    assertThat(result.isNewUser()).isFalse();

    assertThat(courseOwner(gCourse)).isEqualTo(x);
    assertThat(courseOwner(gCourse2)).isEqualTo(x);
    assertThat(courseItemCount(gCourse)).isEqualTo(2);
    assertThat(courseItemCount(gCourse2)).isEqualTo(1);
    assertThat(courseOwner(xCourse)).isEqualTo(x);
    assertThat(courseItemCount(xCourse)).isEqualTo(1);

    assertThat(savedPlaces(x)).containsExactlyInAnyOrder(places[0], places[1], places[2]);
    assertThat(sourceContentOf(x, places[2])).isEqualTo(contents[1]);
    assertThat(savedContents(x)).containsExactlyInAnyOrder(contents[0], contents[2]);

    assertThat(count("course", g)).isZero();
    assertThat(count("saved_place", g)).isZero();
    assertThat(count("saved_content", g)).isZero();

    assertThat(users.lookup(installB)).isEqualTo(new UserStore.Account(x, true));
    assertThat(users.lookup(installA)).isEqualTo(new UserStore.Account(x, true));
    assertThat(deviceOwner(installB)).contains(x);
    assertThat(mergedInto(g)).isEqualTo(x);
    assertThat(userExists(g)).isTrue();
    assertThat(isRegistered(g)).isFalse();
    assertThat(count("user_identity", g)).isZero();
    assertThat(identityCount(identity)).isEqualTo(1);
    assertRefreshTokenBelongsTo(result.refreshToken(), x);
    assertNoPartialState();
  }

  @Test
  @DisplayName("합친 뒤 G 로 합쳐진 설치본에서 다시 로그인하면 합칠 것이 없다 — 같은 계정, merged=false")
  void signInAgainAfterMergeIsPlainSignIn() {
    SocialIdentity identity = identity("x@example.com", null);
    UUID x = service.signIn(identity, newInstall()).userId();
    UUID installB = newInstall();
    guestOf(installB);
    service.signIn(identity, installB);

    SignedIn again = service.signIn(identity, installB);

    assertThat(again.userId()).isEqualTo(x);
    assertThat(again.isNewUser()).isFalse();
    assertThat(again.merged()).isFalse();
  }

  // ───────────── 진행 중 여행 ─────────────

  @Test
  @DisplayName("X 에 진행 중 코스가 있으면 G 의 진행 중 코스는 upcoming 으로 내려가고 current_day_no 가 비워진다 — X 의 것은 그대로")
  void mergeDemotesGuestActiveTripWhenTargetHasOne() {
    SocialIdentity identity = identity("x@example.com", null);
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    service.signIn(identity, installA);
    long xActive = insertCourse(x, "active", 1, 2, places[0]);
    UUID installB = newInstall();
    UUID g = guestOf(installB);
    long gActive = insertCourse(g, "active", 2, 3, places[1]);

    service.signIn(identity, installB);

    assertThat(courseStatus(xActive)).isEqualTo("active");
    assertThat(currentDay(xActive)).isEqualTo(1);
    assertThat(courseOwner(gActive)).isEqualTo(x);
    assertThat(courseStatus(gActive)).isEqualTo("upcoming");
    assertThat(currentDay(gActive)).isNull();
    assertThat(activeCourseCount(x)).isEqualTo(1);
  }

  @Test
  @DisplayName("X 에 진행 중 코스가 없으면 G 의 진행 중 코스는 진행 중 그대로 넘어간다")
  void mergeKeepsGuestActiveTripWhenTargetHasNone() {
    SocialIdentity identity = identity("x@example.com", null);
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    service.signIn(identity, installA);
    insertCourse(x, "upcoming", null, 1, places[0]);
    UUID installB = newInstall();
    UUID g = guestOf(installB);
    long gActive = insertCourse(g, "active", 2, 3, places[1]);

    service.signIn(identity, installB);

    assertThat(courseOwner(gActive)).isEqualTo(x);
    assertThat(courseStatus(gActive)).isEqualTo("active");
    assertThat(currentDay(gActive)).isEqualTo(2);
  }

  // ───────────── 설치본이 이미 가입 계정을 가리킬 때 ─────────────

  @Test
  @DisplayName("설치본이 가입 계정 Z 를 가리키는데 새 신분으로 로그인하면 — 설치본을 새 비회원으로 옮겨 그것을 가입시킨다, Z 는 그대로")
  void installOnRegisteredAccountWithNewIdentity() {
    UUID install = newInstall();
    SocialIdentity first = identity("z@example.com", "제트");
    UUID z = service.signIn(first, install).userId();
    long zCourse = insertCourse(z, "upcoming", null, 1, places[0]);
    savePlace(z, places[1], null);
    SocialIdentity second = identity("other@example.com", "다른사람");

    SignedIn result = service.signIn(second, install);

    assertThat(result.userId()).isNotEqualTo(z);
    assertThat(result.isNewUser()).isTrue();
    assertThat(result.merged()).isFalse();
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(result.userId(), true));
    assertThat(identityOwner(second)).isEqualTo(result.userId());
    // Z 는 손대지 않는다 — 신분이 늘지 않고, 합쳐지지 않고, 데이터가 그대로다.
    assertThat(count("user_identity", z)).isEqualTo(1);
    assertThat(identityOwner(first)).isEqualTo(z);
    assertThat(mergedInto(z)).isNull();
    assertThat(isRegistered(z)).isTrue();
    assertThat(courseOwner(zCourse)).isEqualTo(z);
    assertThat(savedPlaces(z)).containsExactly(places[1]);
    assertThat(count("course", result.userId())).isZero();
    assertThat(count("saved_place", result.userId())).isZero();
    assertNoPartialState();
  }

  @Test
  @DisplayName("설치본이 가입 계정 Z 를 가리키는데 다른 가입 계정 Y 의 신분으로 로그인하면 — 합치지 않고 설치본만 Y 로 바꿔 단다")
  void installOnRegisteredAccountWithOtherAccountsIdentity() {
    SocialIdentity yIdentity = identity("y@example.com", "와이");
    UUID y = service.signIn(yIdentity, newInstall()).userId();
    UUID install = newInstall();
    SocialIdentity zIdentity = identity("z@example.com", "제트");
    UUID z = service.signIn(zIdentity, install).userId();
    long zCourse = insertCourse(z, "upcoming", null, 1, places[0]);
    savePlace(z, places[1], null);
    saveContent(z, contents[0]);

    SignedIn result = service.signIn(yIdentity, install);

    assertThat(result.userId()).isEqualTo(y);
    assertThat(result.isNewUser()).isFalse();
    assertThat(result.merged()).isFalse();
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(y, true));
    // 가입 계정끼리는 절대 합치지 않는다.
    assertThat(mergedInto(z)).isNull();
    assertThat(isRegistered(z)).isTrue();
    assertThat(identityOwner(zIdentity)).isEqualTo(z);
    assertThat(courseOwner(zCourse)).isEqualTo(z);
    assertThat(savedPlaces(z)).containsExactly(places[1]);
    assertThat(savedContents(z)).containsExactly(contents[0]);
    assertThat(count("course", y)).isZero();
    assertThat(count("saved_place", y)).isZero();
    assertThat(count("user_identity", y)).isEqualTo(1);
  }

  // ───────────── 원자성 ─────────────

  @Test
  @DisplayName("가입 도중 DB 가 거절하면(허용되지 않은 provider) 아무것도 남지 않는다 — 가입 표시·신분·리프레시 토큰 모두")
  void failedSignUpLeavesNothing() {
    UUID install = newInstall();
    UUID guest = guestOf(install);
    String subject = "sub-" + UUID.randomUUID();
    subjects.add(subject);

    assertThatThrownBy(
            () ->
                service.signIn(
                    new SocialIdentity("facebook", subject, "f@example.com", "에프"), install))
        .isInstanceOf(RuntimeException.class);

    assertThat(isRegistered(guest)).isFalse();
    assertThat(count("user_identity", guest)).isZero();
    assertThat(count("refresh_token", guest)).isZero();
    assertThat(users.lookup(install)).isEqualTo(new UserStore.Account(guest, false));
  }

  // ───────────── 동시성 ─────────────

  @Test
  @DisplayName("같은 새 신분으로 두 설치본이 동시에 첫 로그인 — 둘 다 성공, 가입은 하나, 같은 계정, 다른 쪽 데이터는 합쳐지고 신분은 한 줄")
  void concurrentFirstSignInFromTwoInstalls() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 8; round++) {
        UUID i1 = newInstall();
        UUID i2 = newInstall();
        UUID g1 = guestOf(i1);
        UUID g2 = guestOf(i2);
        savePlace(g1, places[0], null);
        saveContent(g1, contents[0]);
        long c1 = insertCourse(g1, "upcoming", null, 1, places[1]);
        savePlace(g2, places[2], null);
        saveContent(g2, contents[1]);
        long c2 = insertCourse(g2, "upcoming", null, 1, places[3]);
        SocialIdentity identity = identity("race@example.com", "경주");

        CyclicBarrier start = new CyclicBarrier(2);
        Future<SignedIn> f1 = pool.submit(atOnce(start, identity, i1));
        Future<SignedIn> f2 = pool.submit(atOnce(start, identity, i2));
        SignedIn r1 = f1.get(30, TimeUnit.SECONDS);
        SignedIn r2 = f2.get(30, TimeUnit.SECONDS);

        String at = "round " + round;
        assertThat(r1.userId()).as(at).isEqualTo(r2.userId());
        UUID owner = r1.userId();
        assertThat(owner).as(at).isIn(g1, g2);
        UUID loser = owner.equals(g1) ? g2 : g1;
        assertThat(List.of(r1.isNewUser(), r2.isNewUser())).as(at).containsOnlyOnce(true);
        SignedIn loserResult = r1.isNewUser() ? r2 : r1;
        assertThat(loserResult.merged()).as(at).isTrue();
        assertThat(identityCount(identity)).as(at).isEqualTo(1);
        assertThat(identityOwner(identity)).as(at).isEqualTo(owner);
        assertThat(mergedInto(loser)).as(at).isEqualTo(owner);
        assertThat(isRegistered(loser)).as(at).isFalse();
        assertThat(savedPlaces(owner)).as(at).containsExactlyInAnyOrder(places[0], places[2]);
        assertThat(savedContents(owner)).as(at).containsExactlyInAnyOrder(contents[0], contents[1]);
        assertThat(courseOwner(c1)).as(at).isEqualTo(owner);
        assertThat(courseOwner(c2)).as(at).isEqualTo(owner);
        assertThat(count("saved_place", loser)).as(at).isZero();
        assertThat(count("course", loser)).as(at).isZero();
        assertThat(users.lookup(i1)).as(at).isEqualTo(new UserStore.Account(owner, true));
        assertThat(users.lookup(i2)).as(at).isEqualTo(new UserStore.Account(owner, true));
        assertRefreshTokenBelongsTo(r1.refreshToken(), owner);
        assertRefreshTokenBelongsTo(r2.refreshToken(), owner);
        assertNoPartialState();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("같은 설치본의 로그인이 동시에 둘 — 둘 다 성공, 같은 계정, 가입은 하나, 신분은 한 줄")
  void concurrentSignInFromSameInstall() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 8; round++) {
        UUID install = newInstall();
        UUID guest = guestOf(install);
        SocialIdentity identity = identity("twice@example.com", "두번");

        CyclicBarrier start = new CyclicBarrier(2);
        Future<SignedIn> f1 = pool.submit(atOnce(start, identity, install));
        Future<SignedIn> f2 = pool.submit(atOnce(start, identity, install));
        SignedIn r1 = f1.get(30, TimeUnit.SECONDS);
        SignedIn r2 = f2.get(30, TimeUnit.SECONDS);

        String at = "round " + round;
        assertThat(r1.userId()).as(at).isEqualTo(guest);
        assertThat(r2.userId()).as(at).isEqualTo(guest);
        assertThat(List.of(r1.isNewUser(), r2.isNewUser())).as(at).containsOnlyOnce(true);
        assertThat(r1.merged()).as(at).isFalse();
        assertThat(r2.merged()).as(at).isFalse();
        assertThat(identityCount(identity)).as(at).isEqualTo(1);
        assertThat(users.lookup(install)).as(at).isEqualTo(new UserStore.Account(guest, true));
        assertNoPartialState();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("같은 새 신분으로 처음 보는 두 설치본이 동시에 첫 로그인해도 — 둘 다 성공하고 같은 계정")
  void concurrentFirstSignInFromTwoUnknownInstalls() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        UUID i1 = newInstall();
        UUID i2 = newInstall();
        SocialIdentity identity = identity("fresh@example.com", null);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<SignedIn> f1 = pool.submit(atOnce(start, identity, i1));
        Future<SignedIn> f2 = pool.submit(atOnce(start, identity, i2));
        SignedIn r1 = f1.get(30, TimeUnit.SECONDS);
        SignedIn r2 = f2.get(30, TimeUnit.SECONDS);

        String at = "round " + round;
        assertThat(r1.userId()).as(at).isEqualTo(r2.userId());
        assertThat(List.of(r1.isNewUser(), r2.isNewUser())).as(at).containsOnlyOnce(true);
        assertThat(identityCount(identity)).as(at).isEqualTo(1);
        assertThat(deviceOwner(i1)).as(at).contains(r1.userId());
        assertThat(deviceOwner(i2)).as(at).contains(r1.userId());
        assertNoPartialState();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ───────────── 쓸어 오기 (알려진 틈의 복구) ─────────────

  @Test
  @DisplayName("합친 뒤 G 에 늦게 떨어진 장바구니·찜·코스를 sweepInto 가 X 로 옮긴다 — 겹친 것은 버린다, 두 번째는 0")
  void sweepIntoMovesStragglers() {
    Merged m = mergedPair();
    // 「알려진 틈」 — 합치기 직전에 계정을 G 로 정한 요청이 합친 뒤에 쓴다.
    savePlace(m.g(), places[3], contents[2]); // X 에 없다
    savePlace(m.g(), places[0], null); // X 에 이미 있다
    saveContent(m.g(), contents[1]);
    long straggler = insertCourse(m.g(), "upcoming", null, 1, places[4]);

    int moved = sweepInto(m.x());

    // 「from 에서 빠진 행 수」 — 장소 2(하나는 겹쳐 버려짐) + 찜 1 + 코스 1.
    assertThat(moved).isEqualTo(4);
    assertThat(savedPlaces(m.x())).containsExactlyInAnyOrder(places[0], places[3]);
    assertThat(sourceContentOf(m.x(), places[3])).isEqualTo(contents[2]);
    assertThat(savedContents(m.x())).contains(contents[1]);
    assertThat(courseOwner(straggler)).isEqualTo(m.x());
    assertThat(courseItemCount(straggler)).isEqualTo(1);
    assertThat(count("saved_place", m.g())).isZero();
    assertThat(count("saved_content", m.g())).isZero();
    assertThat(count("course", m.g())).isZero();
    assertThat(mergedInto(m.g())).isEqualTo(m.x());

    assertThat(sweepInto(m.x())).isZero();
  }

  @Test
  @DisplayName("쓸어 올 것이 없으면 0 — 합쳐진 행이 없는 계정도 0")
  void sweepIntoWithNothingIsZero() {
    Merged m = mergedPair();
    UUID lonely = service.signIn(identity("lonely@example.com", null), newInstall()).userId();

    assertThat(sweepInto(m.x())).isZero();
    assertThat(sweepInto(lonely)).isZero();
  }

  @Test
  @DisplayName("쓸어 올 때도 진행 중 여행을 정리한다 — X 에 진행 중이 있으면 늦게 온 진행 중 코스는 upcoming")
  void sweepIntoDemotesStragglerActiveTrip() {
    Merged m = mergedPair();
    long xActive = insertCourse(m.x(), "active", 1, 2, places[0]);
    long straggler = insertCourse(m.g(), "active", 1, 1, places[1]);

    sweepInto(m.x());

    assertThat(courseOwner(straggler)).isEqualTo(m.x());
    assertThat(courseStatus(straggler)).isEqualTo("upcoming");
    assertThat(currentDay(straggler)).isNull();
    assertThat(courseStatus(xActive)).isEqualTo("active");
  }

  @Test
  @DisplayName("SignInService.sweep 도 늦게 온 것을 옮긴다")
  void serviceSweepMovesStragglers() {
    Merged m = mergedPair();
    savePlace(m.g(), places[4], null);

    service.sweep(m.x());

    assertThat(savedPlaces(m.x())).contains(places[4]);
    assertThat(count("saved_place", m.g())).isZero();
  }

  @Test
  @DisplayName("SignInService.sweep 은 실패해도 던지지 않는다 — 없는 계정이든 null 이든")
  void serviceSweepNeverThrows() {
    assertThatCode(() -> service.sweep(UUID.randomUUID())).doesNotThrowAnyException();
    assertThatCode(() -> service.sweep(null)).doesNotThrowAnyException();
  }

  // ───────────── 도우미 ─────────────

  private record Merged(UUID x, UUID g) {}

  /** AccountLinkStore 는 트랜잭션을 열지 않는다 — 부르는 쪽처럼 감싼다. */
  private static int sweepInto(UUID to) {
    Integer moved = transactions.execute(status -> links.sweepInto(to));
    return moved;
  }

  /** X 가 설치본 A 에서 가입하고, 설치본 B 의 비회원 G 가 같은 신분으로 로그인해 X 로 합쳐진 상태. X 는 장소 0 을 담아 두었다. */
  private Merged mergedPair() {
    SocialIdentity identity = identity("x@example.com", "엑스");
    UUID installA = newInstall();
    UUID x = guestOf(installA);
    service.signIn(identity, installA);
    savePlace(x, places[0], null);
    UUID installB = newInstall();
    UUID g = guestOf(installB);
    SignedIn merged = service.signIn(identity, installB);
    assertThat(merged.merged()).isTrue();
    assertThat(mergedInto(g)).isEqualTo(x);
    return new Merged(x, g);
  }

  private static Callable<SignedIn> atOnce(
      CyclicBarrier start, SocialIdentity identity, UUID install) {
    return () -> {
      start.await(10, TimeUnit.SECONDS);
      return service.signIn(identity, install);
    };
  }

  private SocialIdentity identity(String email, String name) {
    String subject = "sub-" + UUID.randomUUID();
    subjects.add(subject);
    return new SocialIdentity("google", subject, email, name);
  }

  private UUID newInstall() {
    UUID install = UUID.randomUUID();
    installs.add(install);
    return install;
  }

  private UUID guestOf(UUID install) {
    UUID id = users.resolve(install);
    createdUsers.add(id);
    return id;
  }

  /** 설치본·신분으로 찾을 수 없는 계정까지 포함해, 이 시험이 만든 계정에 반쯤 된 상태가 없는가. */
  private void assertNoPartialState() {
    Set<UUID> all = new LinkedHashSet<>(createdUsers);
    installs.forEach(i -> deviceOwner(i).ifPresent(all::add));
    for (UUID id : all) {
      if (count("user_identity", id) > 0) {
        assertThat(isRegistered(id)).as("신분이 붙은 %s 는 가입 표시가 있어야 한다", id).isTrue();
      }
      if (mergedInto(id) != null) {
        assertThat(count("user_identity", id)).as("합쳐진 %s 에 신분이 남았다", id).isZero();
        assertThat(count("course", id) + count("saved_place", id) + count("saved_content", id))
            .as("합쳐진 %s 에 데이터가 남았다", id)
            .isZero();
        assertThat(count("user_device", id)).as("합쳐진 %s 를 가리키는 설치본이 남았다", id).isZero();
      }
    }
  }

  private static void assertRefreshTokenBelongsTo(IssuedToken token, UUID userId) {
    assertThat(token).isNotNull();
    assertThat(token.value()).isNotBlank();
    assertThat(token.expiresInSeconds()).isEqualTo(REFRESH_TTL.toSeconds());
    // 교체해 보면 그 토큰의 계정이 나온다. 이 시험 뒤로 그 토큰은 쓰지 않는다.
    assertThat(refreshTokens.rotate(token.value()))
        .isInstanceOfSatisfying(Rotated.class, r -> assertThat(r.userId()).isEqualTo(userId));
  }

  private static long[] ids(String sql, int n) {
    List<Long> list = jdbc.sql(sql).query(Long.class).list();
    assertThat(list).as("시험 데이터(%s)", sql).hasSize(n);
    return list.stream().mapToLong(Long::longValue).toArray();
  }

  private static Optional<UUID> deviceOwner(UUID install) {
    return jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
        .param("i", install.toString())
        .query(UUID.class)
        .optional();
  }

  private static boolean isRegistered(UUID id) {
    return jdbc.sql("SELECT registered_at IS NOT NULL FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(Boolean.class)
        .single();
  }

  private static boolean userExists(UUID id) {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", id.toString())
            .query(Long.class)
            .single()
        == 1;
  }

  private static UUID mergedInto(UUID id) {
    return jdbc.sql("SELECT merged_into FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private static UUID identityOwner(SocialIdentity identity) {
    return jdbc.sql("SELECT user_id FROM user_identity WHERE provider = :p AND subject = :s")
        .param("p", identity.provider())
        .param("s", identity.subject())
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private static long identityCount(SocialIdentity identity) {
    return jdbc.sql("SELECT count(*) FROM user_identity WHERE provider = :p AND subject = :s")
        .param("p", identity.provider())
        .param("s", identity.subject())
        .query(Long.class)
        .single();
  }

  /** 열 이름은 이 파일 안의 상수로만 들어온다. */
  private static String identityColumn(SocialIdentity identity, String column) {
    return jdbc.sql("SELECT " + column + " FROM user_identity WHERE provider = :p AND subject = :s")
        .param("p", identity.provider())
        .param("s", identity.subject())
        .query(String.class)
        .single();
  }

  /** 표 이름은 이 파일 안의 상수로만 들어온다. */
  private static long count(String table, UUID userId) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = CAST(:id AS UUID)")
        .param("id", userId.toString())
        .query(Long.class)
        .single();
  }

  private static List<Long> savedPlaces(UUID userId) {
    return jdbc.sql("SELECT place_id FROM saved_place WHERE user_id = CAST(:id AS UUID)")
        .param("id", userId.toString())
        .query(Long.class)
        .list();
  }

  private static Long sourceContentOf(UUID userId, long placeId) {
    return jdbc.sql(
            "SELECT source_content_id FROM saved_place"
                + " WHERE user_id = CAST(:id AS UUID) AND place_id = :p")
        .param("id", userId.toString())
        .param("p", placeId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private static List<Long> savedContents(UUID userId) {
    return jdbc.sql("SELECT content_id FROM saved_content WHERE user_id = CAST(:id AS UUID)")
        .param("id", userId.toString())
        .query(Long.class)
        .list();
  }

  private static void savePlace(UUID userId, long placeId, Long sourceContentId) {
    jdbc.sql(
            "INSERT INTO saved_place (user_id, place_id, source_content_id)"
                + " VALUES (CAST(:u AS UUID), :p, CAST(:c AS BIGINT))")
        .param("u", userId.toString())
        .param("p", placeId)
        .param("c", sourceContentId)
        .update();
  }

  private static void saveContent(UUID userId, long contentId) {
    jdbc.sql("INSERT INTO saved_content (user_id, content_id) VALUES (CAST(:u AS UUID), :c)")
        .param("u", userId.toString())
        .param("c", contentId)
        .update();
  }

  private static long insertCourse(
      UUID userId, String status, Integer currentDay, int dayCount, long... placeIds) {
    long courseId =
        jdbc.sql(
                """
                INSERT INTO course (user_id, title, day_count, status, current_day_no, origin)
                VALUES (CAST(:u AS UUID), '합치기 시험 코스', :d, :s, CAST(:cur AS INT), 'self')
                RETURNING id
                """)
            .param("u", userId.toString())
            .param("d", dayCount)
            .param("s", status)
            .param("cur", currentDay)
            .query(Long.class)
            .single();
    for (int i = 0; i < placeIds.length; i++) {
      jdbc.sql(
              "INSERT INTO course_item (course_id, day_no, place_id, sort_order)"
                  + " VALUES (:c, 1, :p, :o)")
          .param("c", courseId)
          .param("p", placeIds[i])
          .param("o", (i + 1) * 10)
          .update();
    }
    return courseId;
  }

  private static UUID courseOwner(long courseId) {
    return jdbc.sql("SELECT user_id FROM course WHERE id = :c")
        .param("c", courseId)
        .query(UUID.class)
        .single();
  }

  private static String courseStatus(long courseId) {
    return jdbc.sql("SELECT status FROM course WHERE id = :c")
        .param("c", courseId)
        .query(String.class)
        .single();
  }

  private static Integer currentDay(long courseId) {
    return jdbc.sql("SELECT current_day_no FROM course WHERE id = :c")
        .param("c", courseId)
        .query(Integer.class)
        .optional()
        .orElse(null);
  }

  private static long courseItemCount(long courseId) {
    return jdbc.sql("SELECT count(*) FROM course_item WHERE course_id = :c")
        .param("c", courseId)
        .query(Long.class)
        .single();
  }

  private static long activeCourseCount(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM course WHERE user_id = CAST(:id AS UUID) AND status = 'active'")
        .param("id", userId.toString())
        .query(Long.class)
        .single();
  }
}
