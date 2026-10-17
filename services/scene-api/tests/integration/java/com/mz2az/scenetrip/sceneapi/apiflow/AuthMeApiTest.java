package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 로그인·내 정보 — 토큰이 회원 창구({@code /me}, {@code /me/nickname})로 이어지는지, 닉네임 중복이 DB 제약에서 409 로 나오는지 (계약
 * 「인증」·{@code setMyNickname}, 계획 §2).
 */
@DisplayName("API 통합 — 로그인·내 정보")
class AuthMeApiTest extends ApiIntegrationTest {

  @Test
  @DisplayName("회원 토큰으로 GET /me — 그 계정의 id·닉네임")
  void meWithMemberToken() throws Exception {
    UUID me = member("TEST_me" + tag.substring(0, 6));

    mvc.perform(get("/me").header("Authorization", bearer(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(me.toString()))
        .andExpect(jsonPath("$.nickname").value("TEST_me" + tag.substring(0, 6)))
        .andExpect(jsonPath("$.nicknameConfirmed").value(true))
        .andExpect(jsonPath("$.identities").isArray())
        .andExpect(jsonPath("$.registeredAt").exists());
  }

  @Test
  @DisplayName("깨진 토큰은 401 ACCESS_TOKEN_INVALID")
  void brokenTokenIsInvalid() throws Exception {
    mvc.perform(get("/me").header("Authorization", "Bearer TEST-not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
  }

  @Test
  @DisplayName("가입한 계정의 설치 UUID 만 오면 401 SESSION_REQUIRED — 토큰을 주면 같은 설치본으로 열린다")
  void registeredAccountNeedsToken() throws Exception {
    UUID me = member("TEST_se" + tag.substring(0, 6));
    UUID install = UUID.randomUUID();
    jdbc.sql("INSERT INTO user_device (install_uuid, user_id) VALUES (:i, :u)")
        .param("i", install)
        .param("u", me)
        .update();

    mvc.perform(get("/cart").header("X-Install-Id", install.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    mvc.perform(
            get("/cart")
                .header("X-Install-Id", install.toString())
                .header("Authorization", bearer(me)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName(
      "닉네임을 정하면 앞뒤 공백을 떼고 nicknameConfirmed=true — 남이 쓰는 닉네임은 대소문자가 달라도 409 NICKNAME_TAKEN")
  void nicknameSetAndTaken() throws Exception {
    String taken = "TEST_tk" + tag.substring(0, 6);
    member(taken);
    UUID me = member("TEST_mine" + tag.substring(0, 6));
    jdbc.sql("UPDATE app_user SET nickname_confirmed = false WHERE id = :u")
        .param("u", me)
        .update();
    String wanted = "TEST_new" + tag.substring(0, 6);

    mvc.perform(nickname(me, "  " + wanted + "  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nickname").value(wanted))
        .andExpect(jsonPath("$.nicknameConfirmed").value(true));

    mvc.perform(nickname(me, taken.toLowerCase()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
  }

  @Test
  @DisplayName("규칙에 맞지 않는 닉네임은 400 NICKNAME_INVALID")
  void invalidNickname() throws Exception {
    UUID me = member("TEST_iv" + tag.substring(0, 6));

    mvc.perform(nickname(me, "a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NICKNAME_INVALID"));
    mvc.perform(nickname(me, "TEST-하이픈"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NICKNAME_INVALID"));
  }

  private org.springframework.test.web.servlet.RequestBuilder nickname(UUID user, String value)
      throws Exception {
    return put("/me/nickname")
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(java.util.Map.of("nickname", value)));
  }
}
