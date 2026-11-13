package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.moderation.BlockStore;
import com.mz2az.scenetrip.sceneapi.moderation.OperatorDirectory;
import com.mz2az.scenetrip.sceneapi.moderation.RemovalStore;
import com.mz2az.scenetrip.sceneapi.moderation.ReportNotifier;
import com.mz2az.scenetrip.sceneapi.moderation.ReportStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 신고·차단 창구를 HTTP 로 본다 — 계약 1.19.0 tag {@code moderation}, 계획 {@code
 * community-moderation.md}(MZ2AZ-438).
 *
 * <p>여기서 보는 것은 <b>요청 모양이 계약을 어겼을 때의 400</b>(스키마 검사는 스프링 MVC 가 하므로 컨트롤러를 손으로 부르는 통합 레인에서는 안 보인다), 차단
 * 창구의 로그인 요구(401), 그리고 신고 알림 로그 「[신고] 접수」(SigNoz 알람 규칙이 이 글자를 찾는다, 계획 §3). Store 는 가짜이고 SQL·오류 코드
 * 흐름은 통합 레인({@code ModerationFlowIntegrationTest})이 본다.
 */
@WebMvcTest(ModerationController.class)
@Import({LanguageConfiguration.class, CurrentAccount.class, PostsControllerTest.Tokens.class})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ModerationController — 신고·차단 요청 모양·로그인·알림 로그")
class ModerationControllerTest {

  private static final UUID USER = UUID.fromString("2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d");
  private static final String INSTALL_ID = "7e8f9a0b-1c2d-4e3f-8a5b-6c7d8e9f0a1b";
  private static final UUID GUEST = UUID.fromString("5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e");

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens tokens;

  @MockitoBean private ReportStore reports;
  @MockitoBean private BlockStore blocks;
  @MockitoBean private UserStore users;
  @MockitoBean private RemovalStore removals;
  @MockitoBean private OperatorDirectory operators;
  @MockitoBean private ReportNotifier notifier;

  @BeforeEach
  void setup() {
    when(users.touchSignedIn(USER)).thenReturn(true);
    when(users.isRegistered(USER)).thenReturn(true);
    when(users.lookup(UUID.fromString(INSTALL_ID))).thenReturn(new UserStore.Account(GUEST, false));
    when(users.isRegistered(GUEST)).thenReturn(false);
    when(users.resolve(UUID.fromString(INSTALL_ID))).thenReturn(GUEST);
    when(reports.report(any(), any(), anyLong(), any(), any()))
        .thenReturn(ReportStore.Outcome.RECORDED);
    when(removals.remove(any(), any(), anyLong())).thenReturn(RemovalStore.Outcome.REMOVED);
  }

  // ───────────── 신고: 요청 모양 ─────────────

