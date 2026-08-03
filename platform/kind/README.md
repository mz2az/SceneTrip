# platform/kind

`just cluster-up` 이 만드는 로컬 Kubernetes 클러스터의 정의.

`cluster.yaml` 이 이 프로젝트가 Docker Desktop 내장 Kubernetes 대신 kind 를 쓰는
이유다 — 클러스터가 **코드**이기 때문이다. 노드 수와 호스트 포트 매핑이 모두에게
동일하고, 클러스터가 깨지면 GUI 를 헤매는 대신 지우고 한 줄로 다시 만든다.

## 호스트 포트 매핑

| 호스트 | NodePort | 용도 |
| --- | --- | --- |
| `localhost:8080` | 30080 | SigNoz UI |
| `localhost:8081` | 30081 | 애플리케이션 API |

`extraPortMappings` 는 **클러스터를 만드는 시점에만** 정할 수 있다. 포트를 추가하려면
클러스터를 다시 만들어야 하고, 그러면 수집한 텔레메트리와 DB 데이터가 사라진다.

노드 컨테이너가 이 호스트 포트를 이미 잡고 있으므로 같은 포트로 `port-forward` 를
겹쳐 열지 않는다. 리스너가 둘이면 어느 쪽이 응답할지 OS 바인딩 순서에 달리게 되고,
증상은 "설정을 고쳤는데 반영이 안 된다"와 똑같이 보인다.

```bash
just cluster-up      # 생성 (멱등)
just cluster-down    # 삭제, 확인 절차 있음
```
