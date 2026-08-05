package com.mz2az.scenetrip.sceneapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SceneTrip 검색·지도 API 의 진입점.
 *
 * <p>지금은 껍데기다 — 기동과 상태 점검만 한다. 검색·지도 엔드포인트는 MZ2AZ-149 가, DB 연결은 MZ2AZ-152 가 채운다.
 */
@SpringBootApplication
public class SceneApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(SceneApiApplication.class, args);
  }
}
