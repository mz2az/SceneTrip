package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 계약 1.32.0 {@code CartItem.nameKo} 가 JSON 에 나오는지(계획 {@code web-ad-landing.md} §10-2). 값은 통합 레인이
 * 본다.
 */
@WebMvcTest(CartController.class)
@Import(LanguageConfiguration.class)
class CartNameKoControllerTest {

  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private CartStore store;

  @MockitoBean private CurrentAccount accounts;

  @Test
  @DisplayName("GET /cart — items[].nameKo 가 나오고, 없으면 빠진다")
  void cartItemsCarryNameKo() throws Exception {
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
    OffsetDateTime at = OffsetDateTime.parse("2026-11-15T06:00:00Z");
    when(store.list(eq(USER), any()))
        .thenReturn(
            new CartStore.Contents(
                List.of(
                    new CartItem(2L, "Bukchon Hanok Village", at).nameKo("북촌한옥마을"),
                    new CartItem(8L, "English Only", at)),
                Set.of(Lang.EN)));

    mvc.perform(get("/cart").header("X-Install-Id", INSTALL_ID).header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].nameKo").value("북촌한옥마을"))
        .andExpect(jsonPath("$.items[1].nameKo").doesNotExist());
  }
}
