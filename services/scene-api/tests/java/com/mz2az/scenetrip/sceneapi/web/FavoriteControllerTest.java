package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.favorite.FavoriteStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 작품 찜의 HTTP 계층. DB 없이 돈다. */
@WebMvcTest(FavoriteController.class)
@Import(LanguageConfiguration.class)
class FavoriteControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private FavoriteStore store;

  @MockitoBean private UserStore users;

  @BeforeEach
  void resolveAccount() {
    when(users.resolve(UUID.fromString(DEVICE))).thenReturn(USER);
  }

  @Test
  @DisplayName("X-Device-Id 가 없으면 400")
  void missingDeviceIdIsRejected() throws Exception {
    mvc.perform(get("/favorites/contents"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_DEVICE_ID"));
  }

  @Test
  @DisplayName("목록은 작품 카드 모양이고 실제로 쓴 언어를 헤더로 알린다")
  void listsFavorites() throws Exception {
    ContentSummary item =
        new ContentSummary(2L, ContentCategory.DRAMA, "도깨비", 12).broadcaster("tvN");
    when(store.list(eq(USER), any(), eq(20), eq(0)))
        .thenReturn(new FavoriteStore.Page(List.of(item), 1, true));

    mvc.perform(get("/favorites/contents").header("X-Device-Id", DEVICE))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].title").value("도깨비"))
        .andExpect(jsonPath("$.items[0].placeCount").value(12));
  }

  @Test
  @DisplayName("찜하면 204 — 이미 찜한 것을 또 보내도 같다")
  void addIsIdempotent() throws Exception {
    when(store.contentExists(2L)).thenReturn(true);

    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              post("/favorites/contents")
                  .header("X-Device-Id", DEVICE)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"contentId\":2}"))
          .andExpect(status().isNoContent());
    }

    // 장바구니(POST /cart/items)는 같은 상황에 409 를 낸다. 하트는 토글이라 다르다.
    verify(store, org.mockito.Mockito.times(2)).add(USER, 2L);
  }

  @Test
  @DisplayName("없는 작품을 찜하면 404 — 외래키 위반이 500 으로 새 나가기 전에 막는다")
  void addingMissingContentIsNotFound() throws Exception {
    when(store.contentExists(anyLong())).thenReturn(false);

    mvc.perform(
            post("/favorites/contents")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentId\":999}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));

    verify(store, never()).add(any(), anyLong());
  }

  @Test
  @DisplayName("찜하지 않은 것을 해제해도 204")
  void removeIsIdempotent() throws Exception {
    mvc.perform(delete("/favorites/contents/2").header("X-Device-Id", DEVICE))
        .andExpect(status().isNoContent());

    verify(store).remove(USER, 2L);
  }

  @Test
  @DisplayName("설치 UUID 를 계정으로 바꿔 Store 에 넘긴다")
  void resolvesInstallUuidToAccount() throws Exception {
    when(store.contentExists(2L)).thenReturn(true);

    mvc.perform(
            post("/favorites/contents")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentId\":2}"))
        .andExpect(status().isNoContent());

    verify(users).resolve(UUID.fromString(DEVICE));
    verify(store).add(USER, 2L);
  }
}
