# Helm

[scenetrip](scenetrip/README.md)은 AWS 원격 배포용 차트다. 로컬 kind 매니페스트는
[`platform/kubernetes`](../kubernetes/README.md)에 별도로 둔다.

`just aws-render dev`, `just aws-render prd`로 인증 없이 예시 설정을 렌더링한다.
실제 배포는 DB 준비·마이그레이션 이후 `just aws-apply <환경>`이 수행한다.
