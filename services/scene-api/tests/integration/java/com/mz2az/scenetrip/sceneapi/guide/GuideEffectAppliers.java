package com.mz2az.scenetrip.sceneapi.guide;

import com.mz2az.scenetrip.sceneapi.cart.CartStore;

/** 다른 패키지의 통합 테스트가 {@link GuideEffectApplier} 를 만들 수 있게 하는 통로({@code PlaceStores} 와 같은 이유). */
public final class GuideEffectAppliers {

  private GuideEffectAppliers() {}

  public static GuideEffectApplier create(CartStore cart) {
    return new GuideEffectApplier(cart);
  }
}
