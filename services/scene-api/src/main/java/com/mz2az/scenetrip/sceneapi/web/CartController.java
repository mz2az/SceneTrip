package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.CartApi;
import com.mz2az.scenetrip.sceneapi.api.model.Cart;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartItemCreate;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니 — 담기까지만.
 *
 * <p>주체는 {@code X-Device-Id} 헤더의 기기 UUID 다. 로그인이 없어 사용자를 식별할 방법이 그것뿐이고, 로그인이 생기면 이 UUID 를 계정에 이어
 * 붙인다.
 *
 * <p>목업에는 담는 경로가 셋이다 — 장소 카드의 {@code +}, 상세의 저장 버튼, 장면 팝업의 북마크. 셋 다 같은 하나를 부른다.
 */
@RestController
class CartController implements CartApi {

  private final CartStore store;

  CartController(CartStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<Cart> getCart(UUID xDeviceId, Lang acceptLanguage) {
    CartStore.Contents contents = store.list(xDeviceId, acceptLanguage);
    Cart body = new Cart(contents.items(), contents.items().size());
    return Responses.ok(body, Responses.used(acceptLanguage, contents.anyInRequestedLang()));
  }

  @Override
  public ResponseEntity<CartItem> addCartItem(
      UUID xDeviceId, CartItemCreate cartItemCreate, Lang acceptLanguage) {

    long placeId = cartItemCreate.getPlaceId();

    // 없는 장소를 담는 것은 404 다. 그냥 넣으면 외래키 위반이 500 으로 나가는데,
    // 그것은 서버 결함이 아니라 클라이언트가 잘못된 id 를 보낸 것이다.
    if (!store.placeExists(placeId)) {
      throw ApiException.notFound("PLACE_NOT_FOUND", "장소 " + placeId + " 이(가) 없습니다");
    }

    CartItem item =
        store
            .add(xDeviceId, placeId, cartItemCreate.getSourceContentId(), acceptLanguage)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "DUPLICATE_CART_ITEM", "장소 " + placeId + " 은(는) 이미 담겨 있습니다"));

    return ResponseEntity.status(HttpStatus.CREATED).body(item);
  }

  @Override
  public ResponseEntity<Void> removeCartItem(UUID xDeviceId, Long placeId) {
    // 담겨 있지 않은 것을 빼려 하면 404 다. 조용히 204 로 돌려주면 "저장됨" 토글이
    // 어긋나 있는 상태를 클라이언트가 알아차리지 못한다.
    if (!store.remove(xDeviceId, placeId)) {
      throw ApiException.notFound("CART_ITEM_NOT_FOUND", "장소 " + placeId + " 은(는) 장바구니에 없습니다");
    }
    return ResponseEntity.noContent().build();
  }
}
