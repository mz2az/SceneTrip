# SceneTrip에서 쓰는 AWS 서비스: 정의와 필요한 이유

AWS 이름을 외우기보다 **어떤 문제가 있고 어느 구성요소가 해결하는지** 순서로 읽는다.
실제 연결 그림은 [DEV·PRD 아키텍처](aws-dev-prd.md)에 있다.
AWS는 사용량·리전별로 과금하므로 금액을 고정하지 않고 비용을 만드는 요인을 적는다.

## 네트워크: 들어오는 길과 나가는 길

| 용어 | 정의 | SceneTrip에서 필요한 이유 | 비용·운영 판단 |
| --- | --- | --- | --- |
| Region | 서로 떨어진 지리적 AWS 서비스 영역 | 데이터 위치·사용자 지연·서비스 지원 범위를 선택 | 한국 사용자를 위한 서울 리전을 예제로 사용하되 계정 입력으로 확정 |
| Availability Zone (AZ) | 리전 안에서 전력·네트워크 장애를 분리한 영역 | PRD의 노드·DB가 한 장애 영역에만 묶이지 않게 함 | AZ 수가 곧 Pod 수는 아니며 AZ 간 전송비 고려 |
| VPC | 계정 안에 만드는 논리적 가상 네트워크 | DEV·PRD 주소 공간·라우팅·접근 경계를 분리 | VPC 자체와 그 안에서 쓰는 유료 기능을 구분 |
| Subnet | 한 AZ에 속한 VPC 주소 범위 | public/workload/DB 경로를 분리 | 이름이 public이어도 라우트·IP가 없으면 인터넷 연결 안 됨 |
| Route table | 목적지 주소별 다음 경로 표 | workload는 NAT로, DB는 인터넷 경로 없이 동작 | 라우트가 있다고 보안 그룹 허용까지 생기지는 않음 |
| Internet Gateway (IGW) | VPC와 인터넷 사이의 게이트웨이 | public subnet의 ALB/NAT가 외부와 통신 | private Pod를 직접 공개하는 기능으로 쓰지 않음 |
| NAT Gateway | private 자원의 외부 연결을 주소 변환 | Kakao·Naver·DeepSeek와 이미지/서비스 API에 연결 | 시간·처리량 비용. DEV 1개, PRD AZ별 분산의 가격/장애 절충 |
| Security Group | 리소스에 연결하는 상태 기반 네트워크 허용 규칙 | ALB 443은 승인 CIDR, ALB→gateway는 8080, DB 5432는 workload로 제한 | EKS 기본 self 규칙이 있으므로 Pod 간 접근은 NetworkPolicy로도 제한. 인증을 대신하지 않음 |
| VPC Endpoint | 지원 AWS 서비스에 사설 연결하는 수단 | 향후 ECR/S3/Secrets 호출의 인터넷 경로를 줄일 선택지 | 외부 LLM API에는 적용되지 않음. 타입별 비용을 따져 NAT와 비교 |