  @Test
  @DisplayName("비회원이 X-Install-Id 로 신고하면 204 — 신고한 사람은 설치 id 로 찾은 계정, 이유는 계약 값 그대로")
  void guestReportIs204() throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content(
                    "{\"targetType\":\"review\",\"targetId\":42,\"reason\":\"personal_info\","
                        + "\"detail\":\"전화번호가 있어요\"}"))
        .andExpect(status().isNoContent());
    verify(reports)
        .report(
            eq(GUEST),
            eq(ReportStore.Target.REVIEW),
            eq(42L),
            eq("personal_info"),
            eq("전화번호가 있어요"));
  }

  @Test
  @DisplayName("토큰이 있으면 토큰의 계정이 신고한 사람이다 — 여행후기 신고, detail 없이도 204")
  void memberReportUsesToken() throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .header("Authorization", bearer())
                .content("{\"targetType\":\"post\",\"targetId\":7,\"reason\":\"spam\"}"))
        .andExpect(status().isNoContent());
    verify(reports).report(eq(USER), eq(ReportStore.Target.POST), eq(7L), eq("spam"), isNull());
  }

  @Test
  @DisplayName("이미 신고한 글이어도 204 — 한 번으로 센다")
  void duplicateReportIs204() throws Exception {
    when(reports.report(any(), any(), anyLong(), any(), any()))
        .thenReturn(ReportStore.Outcome.ALREADY);
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Store 가 자기 글·없는 글이라 하면 400 CANNOT_REPORT_OWN · 404 REPORT_TARGET_NOT_FOUND")
  void storeOutcomesMapToCodes() throws Exception {
    when(reports.report(any(), any(), anyLong(), any(), any())).thenReturn(ReportStore.Outcome.OWN);
    mvc.perform(
            report()
                .header("Authorization", bearer())
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN"));

    when(reports.report(any(), any(), anyLong(), any(), any()))
        .thenReturn(ReportStore.Outcome.NOT_FOUND);
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"post\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_TARGET_NOT_FOUND"));
  }

  @Test
  @DisplayName("계약을 어긴 신고는 400 INVALID_PARAMETER 이고 Store 를 부르지 않는다 — 없는 이유·없는 종류·빠진 칸·501 자 설명")
  void malformedReportsAre400() throws Exception {
    String[] bodies = {
      "{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"rude\"}",
      "{\"targetType\":\"user\",\"targetId\":1,\"reason\":\"abuse\"}",
      "{\"targetType\":\"review\",\"targetId\":1}",
      "{\"targetType\":\"review\",\"reason\":\"abuse\"}",
      "{\"targetId\":1,\"reason\":\"abuse\"}",
      "{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"other\",\"detail\":\""
          + "가".repeat(501)
          + "\"}",
    };
    for (String body : bodies) {
      mvc.perform(report().header("X-Install-Id", INSTALL_ID).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    verify(reports, never()).report(any(), any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("설명은 500 자까지 받는다")
  void detailOf500IsAccepted() throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content(
                    "{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"other\",\"detail\":\""
                        + "가".repeat(500)
                        + "\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("토큰도 X-Install-Id 도 없으면 400 MISSING_INSTALL_ID — 헤더가 필수인 창구다")
  void reportWithoutAnyIdentityIs400() throws Exception {
    mvc.perform(report().content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
    verify(reports, never()).report(any(), any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("가입 계정의 설치 id 로 토큰 없이 신고하면 401 SESSION_REQUIRED, 위조 토큰은 401 — Store 를 부르지 않는다")
  void reportUnauthorized() throws Exception {
    String memberInstall = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    when(users.lookup(UUID.fromString(memberInstall)))
        .thenReturn(new UserStore.Account(USER, true));
    mvc.perform(
            report()
                .header("X-Install-Id", memberInstall)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .header("Authorization", "Bearer not-a-token")
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isUnauthorized());
    verify(reports, never()).report(any(), any(), anyLong(), any(), any());
  }

  // ───────────── 신고: 알림 로그 ─────────────

  @Test
  @DisplayName("새 신고는 「[신고] 접수」 가 든 로그를 남긴다 — SigNoz 알람이 이 글자를 찾는다")
  void newReportLogsAlertLine(CapturedOutput output) throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":4242,\"reason\":\"sexual\"}"))
        .andExpect(status().isNoContent());
    assertThat(output.getAll()).contains("[신고] 접수");
  }

  @Test
  @DisplayName("거절된 신고(자기 글)는 알림 로그를 남기지 않는다")
  void rejectedReportDoesNotAlert(CapturedOutput output) throws Exception {
    when(reports.report(any(), any(), anyLong(), any(), any())).thenReturn(ReportStore.Outcome.OWN);
    mvc.perform(
            report()
                .header("Authorization", bearer())
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isBadRequest());
    assertThat(output.getAll()).doesNotContain("[신고] 접수");
  }

  // ───────────── 신고: Slack 알림(MZ2AZ-569 §3) ─────────────

  @Test
  @DisplayName("새 신고(RECORDED)는 ReportNotifier 에 넘긴다 — 종류·번호·이유·설명 그대로, 「[신고] 접수」 로그도 그대로 남는다")
  void newReportNotifies(CapturedOutput output) throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content(
                    "{\"targetType\":\"post\",\"targetId\":26,\"reason\":\"spam\","
                        + "\"detail\":\"광고예요\"}"))
        .andExpect(status().isNoContent());
    verify(notifier).reportRecorded(ReportStore.Target.POST, 26L, "spam", "광고예요");
    assertThat(output.getAll()).contains("[신고] 접수");
  }

  @Test
  @DisplayName("설명 없는 새 신고는 설명 null 로 넘긴다")
  void newReportWithoutDetailNotifiesWithNull() throws Exception {
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"comment\",\"targetId\":501,\"reason\":\"abuse\"}"))
        .andExpect(status().isNoContent());
    verify(notifier)
        .reportRecorded(eq(ReportStore.Target.COMMENT), eq(501L), eq("abuse"), isNull());
  }

  @Test
  @DisplayName("이미 신고한 글(ALREADY)·자기 글(OWN)·없는 글(NOT_FOUND)은 알림을 보내지 않는다")
  void onlyRecordedNotifies() throws Exception {
    when(reports.report(any(), any(), anyLong(), any(), any()))
        .thenReturn(ReportStore.Outcome.ALREADY);
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isNoContent());

    when(reports.report(any(), any(), anyLong(), any(), any())).thenReturn(ReportStore.Outcome.OWN);
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isBadRequest());

    when(reports.report(any(), any(), anyLong(), any(), any()))
        .thenReturn(ReportStore.Outcome.NOT_FOUND);
    mvc.perform(
            report()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"review\",\"targetId\":1,\"reason\":\"abuse\"}"))
        .andExpect(status().isNotFound());

    verifyNoInteractions(notifier);
  }

  // ───────────── 운영자 내리기: POST /moderation/removals (1.30.0, MZ2AZ-569 §2) ─────────────

  @Test
  @DisplayName("토큰이 없으면 401 SIGN_IN_REQUIRED — 설치 id 만으로는 안 되고 운영자 판정·Store 를 부르지 않는다")
  void removalNeedsSignIn() throws Exception {
    mvc.perform(removal().content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(
            removal()
                .header("X-Install-Id", INSTALL_ID)
                .content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    verifyNoInteractions(removals);
  }

  @Test
  @DisplayName("위조 토큰은 401 이고 Store 를 부르지 않는다")
  void removalWithBrokenTokenIs401() throws Exception {
    mvc.perform(
            removal()
                .header("Authorization", "Bearer not-a-token")
                .content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(removals);
  }

  @Test
  @DisplayName("운영자가 아닌 회원은 403 OPERATOR_ONLY 이고 Store 를 부르지 않는다")
  void nonOperatorIs403() throws Exception {
    when(operators.isOperator(USER)).thenReturn(false);
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OPERATOR_ONLY"));
    verifyNoInteractions(removals);
  }

  @Test
  @DisplayName("운영자가 내리면 204 — Store 에 토큰의 계정·종류·번호를 넘기고 「[운영자] 내림」 로그를 남긴다")
  void operatorRemovesPost(CapturedOutput output) throws Exception {
    when(operators.isOperator(USER)).thenReturn(true);
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isNoContent());
    verify(removals).remove(USER, ReportStore.Target.POST, 26L);
    assertThat(output.getAll()).contains("[운영자] 내림");
  }

  @Test
  @DisplayName("종류는 계약 값대로 옮긴다 — review·comment")
  void removalTargetTypesMap() throws Exception {
    when(operators.isOperator(USER)).thenReturn(true);
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"review\",\"targetId\":9}"))
        .andExpect(status().isNoContent());
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"comment\",\"targetId\":501}"))
        .andExpect(status().isNoContent());
    verify(removals).remove(USER, ReportStore.Target.REVIEW, 9L);
    verify(removals).remove(USER, ReportStore.Target.COMMENT, 501L);
  }

  @Test
  @DisplayName("이미 내려 있으면(ALREADY) 204 — 「[운영자] 내림」 로그는 남기지 않는다")
  void alreadyRemovedIs204WithoutLog(CapturedOutput output) throws Exception {
    when(operators.isOperator(USER)).thenReturn(true);
    when(removals.remove(any(), any(), anyLong())).thenReturn(RemovalStore.Outcome.ALREADY);
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"post\",\"targetId\":26}"))
        .andExpect(status().isNoContent());
    assertThat(output.getAll()).doesNotContain("[운영자] 내림");
  }

  @Test
  @DisplayName("대상이 없으면(NOT_FOUND) 404 REMOVAL_TARGET_NOT_FOUND — 로그 없음")
  void missingTargetIs404(CapturedOutput output) throws Exception {
    when(operators.isOperator(USER)).thenReturn(true);
    when(removals.remove(any(), any(), anyLong())).thenReturn(RemovalStore.Outcome.NOT_FOUND);
    mvc.perform(
            removal()
                .header("Authorization", bearer())
                .content("{\"targetType\":\"comment\",\"targetId\":999999}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REMOVAL_TARGET_NOT_FOUND"));
    assertThat(output.getAll()).doesNotContain("[운영자] 내림");
  }

  @Test
  @DisplayName("계약을 어긴 요청은 400 INVALID_PARAMETER 이고 Store 를 부르지 않는다 — 없는 종류·빠진 칸·번호 0")
  void malformedRemovalsAre400() throws Exception {
    when(operators.isOperator(USER)).thenReturn(true);
    for (String body :
        new String[] {
          "{\"targetType\":\"user\",\"targetId\":1}",
          "{\"targetType\":\"post\"}",
          "{\"targetId\":1}",
          "{\"targetType\":\"post\",\"targetId\":0}"
        }) {
      mvc.perform(removal().header("Authorization", bearer()).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    verifyNoInteractions(removals);
  }

  // ───────────── 차단: 로그인 ─────────────

  @Test
  @DisplayName("차단 세 창구는 토큰이 없으면 401 이고 Store 를 부르지 않는다 — 설치 id 만으로는 안 된다")
  void blocksNeedSignIn() throws Exception {
    mvc.perform(
            post("/me/blocks")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"review\",\"targetId\":1}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/me/blocks").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/me/blocks/1").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(blocks);
  }

  @Test
  @DisplayName("계약을 어긴 차단 요청은 400 INVALID_PARAMETER — 없는 종류·빠진 번호")
  void malformedBlocksAre400() throws Exception {
    for (String body :
        new String[] {
          "{\"targetType\":\"user\",\"targetId\":1}",
          "{\"targetType\":\"review\"}",
          "{\"targetId\":1}"
        }) {
      mvc.perform(
              post("/me/blocks")
                  .header("Authorization", bearer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    verifyNoInteractions(blocks);
  }

  @Test
  @DisplayName("풀 번호가 숫자가 아니면 400 INVALID_PARAMETER")
  void nonNumericBlockIdIs400() throws Exception {
    mvc.perform(delete("/me/blocks/abc").header("Authorization", bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verifyNoInteractions(blocks);
  }

  private static MockHttpServletRequestBuilder removal() {
    return post("/moderation/removals").contentType(MediaType.APPLICATION_JSON);
  }

  private static MockHttpServletRequestBuilder report() {
    return post("/reports").contentType(MediaType.APPLICATION_JSON);
  }

  private String bearer() {
    return "Bearer " + tokens.issue(USER).value();
  }
}
