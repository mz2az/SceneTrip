package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 장바구니의 HTTP 계층. DB 없이 돈다. */
@WebMvcTest(CartController.class)
@Import(LanguageConfiguration.class)
class CartControllerTest {

  private static final String DEVICE = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";

  @Autowired private MockMvc mvc;

  @MockitoBean private CartStore store;

  private static CartItem item(long placeId, String name) {
    return new CartItem(placeId, name, OffsetDateTime.parse("2026-08-06T06:00:00Z"));
  }

  @Test
  @DisplayName("X-Device-Id 가 없으면 400")
  void missingDeviceIdIsRejected() throws Exception {
    // 장바구니의 주체를 알 수 없다. 로그인이 없어 이 헤더가 유일한 식별자다.
    mvc.perform(get("/cart"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_DEVICE_ID"));
  }

  @Test
  @DisplayName("X-Device-Id 가 UUID 가 아니어도 같은 코드")
  void malformedDeviceIdGetsSameCode() throws Exception {
    // 클라이언트가 둘을 나눠 처리할 일이 없다 — 어느 쪽이든 기기를 식별하지 못한다.
    mvc.perform(get("/cart").header("X-Device-Id", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_DEVICE_ID"));
  }

  @Test
  @DisplayName("장바구니는 담은 순서 그대로 나온다")
  void listsInInsertionOrder() throws Exception {
    // 인기도순이 아니다. 사용자가 걸어갈 순서를 스스로 만든 목록이라 담은 순서가 뜻이다.
    when(store.list(eq(UUID.fromString(DEVICE)), any()))
        .thenReturn(new CartStore.Contents(List.of(item(2L, "북촌한옥마을"), item(8L, "서강대교")), true));

    mvc.perform(get("/cart").header("X-Device-Id", DEVICE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(2))
        .andExpect(jsonPath("$.items[0].name").value("북촌한옥마을"))
        .andExpect(jsonPath("$.items[1].name").value("서강대교"));
  }

  @Test
  @DisplayName("담으면 201 이다")
  void addReturnsCreated() throws Exception {
    when(store.placeExists(2L)).thenReturn(true);
    when(store.add(any(), eq(2L), eq(2L), any())).thenReturn(Optional.of(item(2L, "북촌한옥마을")));

    mvc.perform(
            post("/cart/items")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":2,\"sourceContentId\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.placeId").value(2));
  }

  @Test
  @DisplayName("작품 없이도 담긴다 — 장소 목록에서 바로 담는 경로")
  void addWithoutSourceContent() throws Exception {
    when(store.placeExists(8L)).thenReturn(true);
    when(store.add(any(), eq(8L), eq(null), any())).thenReturn(Optional.of(item(8L, "서강대교")));

    mvc.perform(
            post("/cart/items")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":8}"))
        .andExpect(status().isCreated());

    verify(store).add(any(), eq(8L), eq(null), any());
  }

  @Test
  @DisplayName("같은 장소를 또 담으면 409")
  void duplicateIsConflict() throws Exception {
    when(store.placeExists(2L)).thenReturn(true);
    when(store.add(any(), anyLong(), any(), any())).thenReturn(Optional.empty());

    mvc.perform(
            post("/cart/items")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_CART_ITEM"));
  }

  @Test
  @DisplayName("없는 장소를 담으면 404")
  void addingMissingPlaceIsNotFound() throws Exception {
    // 그냥 넣으면 외래키 위반이 500 으로 나가는데, 그것은 서버 결함이 아니라
    // 클라이언트가 잘못된 id 를 보낸 것이다.
    when(store.placeExists(anyLong())).thenReturn(false);

    mvc.perform(
            post("/cart/items")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":999}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
  }

  @Test
  @DisplayName("빼면 204, 담겨 있지 않았으면 404")
  void removeReturnsNoContentOrNotFound() throws Exception {
    when(store.remove(any(), eq(2L))).thenReturn(true);
    when(store.remove(any(), eq(99L))).thenReturn(false);

    mvc.perform(delete("/cart/items/2").header("X-Device-Id", DEVICE))
        .andExpect(status().isNoContent());

    // 조용히 204 로 돌려주면 "저장됨" 토글이 어긋난 상태를 클라이언트가 모른다.
    mvc.perform(delete("/cart/items/99").header("X-Device-Id", DEVICE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
  }
}
