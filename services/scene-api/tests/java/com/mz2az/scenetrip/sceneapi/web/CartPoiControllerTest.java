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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartPoi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 장바구니에 편의시설 담기의 HTTP 계층 — 계약 1.31.0 {@code POST /cart/pois} · {@code DELETE /cart/pois/{poiId}} ·
 * {@code GET /cart} 의 {@code pois}·{@code poiCount} (docs/project/plans/keep-poi.md §1·§2). DB 없이
 * 돈다.
 *
 * <p>컨트롤러는 {@link CartStore} 하나만 부른다고 정했다. 쓰는 메서드:
 *
 * <ul>
 *   <li>{@code boolean poiAvailable(long poiId)} — 편의시설이 있고 폐업이 아니다. false 면 404 {@code
 *       POI_NOT_FOUND}
 *   <li>{@code Optional<CartPoi> addPoi(UUID userId, long poiId, Lang lang)} — 비면 이미 담겼다(409)
 *   <li>{@code boolean removePoi(UUID userId, long poiId)} — false 면 담겨 있지 않았다(404)
 *   <li>{@code Contents(List<CartItem> items, Set<Lang> shownLangs, List<CartPoi> pois)} — 두 인자
 *       생성자는 pois 가 빈 목록
 * </ul>
 */
@WebMvcTest(CartController.class)
@Import(LanguageConfiguration.class)
class CartPoiControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";

  /** 설치 UUID 를 {@link CurrentAccount} 가 바꿔 주는 계정 id. 저장의 주체는 이쪽이다. */
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  private static final OffsetDateTime ADDED = OffsetDateTime.parse("2026-11-14T12:00:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private CartStore store;

  @MockitoBean private CurrentAccount accounts;

  @BeforeEach
  void resolveAccount() {
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
  }

  private static CartPoi cartPoi(long id, String name) {
    return new CartPoi(id, name, "카페", PoiCategoryGroup.FOOD, 37.5665, 126.978, ADDED)
        .categoryLabel("Cafe")
        .address("서울 중구 세종대로 110")
        .closed(false);
  }

  private static CartItem item(long placeId, String name) {
    return new CartItem(placeId, name, ADDED);
  }

  private static MockHttpServletRequestBuilder addPoi(String body) {
    return post("/cart/pois")
        .header("X-Install-Id", INSTALL_ID)
        .header("Accept-Language", "en")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  // ───────────── POST /cart/pois ─────────────

  @Test
  @DisplayName(
      "담으면 201 — 본문은 CartPoi(id·name·category·categoryGroup·좌표·addedAt·closed=false), placeId 는 없다")
  void addPoiReturnsCreatedWithCartPoi() throws Exception {
    when(store.poiAvailable(7L)).thenReturn(true);
    when(store.addPoi(eq(USER), eq(7L), any())).thenReturn(Optional.of(cartPoi(7L, "스타벅스 시청점")));

    mvc.perform(addPoi("{\"poiId\":7}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.name").value("스타벅스 시청점"))
        .andExpect(jsonPath("$.category").value("카페"))
        .andExpect(jsonPath("$.categoryGroup").value("food"))
        .andExpect(jsonPath("$.latitude").value(37.5665))
        .andExpect(jsonPath("$.longitude").value(126.978))
        .andExpect(jsonPath("$.addedAt").exists())
        .andExpect(jsonPath("$.closed").value(false))
        .andExpect(jsonPath("$.placeId").doesNotExist());
  }

  @Test
  @DisplayName("설치 UUID 를 계정으로 바꾸고, 요청 언어를 그대로 Store 에 넘긴다")
  void addPoiPassesAccountAndLanguage() throws Exception {
    when(store.poiAvailable(7L)).thenReturn(true);
    when(store.addPoi(eq(USER), eq(7L), any())).thenReturn(Optional.of(cartPoi(7L, "스타벅스 시청점")));

    mvc.perform(addPoi("{\"poiId\":7}")).andExpect(status().isCreated());

    verify(accounts).resolve(UUID.fromString(INSTALL_ID));
    verify(store).addPoi(USER, 7L, Lang.EN);
  }

  @Test
  @DisplayName("촬영지와 같은 곳인 편의시설 — 서버가 촬영지로 담고 201 본문의 placeId 가 채워진다")
  void addLinkedPoiCarriesPlaceId() throws Exception {
    when(store.poiAvailable(8L)).thenReturn(true);
    when(store.addPoi(eq(USER), eq(8L), any()))
        .thenReturn(Optional.of(cartPoi(8L, "북촌한옥마을 카페").placeId(45L)));

    mvc.perform(addPoi("{\"poiId\":8}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(8))
        .andExpect(jsonPath("$.placeId").value(45));
  }

  @Test
  @DisplayName("없거나 폐업한 편의시설은 404 POI_NOT_FOUND — 담기를 시도하지 않는다")
  void unavailablePoiIsNotFound() throws Exception {
    when(store.poiAvailable(anyLong())).thenReturn(false);

    mvc.perform(addPoi("{\"poiId\":999}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));

    verify(store, never()).addPoi(any(), anyLong(), any());
  }

  @Test
  @DisplayName("이미 담은 편의시설(또는 이미 담긴 촬영지로 담기는 것)은 409 DUPLICATE_CART_ITEM")
  void duplicateIsConflict() throws Exception {
    when(store.poiAvailable(7L)).thenReturn(true);
    when(store.addPoi(any(), eq(7L), any())).thenReturn(Optional.empty());

    mvc.perform(addPoi("{\"poiId\":7}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_CART_ITEM"));
  }

  @Test
  @DisplayName("poiId 가 없으면 400 INVALID_PARAMETER")
  void missingPoiIdIsBadRequest() throws Exception {
    mvc.perform(addPoi("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).addPoi(any(), anyLong(), any());
  }

  @Test
  @DisplayName("poiId 가 숫자가 아닌 문자열이면 400 INVALID_PARAMETER")
  void stringPoiIdIsBadRequest() throws Exception {
    mvc.perform(addPoi("{\"poiId\":\"abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).addPoi(any(), anyLong(), any());
  }

  @Test
  @DisplayName("X-Install-Id 가 없으면 400 MISSING_INSTALL_ID — 다른 장바구니 경로와 같다")
  void missingInstallIdIsRejected() throws Exception {
    mvc.perform(post("/cart/pois").contentType(MediaType.APPLICATION_JSON).content("{\"poiId\":7}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));

    verify(store, never()).addPoi(any(), anyLong(), any());
  }

  // ───────────── DELETE /cart/pois/{poiId} ─────────────

  @Test
  @DisplayName("빼면 204 — 계정으로 바꾼 주체와 그 편의시설 id 로 지운다")
  void removePoiReturnsNoContent() throws Exception {
    when(store.removePoi(USER, 7L)).thenReturn(true);

    mvc.perform(delete("/cart/pois/7").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNoContent());

    verify(store).removePoi(USER, 7L);
  }

  @Test
  @DisplayName("담겨 있지 않은 편의시설을 빼면 404")
  void removingAbsentPoiIsNotFound() throws Exception {
    when(store.removePoi(any(), eq(99L))).thenReturn(false);

    mvc.perform(delete("/cart/pois/99").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").exists());
  }

  @Test
  @DisplayName("poiId 가 숫자가 아니면 400 INVALID_PARAMETER")
  void nonNumericPoiIdIsBadRequest() throws Exception {
    mvc.perform(delete("/cart/pois/abc").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).removePoi(any(), anyLong());
  }

  // ───────────── GET /cart ─────────────

  @Test
  @DisplayName("빈 장바구니에도 pois(빈 배열)와 poiCount(0)가 언제나 있다")
  void emptyCartAlwaysCarriesPois() throws Exception {
    when(store.list(eq(USER), any())).thenReturn(new CartStore.Contents(List.of(), Set.of()));

    mvc.perform(get("/cart").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.totalCount").value(0))
        .andExpect(jsonPath("$.pois").isArray())
        .andExpect(jsonPath("$.pois").isEmpty())
        .andExpect(jsonPath("$.poiCount").value(0));
  }

  @Test
  @DisplayName("촬영지만 있어도 pois 는 빈 배열로 나온다")
  void placesOnlyCartCarriesEmptyPois() throws Exception {
    when(store.list(eq(USER), any()))
        .thenReturn(new CartStore.Contents(List.of(item(2L, "북촌한옥마을")), Set.of(Lang.EN)));

    mvc.perform(get("/cart").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.pois").isArray())
        .andExpect(jsonPath("$.pois").isEmpty())
        .andExpect(jsonPath("$.poiCount").value(0));
  }

  @Test
  @DisplayName("items·totalCount 는 촬영지만, pois·poiCount 는 편의시설만 — 담은 순서 그대로, 폐업은 closed=true")
  void cartSplitsPlacesAndPois() throws Exception {
    when(store.list(eq(USER), any()))
        .thenReturn(
            new CartStore.Contents(
                List.of(item(2L, "북촌한옥마을")),
                Set.of(Lang.EN),
                List.of(cartPoi(7L, "스타벅스 시청점"), cartPoi(9L, "문 닫은 식당").closed(true))));

    mvc.perform(get("/cart").header("X-Install-Id", INSTALL_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].placeId").value(2))
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.pois.length()").value(2))
        .andExpect(jsonPath("$.pois[0].id").value(7))
        .andExpect(jsonPath("$.pois[0].closed").value(false))
        .andExpect(jsonPath("$.pois[1].id").value(9))
        .andExpect(jsonPath("$.pois[1].closed").value(true))
        .andExpect(jsonPath("$.poiCount").value(2));
  }
}
