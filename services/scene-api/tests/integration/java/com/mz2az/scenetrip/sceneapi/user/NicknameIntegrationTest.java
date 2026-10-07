package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.user.UserStore.NicknameResult;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 닉네임(V21, 계약 1.4.0 {@code Me.nickname} · {@code PUT /me/nickname}, 계획 §12)을 진짜 PostgreSQL 에 태운다.
 *
 * <p>가입하면 서버가 「여행자 + 번호」 를 붙이고({@code nickname_confirmed = false}), 사용자가 정하면 {@code true}. 겹침은 영문
 * 대소문자를 가리지 않는다 — 그 판정이 DB 의 {@code lower(nickname)} 유일 색인에 있어 단위 레인에서는 보이지 않는다.
 *
 * <p>닉네임은 공유 DB 에서 겹치지 않도록 시험마다 무작위로 만든다. 만든 계정은 끝나면 지운다.
 */
@DisplayName("닉네임 — 자동 부여·정하기·대소문자 무시 유일 (실제 DB)")
class NicknameIntegrationTest {

  private static JdbcClient jdbc;
  private static UserStore users;
  private static AccountLinkStore links;

  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  @Test
  @DisplayName("가입하면 「여행자 + 숫자」 닉네임이 붙고 nicknameConfirmed=false — profile 이 둘을 돌려준다")
  void registrationAssignsAutoNickname() {
    UUID me = member();

    Profile profile = users.profile(me).orElseThrow();

    assertThat(profile.nickname()).matches("^여행자[0-9]+$");
    assertThat(profile.nicknameConfirmed()).isFalse();
  }

  @Test
  @DisplayName("자동 닉네임은 가입자마다 다르다")
  void autoNicknamesDiffer() {
    UUID a = member();
    UUID b = member();

    assertThat(users.profile(a).orElseThrow().nickname())
        .isNotEqualTo(users.profile(b).orElseThrow().nickname());
  }

  @Test
  @DisplayName("비회원 계정에는 닉네임이 없다")
  void guestHasNoNickname() {
    UUID guest = guest();

    assertThat(nicknameColumn(guest)).isNull();
    assertThat(users.profile(guest)).isEmpty();
  }

  @Test
  @DisplayName("setNickname — SET 이고 profile 의 nickname 이 바뀌며 nicknameConfirmed=true")
  void setNicknameConfirms() {
    UUID me = member();
    String name = randomNickname();

    assertThat(users.setNickname(me, name)).isEqualTo(NicknameResult.SET);

    Profile profile = users.profile(me).orElseThrow();
    assertThat(profile.nickname()).isEqualTo(name);
    assertThat(profile.nicknameConfirmed()).isTrue();
  }

  @Test
  @DisplayName("setNickname — 다른 사람이 같은 닉네임(대소문자만 다름 포함)을 쓰면 TAKEN, 내 닉네임은 그대로")
  void setNicknameTakenCaseInsensitive() {
    UUID owner = member();
    UUID other = member();
    String name = "Jeju" + suffix();
    users.setNickname(owner, name);
    String before = users.profile(other).orElseThrow().nickname();

    assertThat(users.setNickname(other, name)).isEqualTo(NicknameResult.TAKEN);
    assertThat(users.setNickname(other, name.toUpperCase())).isEqualTo(NicknameResult.TAKEN);
    assertThat(users.setNickname(other, name.toLowerCase())).isEqualTo(NicknameResult.TAKEN);

    Profile otherProfile = users.profile(other).orElseThrow();
    assertThat(otherProfile.nickname()).isEqualTo(before);
    assertThat(otherProfile.nicknameConfirmed()).isFalse();
    assertThat(users.profile(owner).orElseThrow().nickname()).isEqualTo(name);
  }

  @Test
  @DisplayName("setNickname — 내 닉네임을 대소문자만 바꿔 다시 정하는 것은 겹침이 아니다")
  void resetOwnNicknameWithDifferentCase() {
    UUID me = member();
    String name = "jeju" + suffix();
    users.setNickname(me, name);

    assertThat(users.setNickname(me, name.toUpperCase())).isEqualTo(NicknameResult.SET);
    assertThat(users.profile(me).orElseThrow().nickname()).isEqualTo(name.toUpperCase());
  }

  @Test
  @DisplayName("setNickname — 다른 사람의 자동 닉네임과 같으면 TAKEN")
  void autoNicknameIsAlsoTaken() {
    UUID a = member();
    UUID b = member();
    String auto = users.profile(a).orElseThrow().nickname();

    // 컨트롤러는 「여행자 + 숫자」 꼴을 400 으로 먼저 막지만, Store 의 유일 판정도 자동 닉네임을 본다.
    assertThat(users.setNickname(b, auto)).isEqualTo(NicknameResult.TAKEN);
  }

  @Test
  @DisplayName("setNickname — 비회원·없는 계정·합쳐진 계정이면 GONE 이고 아무것도 바꾸지 않는다")
  void setNicknameOnNonMemberIsGone() {
    UUID guest = guest();
    UUID survivor = member();
    UUID merged = member();
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:id AS UUID)")
        .param("to", survivor.toString())
        .param("id", merged.toString())
        .update();

    assertThat(users.setNickname(guest, randomNickname())).isEqualTo(NicknameResult.GONE);
    assertThat(users.setNickname(UUID.randomUUID(), randomNickname()))
        .isEqualTo(NicknameResult.GONE);
    assertThat(users.setNickname(merged, randomNickname())).isEqualTo(NicknameResult.GONE);
    assertThat(nicknameColumn(guest)).isNull();

    jdbc.sql("UPDATE app_user SET merged_into = NULL WHERE id = CAST(:id AS UUID)")
        .param("id", merged.toString())
        .update();
  }

  @Test
  @DisplayName("탈퇴하면 닉네임이 풀려 다른 사람이 쓸 수 있다")
  void deletedAccountReleasesNickname() {
    UUID leaver = member();
    UUID next = member();
    String name = randomNickname();
    users.setNickname(leaver, name);

    users.delete(leaver);

    assertThat(users.setNickname(next, name)).isEqualTo(NicknameResult.SET);
  }

  // ───────────── 도우미 ─────────────

  private UUID guest() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    return id;
  }

  private UUID member() {
    UUID id = guest();
    links.register(id, "google", "it-nick-" + UUID.randomUUID(), null, null);
    return id;
  }

  private static String nicknameColumn(UUID id) {
    return jdbc.sql("SELECT nickname FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static String randomNickname() {
    return "it" + suffix();
  }

  /** 영문·숫자 10 자 — 앞에 붙는 것과 합쳐 16 자 안. */
  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }
}
