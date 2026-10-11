package com.mz2az.scenetrip.sceneapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SceneTrip 검색·지도 API 의 진입점.
 *
 * <p>지금은 껍데기다 — 기동과 상태 점검만 한다. 검색·지도 엔드포인트는 MZ2AZ-149 가, DB 연결은 MZ2AZ-152 가 채운다.
 */
@SpringBootApplication
// 매일 밤 작업 — 인기도 계산(popularity.PopularityJob), 챗봇 처리 기록 정리(guide.GuideTurnLog).
@EnableScheduling
public class SceneApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(SceneApiApplication.class, args);
  }
}
