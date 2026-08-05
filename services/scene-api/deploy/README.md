# services/scene-api/deploy

**이 모듈의 Kubernetes 매니페스트는 여기가 아니라
[`platform/kubernetes/scene-api/`](../../../platform/kubernetes/scene-api/) 에 있다.**

`just deploy scene-api local` 이 그 경로를 적용하기 때문이다(`tools/scripts/deploy.sh`).

AGENTS.md §3 은 모듈의 `deploy/` 를 "k8s·helm 오버레이" 자리로 적었는데, 실제 배포
스크립트와 `platform/kubernetes/README.md` 는 `platform/kubernetes/<모듈>/` 을 쓴다.
매니페스트를 두 곳에 나눠 두면 어느 쪽이 적용되는지 알 수 없으므로 실제로 동작하는
쪽으로 모았다. AGENTS.md 문구 정리는 후속 작업이다.

이 디렉터리는 앱 스토어 제출 설정처럼 **모듈만 소유하는 배포 산출물**이 생길 때 쓴다.
백엔드 서비스에는 지금 그런 것이 없다.
