package com.mz2az.scenetrip.sceneapi.popularity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 인기도 계산의 손잡이(계획 {@code popularity.md} §2·§4) — 사용자가 생긴 뒤 코드 없이 고친다.
 *
 * <p>여기 기본값은 DB 함수 {@code recompute_popularity} 의 인자 기본값과 같다. 로컬에서 인자 없이 부를 때(적재 끝, {@code just
 * popularity-recompute}) 그쪽이 쓰이고, 서버의 매일 밤 작업은 이 값을 넘긴다.
 */
@Component
@ConfigurationProperties(prefix = "scenetrip.popularity")
public class PopularitySettings {

  /** 출발점을 「사용자 몇 명분」 으로 믿을지. w = k / (k + 활동 점수). */
  private double k = 200;

  /** 최근 며칠의 활동을 보나. */
  private int windowDays = 30;

  /** 원본 기록(조회·검색)을 며칠 남기나. */
  private int retentionDays = 90;

  private double weightView = 1;
  private double weightHeart = 3;
  private double weightCart = 3;
  private double weightCourse = 5;
  private double weightReview = 5;
  private double weightVisit = 8;

  public double getK() {
    return k;
  }

  public void setK(double k) {
    this.k = k;
  }

  public int getWindowDays() {
    return windowDays;
  }

  public void setWindowDays(int windowDays) {
    this.windowDays = windowDays;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  public double getWeightView() {
    return weightView;
  }

  public void setWeightView(double weightView) {
    this.weightView = weightView;
  }

  public double getWeightHeart() {
    return weightHeart;
  }

  public void setWeightHeart(double weightHeart) {
    this.weightHeart = weightHeart;
  }

  public double getWeightCart() {
    return weightCart;
  }

  public void setWeightCart(double weightCart) {
    this.weightCart = weightCart;
  }

  public double getWeightCourse() {
    return weightCourse;
  }

  public void setWeightCourse(double weightCourse) {
    this.weightCourse = weightCourse;
  }

  public double getWeightReview() {
    return weightReview;
  }

  public void setWeightReview(double weightReview) {
    this.weightReview = weightReview;
  }

  public double getWeightVisit() {
    return weightVisit;
  }

  public void setWeightVisit(double weightVisit) {
    this.weightVisit = weightVisit;
  }
}
