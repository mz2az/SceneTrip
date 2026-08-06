package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentDetail;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.PersonRef;
import com.mz2az.scenetrip.sceneapi.api.model.RoleType;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 작품 목록·검색의 HTTP 계층. DB 없이 돈다. */
@WebMvcTest(ContentsController.class)
@Import(LanguageConfiguration.class)
class ContentsControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private ContentStore store;

  // 이 컨트롤러는 /contents/{id}/places 도 담당해서 장소 저장소를 함께 쓴다.
  @MockitoBean private PlaceStore placeStore;

  private void givenContents(int total, ContentSummary... items) {
    when(store.list(any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new ContentStore.Page(List.of(items), total, true));
  }

  @Test
  @DisplayName("페이지네이션 값을 그대로 돌려준다")
  void echoesPagination() throws Exception {
    // total 이 items 길이보다 큰 상황이 핵심이다. 프론트는 이 차이로 "더 있음" 을
    // 판단해 무한 스크롤을 잇는다.
    givenContents(57, new ContentSummary(2L, ContentCategory.DRAMA, "도깨비", 58).releaseYear(2016));

    mvc.perform(get("/contents").param("limit", "1").param("offset", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(57))
        .andExpect(jsonPath("$.limit").value(1))
        .andExpect(jsonPath("$.offset").value(20))
        .andExpect(jsonPath("$.items[0].title").value("도깨비"))
        .andExpect(jsonPath("$.items[0].category").value("drama"))
        .andExpect(jsonPath("$.items[0].placeCount").value(58));
  }

  @Test
  @DisplayName("limit 이 명세의 상한을 넘으면 400")
  void tooLargeLimitIsRejected() throws Exception {
    givenContents(0);
    mvc.perform(get("/contents").param("limit", "101")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("모르는 category 는 400")
  void unknownCategoryIsRejected() throws Exception {
    givenContents(0);
    // 조용히 무시하면 "필터를 걸었는데 안 걸린" 상태가 된다. 클라이언트의 오타는
    // 오류로 알려 준다.
    mvc.perform(get("/contents").param("category", "webtoon"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("공백뿐인 q 는 없는 것으로 본다")
  void blankQueryIsTreatedAsAbsent() throws Exception {
    givenContents(0);

    // 그대로 넘기면 모든 설명에 걸리는 조건이 되어 필터가 아니라 전체 조회가 된다.
    mvc.perform(get("/contents").param("q", "   ")).andExpect(status().isOk());

    verify(store).list(isNull(), any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("personId 를 그대로 넘긴다")
  void passesPersonId() throws Exception {
    givenContents(0);

    // 자동완성에서 인물 제안을 누른 경로다. q 와 달리 동명이인에 어긋나지 않는다.
    mvc.perform(get("/contents").param("personId", "7")).andExpect(status().isOk());

    verify(store).list(isNull(), eq(7L), isNull(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("작품 상세는 줄거리·별칭·출연진까지 준다")
  void returnsContentDetail() throws Exception {
    ContentDetail detail =
        new ContentDetail(2L, ContentCategory.DRAMA, "도깨비", 58)
            .description("불멸의 삶을 끝내기 위해…")
            .aliases(List.of("Guardian: The Lonely and Great God", "Goblin"))
            .cast(List.of(new PersonRef(5L, "공유", RoleType.ACTOR)));
    when(store.findDetail(eq(2L), any()))
        .thenReturn(Optional.of(new ContentStore.Detail(detail, true)));

    mvc.perform(get("/contents/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("도깨비"))
        .andExpect(jsonPath("$.aliases[1]").value("Goblin"))
        .andExpect(jsonPath("$.cast[0].name").value("공유"))
        .andExpect(jsonPath("$.cast[0].roleType").value("actor"));
  }

  @Test
  @DisplayName("없는 작품은 404 — 빈 응답이 아니다")
  void missingContentIsNotFound() throws Exception {
    when(store.findDetail(any(Long.class), any())).thenReturn(Optional.empty());

    // 200 에 빈 객체로 돌려주면 프론트는 "데이터가 아직 없는 작품" 으로 오해한다.
    mvc.perform(get("/contents/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
  }
}
