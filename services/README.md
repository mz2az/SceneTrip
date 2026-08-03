# services/ — 백엔드 서버

독립적으로 배포되는 백엔드 하나당 디렉터리 하나. 서비스는 여러 개가 생기는 것을
전제하며, 서로 대등한 관계다 — 중첩하지 않는다.

```
services/<이름>/
├── BUILD.bazel   필수
├── README.md     필수 — 목적, 포트, 사용하는 계약, 의존성
├── src/
├── tests/
└── deploy/
```

## 규칙

- 서비스는 자기 인터페이스를 `contracts/`(proto / OpenAPI / AsyncAPI)로 드러낸다.
- 다른 서비스의 소스를 **절대 import 하지 않는다.** 서비스 간 호출은 계약을 통해
  네트워크로 나간다.
- 공유 코드는 복사하지 않고 `libs/<언어>/` 로 올린다.
- 서비스는 자기 데이터를 소유한다. 서비스 경계를 넘는 공유 테이블은 없다.

## 명령

```bash
just new-service <이름> [언어]     # 생성
just build-module services/<이름>
just test-module  services/<이름>
just run //services/<이름>:bin
just image <이름>                  # 컨테이너 이미지 + kind 적재
```
