# Helm

[scenetrip](scenetrip/README.md)은 AWS 원격 배포용 차트다. [signoz](signoz/README.md)는 공식 SigNoz 차트에 얹는 값과
이미지 복사본(ADR 0025), [metabase](metabase/README.md)는 저장소가 소유한 Metabase 차트(ADR 0027)다. 로컬 kind 매니페스트는
[`platform/kubernetes`](../kubernetes/README.md)에 별도로 둔다.

`just aws-render dev`, `just aws-render prd`로 인증 없이 예시 설정을 렌더링한다.
실제 배포는 DB 준비·마이그레이션 이후 `just aws-apply <환경>`이 수행한다.
