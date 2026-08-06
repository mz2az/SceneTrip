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
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import java.util.List;
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
  @DisplayName("구현하지 않은 작업은 501 로 정직하게 말한다")
  void unimplementedOperationsReturnNotImplemented() throws Exception {
    // 명세에 있는 경로가 조용히 404 가 되면 프론트는 "경로를 잘못 알았나" 를 의심한다.
    // 501 은 "경로는 맞고 아직 안 만들었다" 는 뜻이다. MZ2AZ-169 에서 채운다.
    mvc.perform(get("/contents/1")).andExpect(status().isNotImplemented());
  }
}
