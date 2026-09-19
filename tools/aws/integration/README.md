# AWS 배포 구성의 로컬 통합 검증

```sh
just aws-test-integration
```

실행 중인 **로컬 Docker 엔진**이 필요하다. 원격 TCP·SSH Docker context는 거절한다.
Bazel이 `MODULE.bazel`의 digest로 고정한 PostGIS·nginx 이미지를 tar로 준비하고,
테스트는 이를 로컬 Docker에 적재한다. 임의의 컨테이너·네트워크 이름을 만들며
완료·실패 시 자신이 만든 이름만 삭제한다. 기존 kind·애플리케이션 DB에는 접근하지 않는다.
캐시된 성공을 재사용하지 않고 매 실행마다 검증한다.

| 검증 | 실제 실행 내용 |
| --- | --- |
| PostgreSQL 17 역할 경계 | `NOSUPERUSER CREATEDB CREATEROLE` 관리자에서 실제 bootstrap SQL의 역할·DB 생성 구간 실행. `SET ROLE` 권한이 빠지면 실패 |
| DB 초기화 | C/C.UTF-8 locale, PostGIS·pg_trgm, 실제 `db-bootstrap.sh` 실행과 반복 실행 |
| 마이그레이션 | 저장소 V1–V14 원문을 `app_migrate`로 각 트랜잭션에서 실행 |
| 런타임 권한 | `app_runtime`의 공간 데이터 INSERT·SELECT·UPDATE·DELETE와 sequence 사용. schema 생성과 Flyway 이력 접근은 거절 |
| Gateway | 실제 Helm 출력에서 nginx 설정 추출, 고정 nginx 이미지에서 ALB 모의 relay→gateway→backend HTTP 요청 |
| 컨테이너 제한 | nginx UID/GID 10001, 읽기 전용 root filesystem, capability 제거, 쓰기 가능한 `/tmp`만 허용 |
| 외부 경계 | relay가 실제 TCP client IP를 XFF 마지막에 추가. 위조된 이전 XFF·X-Real-IP 제거, 승인되지 않은 client·gateway 직접 접근 403, Host·내부 경로 우회 차단 |
| 요청 제한 | 위조 XFF를 매번 바꿔도 동일 실제 client의 80회 burst는 429. 다른 승인 client IP의 요청은 정상 처리 |

proxy 시험은 허용 client 2개와 비허용 client, ALB 동작을 모사한 nginx relay를 각각 다른
컨테이너 IP로 실행한다. gateway는 relay IP만 신뢰하며 모든 컨테이너 포트는 외부에
게시하지 않는다. 실제 AWS ALB를 실행한 증거는 아니며, ALB의 XFF append 설정과
동일한 proxy 동작에 대한 nginx 경계를 검증한다.

로컬 PostgreSQL에는 AWS의 `rds_superuser` 확장 설치 기능이 없다. 역할·DB 생성은
권한이 제한된 관리자로 검증한 뒤, PostGIS·pg_trgm 설치만 로컬 superuser로 수행한다.
이후 전체 bootstrap과 역할 검증은 다시 제한된 관리자로 수행한다. 비밀번호는 실행 중
생성하고 환경으로만 전달한다. 저장소나 출력에 기록하지 않는다.

이 테스트는 SQL의 실행 가능성과 권한을 검증한다. 실제 Flyway runner의 checksum·이력
동작을 대신하지 않으며, Flyway 이력 권한 검사에는 같은 이름의 시험 테이블을 사용한다.
RDS TLS·IAM, ALB·ACM, EKS NetworkPolicy의 실환경 검증도 별도로 필요하다. DB 연결은
컨테이너 내부 Unix socket을 사용한다. Linux amd64 이미지는 Apple Silicon에서
Docker의 CPU 에뮬레이션 기능이 필요하다.

이미지 출처: [PostGIS Docker](https://github.com/postgis/docker-postgis),
[nginx unprivileged](https://github.com/nginx/docker-nginx-unprivileged).
