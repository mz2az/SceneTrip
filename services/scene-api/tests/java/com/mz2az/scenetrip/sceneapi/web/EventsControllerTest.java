package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.popularity.UsageEventStore;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 사용 기록 {@code POST /events}(계약 1.11.0 {@code recordEvent}, 계획 {@code popularity.md} §7)의 HTTP 계층.
 * DB 없이 돈다.
 *
 * <p>명세가 약속한 것만 본다 — 모양이 맞으면 204 몸체 없음, 모양이 틀리면 400 {@code INVALID_PARAMETER}, 적다가 실패해도 204, 토큰이
 * 있으면 가입 사용자로·없으면 비회원으로, 언어는 {@code Accept-Language} 로.
 */
@WebMvcTest(EventsController.class)
@Import(LanguageConfiguration.class)
@DisplayName("POST /events — 사용 기록의 HTTP 계층")
class EventsControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰 검증을 CurrentAccount 목으로 대신한다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID INSTALL = UUID.fromString(INSTALL_ID);
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private UsageEventStore store;

  @MockitoBean private CurrentAccount accounts;

  private ResultActions send(String body) throws Exception {
    return mvc.perform(
        post("/events")
            .header("X-Install-Id", INSTALL_ID)
            .header("Accept-Language", "ko")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String repeat(char c, int n) {
    return String.valueOf(c).repeat(n);
  }

  @Nested
  @DisplayName("모양이 맞으면 204, 몸체 없음")
  class Accepted {

    @Test
    @DisplayName("view · content 는 204 이고 몸체가 없다 — 대상·기기·언어를 적는다")
    void viewContent() throws Exception {
      send("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}")
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));

      verify(store)
          .recordView(eq(UsageEventStore.Entity.CONTENT), eq(12L), eq(INSTALL), isNull(), eq("ko"));
      verify(store, never()).recordSearch(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("view · place 는 촬영지로 적는다")
    void viewPlace() throws Exception {
      send("{\"type\":\"view\",\"entityType\":\"place\",\"entityId\":7}")
          .andExpect(status().isNoContent());

      verify(store)
          .recordView(eq(UsageEventStore.Entity.PLACE), eq(7L), eq(INSTALL), isNull(), eq("ko"));
    }

    @Test
    @DisplayName("view · poi 는 편의시설로 적는다")
    void viewPoi() throws Exception {
      send("{\"type\":\"view\",\"entityType\":\"poi\",\"entityId\":900001}")
          .andExpect(status().isNoContent());

      verify(store)
          .recordView(eq(UsageEventStore.Entity.POI), eq(900001L), eq(INSTALL), isNull(), eq("ko"));
    }

    @Test
    @DisplayName("search 는 204 — 검색어와 결과 수를 적는다")
    void search() throws Exception {
      send("{\"type\":\"search\",\"query\":\"도깨비\",\"resultCount\":3}")
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));

      verify(store).recordSearch("도깨비", 3, "ko");
      verify(store, never()).recordView(any(), anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("search 의 앞뒤 공백은 서버가 지운다")
    void searchIsTrimmed() throws Exception {
      send("{\"type\":\"search\",\"query\":\"  도깨비 촬영지 \",\"resultCount\":1}")
          .andExpect(status().isNoContent());

      verify(store).recordSearch("도깨비 촬영지", 1, "ko");
    }

    @Test
    @DisplayName("결과 0 건도 받는다 — 「찾는 것이 없었다」")
    void zeroResults() throws Exception {
      send("{\"type\":\"search\",\"query\":\"없는작품\",\"resultCount\":0}")
          .andExpect(status().isNoContent());

      verify(store).recordSearch("없는작품", 0, "ko");
    }

    @Test
    @DisplayName("검색어는 100 자까지 받는다")
    void hundredCharacterQuery() throws Exception {
      String query = repeat('가', 100);
      send("{\"type\":\"search\",\"query\":\"" + query + "\",\"resultCount\":2}")
          .andExpect(status().isNoContent());

      verify(store).recordSearch(query, 2, "ko");
    }

    @Test
    @DisplayName("view 에 search 칸을 함께 보내도 무시한다 — 다른 쪽 칸은 비운다(보내도 무시)")
    void viewIgnoresSearchFields() throws Exception {
      send("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":5,\"query\":\"x\",\"resultCount\":1}")
          .andExpect(status().isNoContent());

      verify(store)
          .recordView(eq(UsageEventStore.Entity.CONTENT), eq(5L), eq(INSTALL), isNull(), eq("ko"));
      verify(store, never()).recordSearch(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("search 에 view 칸을 함께 보내도 무시한다")
    void searchIgnoresViewFields() throws Exception {
      send("{\"type\":\"search\",\"query\":\"남산\",\"resultCount\":4,\"entityType\":\"place\",\"entityId\":3}")
          .andExpect(status().isNoContent());

      verify(store).recordSearch("남산", 4, "ko");
      verify(store, never()).recordView(any(), anyLong(), any(), any(), anyString());
    }
  }

  @Nested
  @DisplayName("모양이 틀리면 400 INVALID_PARAMETER — 앱의 버그")
  class Rejected {

    private void rejected(String body) throws Exception {
      send(body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
      verifyNoInteractions(store);
    }

    @Test
    @DisplayName("view 인데 entityType 이 없다")
    void viewWithoutEntityType() throws Exception {
      rejected("{\"type\":\"view\",\"entityId\":12}");
    }

    @Test
    @DisplayName("view 인데 entityId 가 없다")
    void viewWithoutEntityId() throws Exception {
      rejected("{\"type\":\"view\",\"entityType\":\"content\"}");
    }

    @Test
    @DisplayName("view 인데 entityType·entityId 가 null 이다")
    void viewWithNulls() throws Exception {
      rejected("{\"type\":\"view\",\"entityType\":null,\"entityId\":null}");
    }

    @Test
    @DisplayName("entityId 가 0 이다 — 범위(1 이상)를 넘었다")
    void entityIdZero() throws Exception {
      rejected("{\"type\":\"view\",\"entityType\":\"place\",\"entityId\":0}");
    }

    @Test
    @DisplayName("entityId 가 음수다 — 범위(1 이상)를 넘었다")
    void entityIdNegative() throws Exception {
      rejected("{\"type\":\"view\",\"entityType\":\"place\",\"entityId\":-1}");
    }

    @Test
    @DisplayName("entityType 이 목록 밖이다")
    void unknownEntityType() throws Exception {
      rejected("{\"type\":\"view\",\"entityType\":\"course\",\"entityId\":1}");
    }

    @Test
    @DisplayName("search 인데 query 가 없다")
    void searchWithoutQuery() throws Exception {
      rejected("{\"type\":\"search\",\"resultCount\":3}");
    }

    @Test
    @DisplayName("search 인데 resultCount 가 없다")
    void searchWithoutResultCount() throws Exception {
      rejected("{\"type\":\"search\",\"query\":\"도깨비\"}");
    }

    @Test
    @DisplayName("search 의 query 가 빈 문자열이다 — 길이 1 미만")
    void emptyQuery() throws Exception {
      rejected("{\"type\":\"search\",\"query\":\"\",\"resultCount\":3}");
    }

    @Test
    @DisplayName("search 의 query 가 공백뿐이다 — 공백을 지우면 비어 있다")
    void blankQuery() throws Exception {
      rejected("{\"type\":\"search\",\"query\":\"   \",\"resultCount\":3}");
    }

    @Test
    @DisplayName("search 의 query 가 100 자를 넘었다")
    void tooLongQuery() throws Exception {
      rejected("{\"type\":\"search\",\"query\":\"" + repeat('가', 101) + "\",\"resultCount\":3}");
    }

    @Test
    @DisplayName("resultCount 가 음수다 — 범위를 넘었다")
    void negativeResultCount() throws Exception {
      rejected("{\"type\":\"search\",\"query\":\"도깨비\",\"resultCount\":-1}");
    }

    @Test
    @DisplayName("type 이 없다")
    void missingType() throws Exception {
      rejected("{\"entityType\":\"content\",\"entityId\":12}");
    }

    @Test
    @DisplayName("type 이 목록 밖이다")
    void unknownType() throws Exception {
      rejected("{\"type\":\"click\",\"entityType\":\"content\",\"entityId\":12}");
    }

    @Test
    @DisplayName("몸체가 JSON 이 아니다")
    void malformedJson() throws Exception {
      rejected("{\"type\":\"view\",");
    }

    @Test
    @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID — 필수 헤더")
    void missingInstallId() throws Exception {
      mvc.perform(
              post("/events")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
      verifyNoInteractions(store);
    }

    @Test
    @DisplayName("X-Install-Id 가 UUID 가 아니면 400 MISSING_INSTALL_ID")
    void malformedInstallId() throws Exception {
      mvc.perform(
              post("/events")
                  .header("X-Install-Id", "not-a-uuid")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
      verifyNoInteractions(store);
    }
  }

  @Nested
  @DisplayName("기록에 실패해도 204 — 보내고 잊는다")
  class StorageFailure {

    @Test
    @DisplayName("DB 에 닿지 못해도 view 는 204")
    void viewConnectionFailure() throws Exception {
      when(store.recordView(any(), anyLong(), any(), any(), anyString()))
          .thenThrow(new CannotGetJdbcConnectionException("DB 가 내려갔다"));

      send("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}")
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));
    }

    @Test
    @DisplayName("제약 위반이 나도 view 는 204")
    void viewConstraintFailure() throws Exception {
      when(store.recordView(any(), anyLong(), any(), any(), anyString()))
          .thenThrow(new DataIntegrityViolationException("제약 위반"));

      send("{\"type\":\"view\",\"entityType\":\"place\",\"entityId\":3}")
          .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("시간이 다 돼도 search 는 204")
    void searchTimeout() throws Exception {
      doThrow(new QueryTimeoutException("느리다"))
          .when(store)
          .recordSearch(anyString(), anyInt(), anyString());

      send("{\"type\":\"search\",\"query\":\"도깨비\",\"resultCount\":3}")
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));
    }

    @Test
    @DisplayName("잠금을 못 잡아도 search 는 204")
    void searchLockFailure() throws Exception {
      doThrow(new CannotAcquireLockException("잠금"))
          .when(store)
          .recordSearch(anyString(), anyInt(), anyString());

      send("{\"type\":\"search\",\"query\":\"도깨비\",\"resultCount\":3}")
          .andExpect(status().isNoContent());
    }
  }

  @Nested
  @DisplayName("비회원도 보낸다 — 토큰이 있으면 가입 사용자로 적는다")
  class Account {

    @Test
    @DisplayName("토큰이 있으면 view 를 그 가입 사용자로 적는다")
    void signedInViewCarriesUser() throws Exception {
      when(accounts.signedInOrNull()).thenReturn(USER);

      mvc.perform(
              post("/events")
                  .header("X-Install-Id", INSTALL_ID)
                  .header("Authorization", "Bearer token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}"))
          .andExpect(status().isNoContent());

      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, USER, "en");
    }

    @Test
    @DisplayName("토큰이 없으면 비회원 — 사용자 없이 기기로만 적는다")
    void guestViewHasNoUser() throws Exception {
      when(accounts.signedInOrNull()).thenReturn(null);

      send("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}")
          .andExpect(status().isNoContent());

      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "ko");
    }

    @Test
    @DisplayName("가입 사용자의 search 도 기기·사용자 없이 적는다")
    void signedInSearchIsNotLinked() throws Exception {
      when(accounts.signedInOrNull()).thenReturn(USER);

      send("{\"type\":\"search\",\"query\":\"도깨비\",\"resultCount\":3}")
          .andExpect(status().isNoContent());

      verify(store).recordSearch("도깨비", 3, "ko");
    }
  }

  @Nested
  @DisplayName("언어는 Accept-Language 로 적는다")
  class Language {

    private ResultActions viewWith(String acceptLanguage) throws Exception {
      var request =
          post("/events")
              .header("X-Install-Id", INSTALL_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"type\":\"view\",\"entityType\":\"content\",\"entityId\":12}");
      if (acceptLanguage != null) {
        request.header("Accept-Language", acceptLanguage);
      }
      return mvc.perform(request);
    }

    @Test
    @DisplayName("ko 는 ko")
    void korean() throws Exception {
      viewWith("ko").andExpect(status().isNoContent());
      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "ko");
    }

    @Test
    @DisplayName("zh-Hant 는 zh-Hant")
    void traditionalChinese() throws Exception {
      viewWith("zh-Hant").andExpect(status().isNoContent());
      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "zh-Hant");
    }

    @Test
    @DisplayName("ja 는 ja")
    void japanese() throws Exception {
      viewWith("ja").andExpect(status().isNoContent());
      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "ja");
    }

    @Test
    @DisplayName("생략하면 en")
    void missingIsEnglish() throws Exception {
      viewWith(null).andExpect(status().isNoContent());
      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "en");
    }

    @Test
    @DisplayName("지원하지 않는 값이면 en")
    void unsupportedIsEnglish() throws Exception {
      viewWith("fr").andExpect(status().isNoContent());
      verify(store).recordView(UsageEventStore.Entity.CONTENT, 12L, INSTALL, null, "en");
    }

    @Test
    @DisplayName("search 도 언어를 적는다")
    void searchCarriesLanguage() throws Exception {
      mvc.perform(
              post("/events")
                  .header("X-Install-Id", INSTALL_ID)
                  .header("Accept-Language", "ja")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"type\":\"search\",\"query\":\"トッケビ\",\"resultCount\":1}"))
          .andExpect(status().isNoContent());

      verify(store).recordSearch("トッケビ", 1, "ja");
    }
  }
}