[AWS VPC 설명](https://docs.aws.amazon.com/vpc/latest/userguide/what-is-amazon-vpc.html)을 바탕으로
적용했다. 현재 endpoint를 별도 선언하지 않았다면 설치된 것으로 가르치지 않는다.

## 실행·배포

| 서비스 | 정의 | SceneTrip에서 맡는 일 | 선택 이유·대안 |
| --- | --- | --- | --- |
| EKS | AWS가 Kubernetes 제어 평면을 운영하는 서비스 | Pod·Service·Job·롤아웃을 관리 | 로컬 kind의 개념을 재사용. 제어 평면과 실행 노드 비용은 별도 |
| EKS Auto Mode | 노드·일부 네트워킹·스토리지·로드밸런싱 운영까지 관리 | 애플리케이션 요구에 맞춰 EC2 노드를 준비 | 운영할 컨트롤러를 줄이지만 앱 설계·업그레이드·정책 검증은 우리 책임 |
| EC2 | CPU·메모리·디스크를 갖는 가상 서버 | Auto Mode 노드에서 컨테이너 실행 | EKS 이름만 있어도 계산이 무료가 되지 않음 |
| ECR | OCI/Docker 이미지를 저장하는 레지스트리 | scene-api·agent·migration 이미지를 SHA로 보관 | 이미지 보관·전송·스캔 정책 관리. 앱스토어와는 역할이 다름 |
| ALB | HTTP/HTTPS 요청의 호스트·경로를 이해하는 L7 로드밸런서 | ACM으로 HTTPS 443을 받고 API 호스트의 `/v1`을 gateway Pod로 전달 | SceneTrip의 HTTP API에 맞음. 시간·LCU 비용과 public IPv4 비용을 고려 |
| ALB Listener·Target Group | Listener는 받을 프로토콜·포트와 전달 규칙, Target Group은 전달 대상과 상태 검사를 정의 | HTTPS 443 listener에서 gateway Pod IP의 HTTP 8080 target으로 연결 | `/healthz` 성공은 gateway 준비 상태. DB와 업무 기능은 별도 검사 |
| ACM | 인증서 발급·관리 서비스 | 신뢰되는 도메인의 TLS 인증서 제공 | DNS 소유 검증 필요. 같은 리전의 지원되는 인증서 ARN 입력 |
| Route 53 | DNS 이름과 레코드를 관리하는 서비스 | API 도메인을 ALB에 연결할 때 사용 가능 | 기존 DNS 사업자도 가능. 이 저장소가 도메인을 자동 구매하지 않음 |
| CloudFormation | AWS 리소스를 템플릿·스택으로 생성/변경 | Terraform 이전에 state와 신뢰 역할을 준비 | 최초 신뢰를 Terraform 본체와 분리. 기존 OIDC provider ARN을 입력 |

Auto Mode의 책임 범위는 [AWS 설명](https://docs.aws.amazon.com/eks/latest/userguide/automode.html),
ALB 설정은 [공식 Ingress 구성 문서](https://docs.aws.amazon.com/eks/latest/userguide/auto-configure-alb.html)를 따른다.
ECS/Fargate·단일 EC2도 가능한 대안이다. 이 변경은 Kubernetes 매니페스트·운영 지식의
연속성을 위해 EKS를 선택한다. 작은 서비스에 항상 가장 저렴한 선택이라는 뜻은 아니다.

### ALB·NLB·nginx를 구분하기

| 구성요소 | 적용하기 좋은 요구 | SceneTrip의 선택 |
| --- | --- | --- |
| ALB | HTTP/HTTPS 호스트·경로 라우팅 | 현재 배포에 사용. HTTPS와 `/v1` 라우팅 담당 |
| NLB | TCP/UDP, 고정 IP, L4 연결 처리 | 현재 배포에 없음. 이 요구가 생기면 별도 검토 |
| nginx gateway | 애플리케이션에 맞춘 프록시 정책 | ALB 뒤에서 경로·헤더·본문·IP별 제한 유지 |

ALB의 TLS 처리는 통신 상대 서버의 신원과 암호화를 위한 것이다. 사용자 로그인·API 권한
검증은 애플리케이션의 책임이다. AWS WAF는 HTTP 공격 규칙을 적용하는 별도 서비스이며
ALB를 쓴다고 자동으로 설치되거나 nginx의 모든 제한을 대신하지 않는다.

`Ingress`는 HTTP 라우팅 선언, `IngressClass`는 처리 컨트롤러 선택,
`IngressClassParams`는 Auto Mode의 AWS 설정이다. `ClusterIP Service`는 클러스터 내부
접속 창구다. 이들은 AWS 서비스 이름이 아니라 Kubernetes 리소스다. 현재 ALB는
IP target으로 gateway Pod에 연결하며 Service마다 로드밸런서를 만드는 구조가 아니다.

ALB 뒤에서는 원래 클라이언트 주소를 안전하게 복원해야 IP별 제한이 유지된다.
ALB subnet의 연결만 신뢰하고 ALB가 추가한 마지막 X-Forwarded-For 주소를 사용한다.
클라이언트가 보낸 임의의 앞쪽 주소를 믿지 않는 이유는
[전달 헤더 문서](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html)를 참고한다.

## 신원·비밀값·state

| 서비스·개념 | 정의 | SceneTrip에서 필요한 이유 |
| --- | --- | --- |
| IAM Role | 누가 어떤 조건으로 어떤 API를 호출할지 정한 임시 신원 | 배포 역할, EKS cluster 역할, node 역할을 분리 |
| OIDC | 외부 발급자의 서명된 신원 정보를 검증하는 표준 | GitHub 실행이 허용된 저장소·환경에서 시작됐는지 AWS가 확인 |
| STS | 제한된 수명의 AWS 자격 증명을 발급하는 서비스 | 장기 access key 없이 workflow가 임시 역할을 사용 |
| Secrets Manager | 비밀값의 저장·버전·접근을 관리 | RDS master, 앱 DB 암호, Kakao·DeepSeek 키를 코드와 분리 |
| KMS | 암호화 키와 그 사용 권한을 관리 | RDS·Secret 저장 데이터 보호의 기반. AWS 관리 키와 고객 관리 키를 구분. 현재 state 버킷은 KMS가 아닌 SSE-S3 사용 |
| S3 | 객체를 key 단위로 저장하는 서비스 | Terraform state와 lock 파일을 환경별 key로 저장 |
| S3 Versioning | 덮어쓴 객체의 이전 버전을 보존 | 잘못 쓴 state 복구 근거 확보. DB 데이터 백업을 대신하지 않음 |
| Terraform state | 선언과 실제 리소스 ID의 대응 기록 | 기존 리소스를 다시 만들지 않고 변경을 계산 |
| Terraform lock | 같은 state에 동시 변경하지 못하게 하는 잠금 | 동시 apply로 대응표가 손상되는 사고 방지 |

OIDC 토큰에 `environment:prd`가 적혀 있다고 모든 브랜치를 믿어도 되는 것은 아니다.
GitHub Environment 보호 설정으로 그 환경에 도달할 수 있는 브랜치와 사람을 제한한다.
[AWS의 GitHub OIDC 조건 안내](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_for-idp_oidc.html)를 따른다.

IAM 권한, 암호화, 네트워크는 서로 다른 층이다. S3 암호화를 켰더라도
`s3:GetObject` 권한을 너무 넓게 주면 state를 읽을 수 있다. Secret을 base64로 바꿔서
Git에 올리는 것도 비밀값 보호가 아니다. state·saved plan·비밀값 파일은 커밋하지 않는다.

## 데이터와 관측

| 서비스·구성요소 | 정의 | SceneTrip에서 필요한 이유 |
| --- | --- | --- |
| RDS for PostgreSQL | PostgreSQL의 백업·패치·가용성 운영을 일부 맡기는 서비스 | 촬영지·코스 데이터를 Pod 수명과 분리하고 복구 경로 확보 |
| RDS Multi-AZ | 다른 AZ의 대기 DB를 활용하는 가용성 구성 | 주 DB 장애 시 전환. 읽기 성능 확장용 read replica와 다름 |
| PostGIS | PostgreSQL 공간 타입·함수·인덱스 확장 | 지도 bbox·주변 반경·거리 질의 |
| pg_trgm | 문자열 trigram 기반 유사·부분 검색 확장 | 한글 장소/작품 검색. DB locale이 글자를 인식해야 함 |
| Flyway | 순서와 checksum을 기록하는 SQL 마이그레이션 도구 | API 이미지와 DB 스키마의 호환성을 배포 전에 검증 |
| CloudWatch | AWS 지표·로그·경보 수집 플랫폼 | EKS 제어 로그·RDS 지표 분석의 기반 |
| OpenTelemetry | 계측 데이터 형식·SDK·전송 표준 | Java 요청의 로그·지표·trace를 수집기에 전달 |
| SigNoz | OpenTelemetry 데이터를 조회하는 관측 도구 | 로컬 장애를 로그/trace로 따라감. AWS 관리형 서비스가 아님 |

Multi-AZ, 백업, snapshot은 서로 다르다. Multi-AZ는 장애 전환에, 백업/PITR은
잘못 지운 데이터의 시점 복구에 필요하다. 복구는 새 DB로 수행하고 검증 후
endpoint·비밀값을 전환한다. 단순 이미지 롤백으로 SQL 변경을 되돌릴 수 없다.

## 넣지 않은 것

Redis/ElastiCache, pgvector, 별도 embedding 서버, Bedrock, S3 정적 웹 호스팅을
현재 아키텍처에 자동으로 추가하지 않는다. SceneTrip의 현 코드에 그 의존성이
없기 때문이다. 미래에 메모리 세션을 외부화하거나 모델 제공자를 바꾸면
필요성과 데이터 경계·운영 비용을 별도 ADR로 검토한다.

## 확인 문제

1. private subnet의 agent가 DeepSeek를 부를 때 NAT가 없으면 어느 요청이 실패하는가?
2. S3 state가 멀쩡해도 DB 데이터를 복구하지 못하는 이유는 무엇인가?
3. PRD의 DB가 Multi-AZ인데 agent의 무중단을 보장하지 못하는 이유는 무엇인가?
4. OIDC role에 정확한 저장소와 환경을 지정하고도 GitHub 환경 보호가 필요한 이유는?
5. ALB의 TLS 설정만으로 `/v1/actuator`가 차단되는가? 실제로 어느 코드가 막는가?
6. `show_trgm('도깨비')`가 빈 배열이면 CPU 증설보다 먼저 무엇을 확인해야 하는가?

답은 네트워크 경로, 데이터 수명, 메모리 상태, 신뢰 조건, gateway 경로 정책,
DB 문자 분류 로케일을 각각 설명할 수 있어야 한다.
