package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.EntityType;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Suggestion;
import com.mz2az.scenetrip.sceneapi.search.SuggestionStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 자동완성의 HTTP 계층.
 *
 * <p>DB 없이 돈다. 검증하는 것은 <b>명세가 약속한 것이 실제로 지켜지는가</b>다 — 파라미터 제약이 400 을 내는지, 응답 모양이 맞는지, {@code
 * Content-Language} 가 붙는지. SQL 이 옳은지는 여기서 알 수 없고 실제 DB 로 확인한다.
 *
 * <p>{@link LanguageConfiguration} 을 명시적으로 들여오는 이유: {@code @WebMvcTest} 는 웹 계층 빈만 올리고 일반
 * {@code @Configuration} 은 건너뛴다. 이것이 빠지면 {@code Accept-Language} 변환기가 없어, 운영에서는 되는 요청이 테스트에서만 실패한다.
 */
@WebMvcTest(SearchController.class)
@Import(LanguageConfiguration.class)
class SearchControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private SuggestionStore store;

  private void givenSuggestions(Suggestion... items) {
    when(store.suggest(any(), any(), anyInt()))
        .thenReturn(new SuggestionStore.Result(List.of(items), true));
  }

  @Test
  @DisplayName("q 가 없으면 400")
  void missingQueryIsRejected() throws Exception {
    mvc.perform(get("/search/suggestions")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("q 가 명세의 최대 길이를 넘으면 400")
  void tooLongQueryIsRejected() throws Exception {
    mvc.perform(get("/search/suggestions").param("q", "가".repeat(101)))
        .andExpect(status().isBadRequest())
        // 오류 응답도 계약을 지켜야 한다. 정상 응답만 명세를 따르고 오류는 Spring 의
        // 기본 형식으로 나가는 것이 가장 흔한 계약 위반이다.
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("limit 이 명세의 상한을 넘으면 400")
  void tooLargeLimitIsRejected() throws Exception {
    givenSuggestions();
    mvc.perform(get("/search/suggestions").param("q", "도깨비").param("limit", "21"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("제안 목록과 Content-Language 를 돌려준다")
  void returnsSuggestions() throws Exception {
    givenSuggestions(
        new Suggestion(EntityType.CONTENT, 2L, "도깨비").matchedTerm("Goblin").subtitle("tvN · 2016"));

    mvc.perform(get("/search/suggestions").param("q", "gob").header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.items[0].type").value("content"))
        .andExpect(jsonPath("$.items[0].id").value(2))
        .andExpect(jsonPath("$.items[0].name").value("도깨비"))
        .andExpect(jsonPath("$.items[0].matchedTerm").value("Goblin"));
  }

  @Test
  @DisplayName("걸린 것이 없으면 오류가 아니라 빈 배열이다")
  void emptyResultIsNotAnError() throws Exception {
    when(store.suggest(any(), any(), anyInt()))
        .thenReturn(new SuggestionStore.Result(List.of(), false));

    mvc.perform(get("/search/suggestions").param("q", "없는말"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        // 요청한 언어로 나온 것이 하나도 없으면 ko 로 폴백했다고 알린다.
        .andExpect(header().string("Content-Language", "ko"));
  }

  @Test
  @DisplayName("브라우저가 보내는 Accept-Language 도 받는다")
  void acceptsRealWorldAcceptLanguageHeader() throws Exception {
    givenSuggestions();

    // 명세는 이 헤더를 Lang enum 으로 정의하지만 HTTP 표준 헤더라 실제로는 이런 값이
    // 온다. 거부하면 앱이 아닌 클라이언트는 전부 400 을 받는다.
    mvc.perform(
            get("/search/suggestions")
                .param("q", "goblin")
                .header("Accept-Language", "en-US,en;q=0.9,ko;q=0.8"))
        .andExpect(status().isOk());

    verify(store).suggest(eq("goblin"), eq(Lang.EN), anyInt());
  }

  @Test
  @DisplayName("앞뒤 공백은 서버가 제거한다")
  void trimsQuery() throws Exception {
    givenSuggestions();

    mvc.perform(get("/search/suggestions").param("q", "  도깨비  ")).andExpect(status().isOk());

    verify(store).suggest(eq("도깨비"), any(), anyInt());
  }
}
