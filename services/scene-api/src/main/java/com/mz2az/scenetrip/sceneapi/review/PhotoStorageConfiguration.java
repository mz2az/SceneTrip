package com.mz2az.scenetrip.sceneapi.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 사진 저장소를 고른다 — 버킷 이름이 있으면 S3, 없으면 {@link NoPhotoStorage}.
 *
 * <p>버킷이 없어도 서버는 뜬다. 카카오 키·JWT 키와 같은 규칙이다(application.yaml) — 키 없는 개발자가 검색·코스까지 못 쓰면 안 된다. 그때 사진
 * 올리기만 503 이다.
 */
@Configuration
class PhotoStorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PhotoStorageConfiguration.class);

  @Bean
  PhotoStorage photoStorage(
      @Value("${scenetrip.media.bucket:}") String bucket,
      @Value("${scenetrip.media.region:ap-northeast-2}") String region) {
    if (bucket.isBlank()) {
      log.info("사진 저장소가 설정되지 않았습니다(scenetrip.media.bucket) — 사진 올리기는 503 입니다");
      return new NoPhotoStorage();
    }
    Region r = Region.of(region);
    // HTTP 클라이언트는 JDK 의 것 하나만 둔다(MODULE.bazel 이 Netty·Apache 를 뺐다). 명시해 고른다 —
    // 클래스패스에서 찾게 두면 무엇이 잡힐지가 의존성 변화에 따라 조용히 바뀐다.
    S3Client s3 = S3Client.builder().region(r).httpClient(UrlConnectionHttpClient.create()).build();
    S3Presigner presigner = S3Presigner.builder().region(r).build();
    log.info("사진 저장소: s3://{} ({})", bucket, region);
    return new S3PhotoStorage(s3, presigner, bucket);
  }
}
