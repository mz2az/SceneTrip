"""SceneTrip AWS 과정의 정본. 본문·발표 요약·강사 노트를 한 곳에서 관리한다."""


def chapter(anchor, title, summary, body, notes, source):
    return {
        "id": anchor,
        "title": title,
        "summary": summary,
        "body": body,
        "notes": notes,
        "source": source,
    }


CHAPTERS = [
    chapter(
        "start",
        "SceneTrip을 DEV와 PRD에 배포하기",
        [
            "네이티브 앱 → scene-api → PostgreSQL·trip-guide",
            "구현 파일에서 배포 형상과 실패 지점을 읽는다",
            "로컬 검증과 실제 AWS 적용 결과를 구별한다",
        ],
        """<p>이 과정은 Kubernetes를 처음 접한 개발자가 SceneTrip의 요청 경로, AWS 자원,
        배포 순서와 복구 방법을 설명하고 검증할 수 있도록 구성했다. 기존 TripPilot 강의의
        전달 방식과 검증 원칙을 가져오되 애플리케이션·주소·명령·환경 값은 이 저장소를 기준으로 바꿨다.</p>
        <p>강의 100분, 로컬 실습 40분, DEV 배포 검토 40분을 권장한다. 실제 클라우드 생성은
        계정·도메인·역할·비밀값을 준비한 팀만 진행한다. 이 문서와 구성도는 코드의 선언을 설명하며
        AWS에 이미 배포되어 있다는 증거가 아니다.</p>""",
        "첫 화면에서 참석자가 개발자·운영자 중 어느 역할인지 확인한다. 모든 실습 결과는 환경과 커밋 SHA를 함께 기록한다.",
        "docs/project/plans/aws-dev-prd-port.md",
    ),
    chapter(
        "product",
        "제품 경계와 실행 단위",
        [
            "iOS·Android 앱은 사용자 기기에서 실행",
            "scene-api는 Java 21·Spring Boot HTTP 서버",
            "trip-guide는 Python 에이전트, 데이터 정본은 scene-api",
        ],
        """<p>SceneTrip은 작품 속 장소를 찾고 코스를 만들며 여행 중 안내를 받는 제품이다.
        앱은 API 계약을 통해 검색·장바구니·코스·가이드 기능을 사용한다. Spring 서버는 사용자별
        데이터와 권한 판정을 소유하고, Python 에이전트는 모델 호출과 도구 실행을 담당한다.</p>
        <p>EKS에 배포하는 것은 서버와 에이전트다. iOS 서명·App Store, Android 서명·Play Store는
        별도 배포 경로다. 모바일 API 주소도 환경별로 주입해야 한다. 이 구성에는 Redis,
        pgvector, 임베딩 서버가 없다. PostgreSQL의 공간 검색과 trigram 검색을 사용한다.</p>""",
        "앱 패키지와 서버 컨테이너를 같은 배포라고 부르지 않는다. 모델이 DB 자격 증명을 갖지 않는 경계를 강조한다.",
        "docs/architecture/adr/0013-guide-chat-is-served-by-the-python-agent.md",
    ),
    chapter(
        "request",
        "요청 한 건의 경로",
        [
            "HTTPS → ALB → nginx gateway → scene-api:8080/v1",
            "scene-api → trip-guide:8899 → DeepSeek",
            "에이전트의 장소 조회는 내부 scene-api로 돌아온다",
        ],
        """<p>외부 허용 대역의 HTTPS 요청은 ALB에서 TLS를 종료하고 host와 <code>/v1</code>
        경로 조건을 통과해 nginx gateway로 전달된다. Ingress는 ClusterIP Service를 참조하고,
        <code>target-type=ip</code>인 실제 데이터 경로는 ALB에서 gateway 파드 IP의 8080으로
        직접 이어진다. gateway가 API에 공개하는 경로는 <code>/v1/</code>이며 Actuator와
        에이전트 내부 HTTP는 차단한다. ALB 이후 통신은 사설 HTTP이며 종단 간 TLS 구성은 아니다.</p>
        <p>가이드 요청에서 scene-api가 에이전트를 호출하고, 에이전트가 DeepSeek API와 내부 조회
        도구를 사용한다. 지도·길찾기에는 별도 외부 API가 쓰인다. 장애를 찾을 때는 사용자가 본
        오류에서 출발해 gateway, API, 에이전트, 외부 제공자 중 어느 경계에서 실패했는지 좁힌다.</p>""",
        "일반 장소 조회는 모델을 호출하지 않는다. 모든 요청이 AI 경로를 통과한다고 그리지 않는다.",
        "services/scene-api/src/main/resources/application.yaml",
    ),
    chapter(
        "environments",
        "DEV와 PRD의 비용·장애 허용 범위",
        [
            "DEV: 2 AZ·NAT 1개·Single-AZ DB",
            "PRD: 3 AZ·AZ별 NAT·Multi-AZ DB",
            "계정·state·역할·비밀값을 환경별로 분리",
        ],
        """<table><thead><tr><th>항목</th><th>DEV</th><th>PRD</th></tr></thead><tbody>
        <tr><td>가용 영역</td><td>2개</td><td>3개</td></tr><tr><td>NAT Gateway</td><td>공유 1개</td><td>AZ마다 1개</td></tr>
        <tr><td>RDS</td><td>Single-AZ</td><td>Multi-AZ</td></tr><tr><td>백업 보존</td><td>7일</td><td>14일</td></tr>
        <tr><td>DB 삭제 보호</td><td>꺼짐</td><td>켜짐</td></tr></tbody></table>
        <p>DEV의 단일 NAT는 비용을 줄이지만 해당 AZ 장애가 다른 AZ의 외부 호출에도 영향을 준다.
        PRD의 Multi-AZ는 DB 장애 복구를 지원하나 읽기 확장 장치로 해석하면 안 된다. 에이전트는
        두 환경 모두 메모리 상태를 가진 단일 replica라 전체 서비스가 무중단이라는 주장은 성립하지 않는다.</p>""",
        "구성도 아래 출처 해시를 확인한다. PRD의 높은 비용은 어떤 장애 범위를 줄이는지 항목별로 질문한다.",
        "platform/environments/README.md",
    ),
    chapter(
        "vpc",
        "VPC와 CIDR: 네트워크의 소유 범위",
        [
            "VPC는 AWS 안의 논리적 사설 네트워크",
            "CIDR은 사용할 주소 범위",
            "환경 분리는 이름보다 주소·계정·권한 경계가 중요",
        ],
        """<p>VPC는 서브넷, 라우팅 테이블, 보안 그룹을 묶는 네트워크 경계다. CIDR의 접두사 길이는
        주소 공간 크기를 결정한다. SceneTrip의 예제는 DEV와 PRD에 겹치지 않는 VPC 범위를 쓴다.
        향후 VPN·사내망·피어링을 연결할 때 중복 주소는 라우팅 설계를 어렵게 만든다.</p>
        <p>VPC 자체를 만든 것만으로 접근이 허용되거나 차단되는 것은 아니다. 패킷 경로와 보안
        규칙을 함께 읽어야 한다. 단일 VPC의 namespace 분리는 저렴한 대안이지만 잘못된 권한이나
        네트워크 변경의 영향이 두 환경에 번질 수 있다. 이번 구성은 환경별 VPC를 사용한다.</p>""",
        "CIDR 예제의 계정 ID와 주소를 실제 값으로 오인하지 않게 한다. 주소 설계는 나중에 바꾸기 비싼 입력이다.",
        "platform/terraform/aws/network.tf",
    ),
    chapter(
        "subnets",
        "서브넷·AZ·라우팅 테이블",
        [
            "AZ마다 public·application·database subnet을 분리",
            "public 여부는 이름이 아니라 IGW 경로로 결정",
            "DB subnet은 인터넷으로 직접 나가지 않는다",
        ],
        """<p>서브넷은 하나의 AZ에 속하는 VPC 주소 구간이다. 가용 영역은 같은 리전 안의 분리된
        장애 영역이며, 여러 AZ에 파드를 놓아 한 영역의 장애를 견딘다. SceneTrip은 외부 진입,
        애플리케이션 실행, 데이터베이스를 서로 다른 subnet 계층에 배치한다.</p>
        <p>라우팅 테이블은 목적지에 따라 다음 홉을 고른다. public subnet의 기본 경로는 IGW,
        application subnet의 외부 경로는 NAT, DB subnet은 필요한 사설 통신으로 제한한다.
        사설 subnet에 있다고 외부 API를 호출할 수 없는 것은 아니다. NAT를 거쳐 나갈 수 있다.</p>""",
        "서브넷 이름에서 public을 지우면 보안이 달라지는지 물어본다. 실제 라우팅과 공인 주소가 답이다.",
        "platform/terraform/aws/network.tf",
    ),
    chapter(
        "egress",
        "IGW와 NAT: 들어오는 길과 나가는 길",
        [
            "IGW는 VPC와 인터넷 사이의 게이트웨이",
            "NAT는 사설 workload의 외부 연결을 중계",
            "DeepSeek·Kakao·이미지 다운로드에 egress 경로가 필요",
        ],
        """<p>Internet Gateway(IGW)는 VPC가 인터넷과 통신할 수 있는 연결점이다. NAT Gateway는
        private 주소를 쓰는 노드·파드가 먼저 시작한 외부 통신을 중계한다. NAT는 보안 그룹이나
        인증 서버를 대신하지 않으며, NAT를 둔다고 모든 목적지가 허용되어야 하는 것도 아니다.</p>
        <p>NAT는 시간과 처리 데이터량에 따라 비용이 발생한다. DEV는 1개를 공유하고 PRD는 AZ별로
        배치해 AZ 간 의존을 줄인다. VPC endpoint는 지원 AWS 서비스 트래픽의 대안이지만
        DeepSeek 같은 일반 인터넷 API를 전부 대체하지 않는다. endpoint 자체 비용과 DNS도 고려한다.</p>""",
        "DB 접속은 되는데 모델 호출만 실패하면 egress·DNS·키·제공자 응답을 분리해서 점검한다.",
        "platform/terraform/aws/network.tf",
    ),
    chapter(
        "security-groups",
        "Security Group: 허용할 연결만 선언",
        [
            "SG는 네트워크 인터페이스에 적용하는 상태 기반 방화벽",
            "DB 5432는 필요한 workload 경계에서만 허용",
            "외부 CIDR 제한은 사용자 인증을 대체하지 않는다",
        ],
        """<p>Security Group은 인바운드·아웃바운드 허용 규칙을 관리한다. 연결 상태를 추적하므로
        허용된 연결의 응답을 별도 신규 연결처럼 다루지 않는다. 라우팅 경로가 존재해도 SG에서
        막으면 통신할 수 없다. ALB의 443은 허용한 클라이언트 CIDR으로 제한하고 ALB SG에서
        gateway 대상의 8080으로 가는 규칙을 둔다. 기본 cluster SG의 자체 통신 규칙도 있으므로
        파드 간 접근은 NetworkPolicy로 함께 제한한다. EKS·RDS와 각 연결의 목적·포트를 나누어 읽는다.</p>
        <p>초기 SceneTrip은 설치 UUID인 <code>X-Device-Id</code>를 식별에 사용한다. 이 값은
        비밀 인증서가 아니므로 공개 인터넷 서비스로 확장하기 전에 인증·권한·요청 제한을 검토해야
        한다. 이번 환경 값은 허용 CIDR을 명시하도록 하고 무제한 공개 예제를 기본값으로 삼지 않는다.</p>""",
        "내 IP가 바뀌어 접속이 안 될 때 0.0.0.0/0으로 풀지 말고 승인된 대역을 갱신하는 과정을 보여준다.",
        "docs/api/auth.md",
    ),
    chapter(
        "iam",
        "IAM: 누가 어떤 AWS 작업을 할 수 있는가",
        [
            "IAM policy는 action·resource·condition으로 권한을 제한",
            "배포 역할과 런타임 권한을 분리",
            "신뢰 정책과 권한 정책은 서로 다른 질문에 답한다",
        ],
        """<p>IAM은 AWS 리소스에 대한 신원과 권한을 관리한다. 역할의 신뢰 정책은 누가 그 역할을
        맡을 수 있는지 정하고, 권한 정책은 맡은 뒤 무엇을 할 수 있는지 정한다. 역할을 만들었다고
        자동으로 EKS 내부 Kubernetes 권한까지 생기는 것은 아니다.</p>
        <p>부트스트랩, 인프라 변경, 애플리케이션 배포가 같은 관리자 권한을 공유하면 한 workflow의
        변경이 계정 전체로 번질 수 있다. 환경·저장소·리소스 범위를 좁힌다. EC2에 고정 access key를
        넣는 방식은 회전·유출 위험이 있어 대안으로 채택하지 않는다.</p>""",
        "AccessDenied를 해결할 때 AdministratorAccess부터 붙이지 않는다. 거부된 API와 ARN, condition을 읽는다.",
        "platform/terraform/README.md",
    ),
    chapter(
        "oidc",
        "OIDC와 STS: GitHub Actions의 임시 자격 증명",
        [
            "OIDC 토큰이 저장소·workflow 환경의 신원을 증명",
            "STS가 짧은 수명의 AWS 자격 증명 발급",
            "DEV·PRD Environment 신뢰 조건을 각각 제한",
        ],
        """<p>OpenID Connect(OIDC)는 외부 신원 제공자의 서명된 토큰으로 신원을 전달하는 규약이다.
        AWS STS(Security Token Service)는 신뢰 정책을 통과한 요청에 임시 자격 증명을 발급한다.
        GitHub에 장기 AWS secret key를 저장하지 않고 workflow 실행에 필요한 시간만 권한을 준다.</p>
        <p>GitHub의 <code>id-token: write</code>는 토큰 요청 권한이며 AWS 관리자 권한이라는 뜻이
        아니다. issuer, audience, 저장소와 Environment subject를 확인해야 한다. GitHub Environment의
        승인 규칙과 branch 정책도 별도로 설정한다. 최초 OIDC 제공자와 역할은 기존 관리 권한으로 준비한다.</p>""",
        "OIDC 로그인 성공과 Terraform apply 권한 성공을 구별한다. Environment 이름 대소문자도 신뢰 조건의 일부다.",
        ".github/workflows/aws-bootstrap.yml",
    ),
    chapter(
        "state",
        "Terraform state와 S3 잠금",
        [
            "state는 선언한 자원과 실제 식별자를 연결하는 기록",
            "S3에 환경별 state·버전 기록 저장",
            "잠금은 동시 적용으로 state가 덮이는 일을 막는다",
        ],
        """<p>Terraform은 state를 통해 코드의 리소스 주소와 AWS 리소스를 연결한다. S3는 객체
        저장소로 state를 팀이 공유하는 데 사용한다. DEV와 PRD의 backend key를 분리하고 버전
        관리·암호화·공개 차단을 적용한다. state에는 민감한 값이 들어갈 수 있어 로그나 PR 첨부로 공유하지 않는다.</p>
        <p>현재 S3 backend는 <code>use_lockfile</code> 방식의 잠금을 지원한다. 잠금 파일 접근
        권한과 state 접근 권한이 모두 필요하다. 잠금 오류를 즉시 강제 해제하면 다른 적용과 충돌할 수
        있으므로 실행 중인 workflow부터 확인한다. 로컬 state는 개인 실험에는 가능하지만 팀의 공유 운영에 부적합하다.</p>""",
        "state 파일을 Git에 넣지 않는 이유를 비밀값과 동시 작업 두 측면으로 설명한다. 실패한 작업의 lock은 소유자를 확인한다.",
        "platform/environments/README.md",
    ),
    chapter(
        "eks",
        "EKS Auto Mode와 EC2의 역할",
        [
            "EKS는 Kubernetes 제어 평면을 관리",
            "Auto Mode가 노드·네트워크·로드밸런서 수명 주기를 관리",
            "EC2는 컨테이너를 실제 실행하는 컴퓨팅 자원",
        ],
        """<p>EKS는 Kubernetes API와 클러스터 제어 평면을 AWS가 운영하는 서비스다. EKS Auto Mode는
        노드 생성·교체와 일부 네트워킹·스토리지·로드밸런싱 운영을 맡는다. EC2는 파드가 실행될 CPU와
        메모리를 제공한다. 자동 관리라도 애플리케이션의 probe·리소스 요청·권한·가용성은 우리 책임이다.</p>
        <p>EKS 제어 평면, EC2, Auto Mode 관리 비용을 함께 평가한다. ECS나 단일 VM은 작은 서비스의
        운영 대안이지만 이번 저장소의 Kubernetes·Helm 운영 경험과 배포 형상을 그대로 쓰기 위해 EKS를
        선택했다. Auto Mode가 에이전트 메모리를 복제하거나 DB 스키마를 자동으로 고쳐주지는 않는다.</p>""",
        "관리형이라는 표현을 무운영으로 번역하지 않는다. 파드가 Pending이면 자원 요청과 노드 생성 권한을 함께 본다.",
        "platform/terraform/aws/eks.tf",
    ),
    chapter(
        "kubernetes",
        "Deployment·Service·Job이 나누는 책임",
        [
            "Deployment는 원하는 수의 파드를 유지",
            "Service는 파드 교체에도 유지되는 내부 주소",
            "Job은 DB 준비처럼 끝나야 하는 작업을 실행",
        ],
        """<p>파드는 교체될 수 있으므로 파드 IP를 설정 파일에 고정하지 않는다. Deployment는 선언된
        이미지와 replica 수를 유지하고 Service는 선택한 파드 집합에 DNS와 통신 경로를 제공한다.
        startup·readiness·liveness probe는 각각 기동 대기, 요청 수신 가능 여부, 재시작 필요성을 구분한다.</p>
        <p>DB bootstrap과 migration은 서버가 계속 실행하는 일이 아니다. Job으로 종료 상태를
        확인하고 성공한 뒤 애플리케이션을 배포한다. 롤아웃 성공은 요청이 동작한다는 최소 조건일 뿐이다.
        실제 API smoke와 의존 서비스 호출을 별도로 확인해야 한다.</p>""",
        "Ready인데 사용자가 실패하는 경우를 생각해 본다. 잘못된 API base URL이나 외부 제공자 키는 단순 probe만으로 못 잡을 수 있다.",
        "platform/helm/README.md",
    ),
    chapter(
        "ecr",
        "ECR와 변경하지 않는 이미지 태그",
        [
            "ECR은 컨테이너 이미지 저장소",
            "API·에이전트·migration 산출물을 식별",
            "같은 커밋의 검증한 이미지를 환경에 배포",
        ],
        """<p>Elastic Container Registry(ECR)는 레이어와 이미지 manifest를 저장하고 노드에 전달한다.
        이미지에는 실행 코드와 런타임을 넣고 비밀값은 넣지 않는다. SceneTrip에서는 Bazel 타깃을
        통해 패키지를 만들고 이미지 식별자를 배포 기록에 남긴다.</p>
        <p><code>latest</code>는 시간이 지나면 다른 내용을 가리켜 롤백과 감사가 어려워진다. 커밋
        SHA와 digest를 확인하고 검증한 산출물을 승격한다. 저장량·전송량·스캔 정책이 비용과 운영에
        영향을 준다. 다른 OCI registry도 가능하지만 EKS 노드 IAM과 같은 계정 경계를 활용하는 ECR을 쓴다.</p>""",
        "Git SHA는 소스 식별자이고 digest는 이미지 내용 식별자다. 재빌드한 두 이미지가 항상 같다고 가정하지 않는다.",
        "services/scene-api/BUILD.bazel",
    ),
    chapter(
        "ingress",
        "ALB·ACM·Route 53: HTTPS 진입점",
        [
            "ALB는 HTTP host·path를 읽고 gateway 대상을 선택",
            "ACM 인증서는 HTTPS 서버 신원을 증명",
            "Route 53은 사람이 쓰는 도메인을 진입점에 연결",
        ],
        """<p>Application Load Balancer(ALB)는 HTTP 요청 내용을 기준으로 대상 그룹에 요청을
        전달하는 관리형 로드밸런서다. ACM은 TLS 인증서를 관리하고 Route 53은 DNS 호스팅을 제공한다.
        인증서는 ALB의 리전·도메인·검증 상태가 맞아야 한다. ALB는 HTTPS 443만 열고 실제 API
        도메인과 <code>/v1</code> Prefix를 라우팅한다. HTTP 80 리스너·자동 redirect는 만들지 않는다.</p>
        <p>도메인과 인증서 ARN은 실제 계정 소유자가 준비할 입력이다. 예제 문자열이 있다고 DNS가
        이미 연결된 것이 아니다. ALB의 DNS 이름이 준비된 뒤 Route 53 Alias 등으로 도메인을
        연결한다. 도메인의 소유권·인증서·DNS와 ALB의 대상 상태는 각각 검증해야 한다. ALB와
        DNS 비용은 별개다. ALB가 사용자 인증을 자동으로 제공하지 않으며 설치 UUID의 한계도 남는다.</p>""",
        "인증서 오류·DNS 오류·HTTP 404를 같은 배포 실패로 묶지 않는다. curl에서 인증서 검증을 끄는 방식은 가르치지 않는다.",
        "platform/helm/README.md",
    ),
    chapter(
        "lb-choice",
        "왜 SceneTrip은 ALB를 선택하는가",
        [
            "HTTP API의 host·path 라우팅을 로드밸런서에서 표현",
            "NLB는 TCP·UDP 연결과 고정 IP 요구의 대안",
            "ALB 시간 + LCU 요금과 nginx 운영 비용을 함께 비교",
        ],
        """<table><thead><tr><th>관점</th><th>ALB</th><th>NLB</th></tr></thead><tbody>
        <tr><td>대상 선택</td><td>HTTP 계층의 host·path 등</td><td>네트워크 연결 중심</td></tr>
        <tr><td>SceneTrip 적합성</td><td>HTTPS API 라우팅을 명시</td><td>TCP·UDP 또는 고정 IP 요구 시 검토</td></tr>
        <tr><td>TLS</td><td>HTTPS listener에서 종료</td><td>TLS listener로 종료 가능</td></tr>
        <tr><td>사용량 요금</td><td>LCU</td><td>NLCU</td></tr></tbody></table>
        <p>현재 제품은 HTTP API이므로 ALB를 선택했다. 기존 NLB 설정의 이름만 바꾸는 전환이
        아니라 Service의 외부 노출을 없애고 Ingress가 ALB를 선언하도록 소유 관계를 바꾼다.
        ALB가 고정 IP를 그대로 제공하거나 언제나 더 저렴하다고 가정하지 않는다.</p>
        <p>LCU는 새 연결·활성 연결·처리 바이트·규칙 평가 같은 사용량을 반영한다. 실행 시간,
        리전, 전송량·공인 IPv4와 DNS 비용을 함께 계산한다. 기존 NLB를 사용 중이라면 Service 변경이
        삭제를 유발할 수 있으므로 새 진입점 준비·DNS 전환·복구 절차를 먼저 검토한다. 이 변경만으로
        무중단 전환을 보장하지 않는다.</p>""",
        "로드밸런서 이름을 외우기보다 우리 요청이 HTTP인지, 고정 IP가 필요한지, 어느 계층의 정책을 옮길지 설명하게 한다.",
        "docs/project/plans/alb-deployment.md",
    ),
    chapter(
        "alb-resources",
        "Auto Mode가 ALB를 만드는 선언",
        [
            "IngressClassParams → IngressClass → Ingress",
            "gateway Service는 ClusterIP, ALB 대상은 gateway Pod IP",
            "외부 주소는 Service가 아니라 Ingress 상태에서 확인",
        ],
        """<p><code>eks.amazonaws.com/v1</code>의 IngressClassParams는 internet-facing 배치,
        public subnet·인증서와 적용 namespace를 선언한다. 사용자 지정 ALB 보안 그룹은 Ingress의
        <code>security-groups</code> annotation으로 연결한다. IngressClass의 controller는
        <code>eks.amazonaws.com/alb</code>이며 Ingress의 <code>ingressClassName</code>이 이를
        선택한다. EKS Auto Mode가 이 선언을 처리하므로 별도 AWS Load Balancer Controller를
        설치하는 과정과 섞지 않는다.</p>
        <p>Ingress는 host와 경로, HTTPS listener·ACM·IP target·<code>/healthz</code> health
        check를 지정하고 gateway ClusterIP Service를 참조한다. 이 Service는 대상을 찾는 논리적
        연결이다. ALB 요청이 ClusterIP를 거쳐 다시 파드로 전달되는 경로로 이해하면 안 된다.
        주소는 <code>gateway</code> Ingress의 <code>status.loadBalancer.ingress[].hostname</code>에서
        확인한다. 배포 도구와 <code>just aws-verify dev</code>가 이 상태를 읽는다.</p>
        <p>배포 도구는 주소가 생길 때까지 제한된 시간 동안 기다린 뒤 AWS에서 로드밸런서 유형이
        application인지, 상태가 active인지, 대상이 healthy인지 검증한다. 주소만 존재하는 것과
        API가 정상 동작하는 것은 다르다. 다른 host·내부 경로·허용되지 않은 클라이언트도 거부되어야 한다.</p>""",
        "Service에 EXTERNAL-IP가 없다는 이유로 실패라고 판단하지 않는다. target-type=ip의 논리 참조와 실제 패킷 경로를 구분한다.",
        "platform/helm/scenetrip/templates/ingress.yaml",
    ),
    chapter(
        "alb-nginx",
        "ALB 뒤에서도 nginx를 유지하는 이유",
        [
            "nginx는 일반 프록시이며 Ingress Controller가 아님",
            "경로·파드별 요청/연결 수·본문 1 MiB·헤더 정책 유지",
            "X-Forwarded-For는 신뢰한 ALB가 붙인 마지막 주소만 사용",
        ],
        """<p>ALB를 추가해도 gateway의 공개 경로 허용, Actuator·내부 API 차단, 사용자 IP별
        요청·연결 제한, 본문 1 MiB 제한과 내부 서비스 토큰 제거가 자동 이전되지는 않는다.
        nginx 제한 카운터는 파드마다 독립적이다. PRD gateway 2개를 합쳐 전역 10r/s·20연결을
        보장하지 않으므로 전체 서비스의 한도나 사용자별 quota로 해석하지 않는다.
        nginx는 이 정책을 유지하는 애플리케이션 프록시다. ALB는 TLS·HTTP 라우팅·대상 상태를 맡는다.</p>
        <p>ALB는 <code>X-Forwarded-For</code> append 모드로 실제 연결 클라이언트 IP를 마지막에
        추가한다. nginx는 Terraform이 출력한 ALB public subnet CIDR만 trusted proxy로 설정하고
        <code>real_ip_recursive off</code>로 마지막 주소를 선택한다. 사용자가 앞에 넣은 주소를
        그대로 신뢰하지 않는다. 이 정규화 주소로 로그·요청 제한을 적용하고 API에 전달한다.</p>
        <p>헤더 검사만으로 직접 접근을 막을 수는 없다. ALB SG → 대상 SG의 8080 규칙,
        NetworkPolicy의 ALB subnet 제한과 gateway의 원래 연결 상대·허용 클라이언트 CIDR
        검증을 함께 둔다. 위조 XFF, ALB를 거치지 않은 요청, 허용 CIDR 밖의 요청을 테스트한다.
        이것은 사용자 인증의 대체가 아니다. Auto Mode에서 ALB의 일반 기능이 모두 지원된다고
        가정하지 말고 인증 기능을 추가할 때도 지원 여부와 애플리케이션 계약을 먼저 확인한다.</p>""",
        "realip 처리를 빼면 모든 요청이 ALB 주소로 기록되어 서로 다른 사용자가 같은 rate limit를 공유할 수 있다.",
        "platform/helm/scenetrip/templates/config.yaml",
    ),
    chapter(
        "rds",
        "RDS PostgreSQL: 데이터의 수명은 파드보다 길다",
        [
            "RDS는 PostgreSQL 운영·백업·복구를 지원",
            "PRD Multi-AZ는 장애 복구를 위한 구성",
            "DB 용량·연결 수·복원 시간을 별도로 검증",
        ],
        """<p>RDS는 데이터베이스 인스턴스의 운영 작업을 관리형 서비스로 제공한다. SceneTrip의 장소,
        검색어, 사용자별 코스와 장바구니는 PostgreSQL에 저장한다. 애플리케이션 Deployment를 바꾸어도
        DB를 함께 지우면 안 된다. DB subnet과 보안 그룹은 외부에서 직접 접속하지 못하도록 설계한다.</p>
        <p>비용은 인스턴스, 스토리지, I/O·백업 보존 및 Multi-AZ 선택에 영향을 받는다. 개발 중에는
        작은 Single-AZ로 절약하고 PRD에는 백업·삭제 보호를 둔다. 자체 PostgreSQL 운영은 대안이지만
        복구·패치·장애 대응을 팀이 맡는다. 복원이 가능한지는 복원 연습으로 확인해야 한다.</p>""",
        "백업이 켜져 있다는 것과 정해진 시간 안에 서비스가 복구된다는 것은 다르다. RPO와 RTO를 팀의 숫자로 정하게 한다.",
        "platform/terraform/aws/database.tf",
    ),
    chapter(
        "postgis",
        "PostGIS·pg_trgm과 한국어 검색 로케일",
        [
            "PostGIS: 위치·거리·공간 인덱스",
            "pg_trgm: 오타·부분 문자열 유사도 검색",
            "UTF8와 C.UTF-8 계열 로케일을 배포 전에 검증",
        ],
        """<p>PostGIS는 PostgreSQL에 공간 타입과 연산을 더하는 확장이고 <code>pg_trgm</code>은
        문자열의 trigram 비교와 인덱스를 제공한다. SceneTrip의 <code>GEOGRAPHY(Point,4326)</code>
        위치와 GiST 인덱스, 검색어 GIN 인덱스가 각각 이 확장에 의존한다. pgvector는 사용하지 않는다.</p>
        <p><code>lc_ctype=C</code>는 한글 문자 처리에서 문제가 될 수 있다. RDS가 지원하는 실제
        로케일 이름(C.UTF-8/C.utf8 계열)을 확인하고 UTF8 인코딩·확장·한국어 검색을 검증한다.
        기존 DB의 로케일을 env var 하나로 변경할 수 없다. 잘못 만들었다면 백업·새 DB·전환 계획이 필요하다.</p>""",
        "확장 설치 성공 후 한국어 예제 검색도 확인한다. 기능 테스트가 없는 인프라 정상 판정은 충분하지 않다.",
        "services/scene-api/src/main/resources/db/migration/V1__extensions.sql",
    ),
    chapter(
        "db-roles",
        "DB 관리자·migration·runtime 권한 분리",
        [
            "bootstrap: DB·역할·확장 준비",
            "migration: Flyway 스키마 변경",
            "runtime: API가 필요한 데이터 접근",
        ],
        """<p>RDS에서 PostGIS 초기 설정에는 관리 권한이 필요하다. 이 자격 증명을 API 서버가 계속
        사용하면 SQL 취약점 하나가 스키마·다른 사용자 권한까지 건드릴 수 있다. 초기 준비 작업과
        스키마 변경 작업, 평상시 데이터 접근을 서로 다른 사용자로 분리한다.</p>
        <p>배포는 bootstrap 검증, migration 성공, 애플리케이션 rollout의 순서로 진행한다.
        애플리케이션에서는 자동 Flyway 기동을 끄고 배포 전 Job이 전담한다. 기존 V1–V14 SQL을
        몰래 수정하면 checksum과 환경 간 이력이 어긋난다. 변경은 새 migration으로 추가한다.</p>""",
        "runtime 계정에 확장 설치 권한을 주어 실패를 숨기지 않는다. migration이 실패했으면 새 이미지를 배포하지 않는다.",
        "services/scene-api/src/main/java/com/mz2az/scenetrip/sceneapi/DbMigrate.java",
    ),
    chapter(
        "secrets",
        "Secrets Manager와 KMS",
        [
            "Secrets Manager는 비밀값 저장·버전·회전을 관리",
            "KMS는 암호화 키의 권한과 사용을 관리",
            "Terraform에 실제 API 키·DB 비밀번호를 넣지 않는다",
        ],
        """<p>Secrets Manager는 DB 비밀번호·DeepSeek 키·Kakao 키처럼 노출되면 안 되는 값을
        관리한다. KMS(Key Management Service)는 저장 데이터 암호화에 쓰는 키를 관리한다.
        암호화되어 있어도 복호화 권한을 너무 넓게 주면 안전하지 않다. 값 접근과 키 사용 권한을 함께 제한한다.</p>
        <p>배포 시 필요한 값만 Kubernetes Secret으로 전달하고 디버그 출력·쉘 tracing·임시 파일
        보관에 주의한다. Secret은 base64 인코딩 객체이지 그 자체가 비밀 저장소는 아니다. 서비스별
        저장·호출·키 비용이 있다. Parameter Store 같은 대안은 회전 요구와 접근 패턴을 비교해 결정한다.</p>""",
        "강의 화면에 실제 secret 값을 보여주지 않는다. API 서버는 DeepSeek 키가 필요 없다는 최소 권한 예를 든다.",
        "platform/terraform/README.md",
    ),
    chapter(
        "agent-state",
        "에이전트는 왜 replica가 1개인가",
        [
            "대화 이력은 Python 프로세스 메모리",
            "replica를 늘리면 같은 대화가 서로 다른 파드에 도착",
            "Recreate 배포와 재시작 시 대화 소실을 명시",
        ],
        """<p>trip-guide의 세션·대화 이력은 외부 저장소가 아니라 프로세스 메모리에 있다. replica를
        2로 늘렸다고 두 파드가 같은 대화를 공유하지 않는다. 단일 replica와 Recreate 전략은 동시에
        독립 세션 저장소 두 개가 살아 있는 상황을 피하지만 교체 중 짧은 중단과 기존 대화 소실을 허용한다.</p>
        <p>PRD의 네트워크와 DB가 여러 AZ여도 이 제약은 남는다. 고가용성이 필요하면 세션 저장소,
        멱등성, 동시 갱신 정책부터 설계해야 한다. Redis를 그림에 추가하는 것만으로 해결되지 않는다.
        앱의 대화 재시작 안내와 장애 시 오류 처리를 실제로 확인한다.</p>""",
        "에이전트를 재시작한 뒤 기존 대화가 어떻게 보이는지 DEV에서 확인한다. 강의에서도 중단 시간을 숨기지 않는다.",
        "agents/trip-guide/src/session.py",
    ),
    chapter(
        "timeouts",
        "타임아웃과 재시도: 중복 실행을 막기",
        [
            "에이전트 턴 30초 < API 40초 < 앱 50초",
            "내부 작업이 외부 요청보다 먼저 포기",
            "가이드 chat은 멱등하지 않아 무조건 재시도하지 않는다",
        ],
        """<p>타임아웃은 계층별로 독립된 설정처럼 보이지만 한 요청의 생명주기를 만든다. 바깥이
        먼저 끊기면 안쪽은 사용자가 실패로 본 요청을 계속 실행할 수 있다. SceneTrip은 모델 호출
        한 번의 상한과 전체 턴 예산을 구분하고 API·앱의 대기 시간을 바깥쪽으로 더 길게 둔다.</p>
        <p>chat은 토큰 비용과 대화 이력, 도구 효과를 남기므로 네트워크 오류 때 무조건 재시도하면
        중복 실행할 수 있다. gateway 타임아웃도 이 예산과 맞춘다. 평균 시간만 보지 말고 p95와
        외부 제공자 지연을 관측한 뒤 실제 지연 예산을 조정한다. ALB의 idle timeout 60초는
        통신이 없는 시간의 제한이며 전체 요청이 반드시 60초 안에 끝난다는 뜻은 아니다.</p>""",
        "504가 나왔다고 즉시 재전송했을 때 장바구니 변경이 두 번 일어나는 상황을 토론한다.",
        "agents/trip-guide/config/model.json",
    ),
    chapter(
        "observability",
        "CloudWatch와 SigNoz가 보는 것",
        [
            "CloudWatch는 AWS 자원·제어 평면의 지표와 로그",
            "SigNoz는 앱 로그·메트릭·트레이스 탐색",
            "로컬 SigNoz가 AWS에 자동 설치되지는 않는다",
        ],
        """<p>CloudWatch는 AWS의 모니터링 서비스로 RDS·EKS 제어 평면 등 인프라 상태를 확인하는
        데 쓰인다. SigNoz는 OpenTelemetry로 보낸 애플리케이션 신호를 탐색하는 관측성 도구다.
        둘은 반드시 하나를 버려야 하는 경쟁 관계가 아니라 서로 다른 계층을 함께 볼 수 있는 선택지다.</p>
        <p>로컬 kind의 collector DNS는 AWS에서 존재하지 않는다. 원격 collector와 보존·접근 정책을
        준비하기 전에는 OTEL을 비활성화하고 연결이 준비된 뒤 명시적으로 켠다. 로그 수집·보존·질의량과
        SigNoz 운영 스토리지 비용을 계산한다. 이식만으로 원격 end-to-end trace가 검증됐다고 말하지 않는다.</p>""",
        "RDS CPU는 정상인데 chat p95만 느리다면 앱 트레이스와 제공자 지연이 필요하다. 로그에 질문 원문이나 키를 남기지 않는다.",
        "platform/kubernetes/README.md",
    ),
    chapter(
        "build",
        "Bazel과 just로 같은 산출물 만들기",
        [
            "just는 사람이 실행하는 명령 창구",
            "Bazel은 빌드·테스트·이미지 타깃 그래프",
            "언어별 도구를 따로 실행한 결과를 배포 근거로 삼지 않는다",
        ],
        """<p>같은 명령으로 개발과 CI를 검증하려면 명령 인터페이스와 실제 빌드를 구분해야 한다.
        <code>just</code>는 레시피를 제공하고 Bazel이 소스·도구·의존성을 추적한다. 새로운 파일을
        추가하면 BUILD 타깃에도 반영한다. 고정된 도구와 입력을 사용해 사람마다 결과가 달라지는 일을 줄인다.</p>
        <pre>just check
just test //tools/education:unit_test
just education-check</pre>
        <p>교육 생성 검사는 체크인된 HTML·SVG·출처 해시가 정본에서 다시 만들어지는지 비교한다.
        이것은 실제 AWS plan이나 클라우드 배포 테스트를 대체하지 않는다.</p>""",
        "검사 실패를 환경 문제와 변경 회귀로 분리해 기록한다. 테스트를 빼고 통과한 것처럼 보이게 하지 않는다.",
        "tools/just/docs.just",
    ),
    chapter(
        "bootstrap",
        "첫 배포 전에 준비할 입력",
        [
            "실제 계정·리전·AZ·허용 CIDR 확인",
            "state backend·OIDC 역할·GitHub Environment 준비",
            "인증서·DNS·비밀값은 소유자가 발급·설정",
        ],
        """<p><code>platform/environments/dev</code>와 <code>prd</code>의 예제 파일은 템플릿이다.
        REPLACE 값은 실제 배포 입력이 아니며 검증에서 거부해야 한다. 계정 ID와 AWS 로그인 대상,
        backend key, 배포 역할의 환경이 모두 같은지 확인한다.</p>
        <p>최초 bootstrap은 자신이 만들 역할로 자신을 인증할 수 없으므로 기존 관리 권한이 필요하다.
        이 단계에서 만들어진 state 저장소·OIDC 신뢰와 이후 인프라 변경 역할을 구분한다. GitHub
        Environment 승인 규칙은 YAML만으로 만들어지지 않으며 저장소 설정도 확인해야 한다.</p>""",
        "실제 키를 예제 파일에 채워 커밋하지 않는다. 입력이 없는 수강생은 렌더·테스트·구성도 실습까지만 수행한다.",
        ".github/workflows/aws-bootstrap.yml",
    ),
    chapter(
        "deploy",
        "수동 배포의 순서와 중단 조건",
        [
            "입력 검증 → 인프라 plan 검토·apply",
            "이미지 → DB bootstrap·migration → Helm rollout",
            "readiness → 제한된 HTTPS smoke → 기록",
        ],
        """<p>배포는 AWS workflow의 수동 실행에서 환경과 커밋을 선택한다. 적용 전에 Terraform
        계획의 삭제·교체·공개 접근·DB 변경을 읽는다. 이미지와 환경 값이 정해진 뒤 DB 준비와
        migration을 실행하고 성공한 경우에만 애플리케이션을 rollout한다.</p>
        <p>DB 실패를 무시하고 다음 단계로 가거나 smoke를 주석 처리해서 배포를 성공 처리하지 않는다.
        이미지 식별자, migration 버전, 실행 URL, 환경과 검증 결과를 배포 기록에 남긴다. DEV 성공은
        PRD 성공의 증거가 아니며 PRD의 다른 CIDR·권한·DB 크기에서도 검증해야 한다.</p>
        <p>사용을 마친 환경은 별도 <code>aws-destroy.yml</code>의 <b>AWS 수동 삭제</b>로 정리한다.
        먼저 <code>scope: service</code>의 <code>operation: plan</code>을 검토하고, 실제 삭제는
        <code>destroy</code>와 <code>DELETE &lt;env&gt; &lt;12자리계정&gt;</code> 확인값으로 실행한다.
        서비스 삭제가 끝난 뒤에만 <code>scope: bootstrap</code>을 별도 실행한다. Ingress·ALB가
        사라지는 동안 EKS를 유지해야 하며, 실패했다고 bootstrap을 먼저 지우지 않는다.
        자세한 입력과 중단 조건은 <a href="../ops/aws-teardown.md">삭제 운영 절차</a>를 따른다.</p>""",
        "배포·삭제 모두 실패한 단계가 이미 바꾼 상태를 확인한다. 삭제는 service 완료 뒤 bootstrap을 별도 실행하며 PR·머지로 시작되지 않는다.",
        ".github/workflows/aws-deploy.yml",
    ),
    chapter(
        "validation",
        "검증은 네트워크에서 제품 동작까지",
        [
            "정적 검사: 환경·차트·권한·출처",
            "실행 검사: probe·DB migration·HTTP 경로",
            "제품 검사: 검색·한글·가이드·인증 경계",
        ],
        """<p>프로브는 내부 경로 <code>/v1/actuator/health/readiness</code>와 liveness를 사용한다.
        외부 gateway는 Actuator를 숨겨야 하므로 외부 health 차단도 정상 동작이다. 내부 준비 상태와
        외부 공개 API smoke를 각각 확인한다. 요청에 따른 정상 오류(401/404)와 인프라 오류를 구별한다.</p>
        <p>한국어 검색·좌표 조회·에이전트 대화는 서로 다른 의존성을 검증한다. 테스트를 실제 제공자에
        연결하면 비용과 상태를 바꿀 수 있으므로 단위 테스트에서는 가짜 제공자를 사용하고 DEV smoke는
        범위가 작은 승인된 데이터로 수행한다. 모든 검증 결과에는 실행한 환경을 적는다.</p>
        <p>ALB 생성 뒤 DNS를 연결했다면 같은 환경 입력과 권한을 준비하고
        <code>just aws-verify dev</code> 또는 <code>just aws-verify prd</code>로 재검증한다.
        기존 Terraform output·Ingress·ALB/대상 상태를 읽고 HTTPS 인증서·공개 읽기 API·내부 경로
        차단을 확인한다. 이 명령은 apply·이미지 push·DB migration을 다시 실행하지 않는다.</p>""",
        "모든 경로에 200만 기대하면 권한 검사를 우회하게 된다. 등록하지 않은 기기의 보호 기능 요청은 거부되어야 한다.",
        "services/scene-api/src/main/resources/application.yaml",
    ),
    chapter(
        "rollback",
        "애플리케이션 롤백과 DB 복구",
        [
            "이전 이미지로 복귀해도 DB 스키마는 남는다",
            "호환되는 migration부터 설계",
            "삭제 후 재배포도 DB·Secret·이미지 복구를 따로 준비",
        ],
        """<p>Helm이나 Deployment의 이전 버전으로 돌아가도 이미 성공한 Flyway migration은
        되돌아가지 않는다. 새 컬럼 추가 후 구버전도 동작하도록 확장·전환·정리 순서를 나누어야 한다.
        파괴적인 down migration을 자동 배포 rollback에 묶지 않는다.</p>
        <p>DB 손상이 있으면 마지막 정상 시점과 데이터 손실 범위를 정하고 복원한 DB에 검증한 뒤
        연결을 전환한다. 삭제 보호는 실수 방지 장치이며 복원 전략 전체는 아니다. agent 재시작은
        대화 메모리를 잃을 수 있어 사용자 안내도 필요하다. 복구 소요 시간은 연습 결과로 기록한다.</p>
        <p>환경 삭제의 기본 <code>snapshot_policy: retain</code>은 최종 RDS 스냅샷을 남기지만
        새 RDS가 이를 자동 복원하지는 않는다. <code>discard</code>도 기존 수동 스냅샷까지 지우는
        선택은 아니다. ECR 이미지와 저장소는 삭제하므로 재발행하고 새 ALB 주소로 DNS를 갱신한다.
        삭제 예약된 Secret은 즉시 같은 이름으로 만들 수 없다. 유예 안에 복원·state 반영을
        계획하거나 영구 삭제 완료를 기다린다. bootstrap 삭제 시 기본 <code>purge_state: false</code>로
        보존한 S3도 재사용·가져오기 계획이 필요하며, <code>true</code>로 지운 과거 state는 복구할 수 없다.</p>""",
        "컬럼을 삭제한 새 버전에서 이전 이미지로 돌아가는 사고를 사례로 든다. 안전한 전진 수정이 더 나을 때도 있다.",
        "services/scene-api/src/main/resources/db/migration/V14__poi_naver.sql",
    ),
    chapter(
        "cost",
        "비용을 만드는 것은 트래픽만이 아니다",
        [
            "EKS·NAT·DB·ALB에는 유휴 시간 비용도 존재",
            "로그·이미지·백업은 보존 기간이 비용을 키움",
            "수동 삭제 후에도 남은 스냅샷·로그·runner 비용 확인",
        ],
        """<p>요청이 없어도 클러스터 제어 평면, NAT, 로드밸런서, DB는 비용이 생길 수 있다.
        DeepSeek 모델 호출은 입력·출력 사용량, 외부 데이터 API는 제공자 정책의 영향을 받는다.
        리전·버전·가격표에 따라 달라지므로 이 문서는 고정 월 요금을 약속하지 않는다.</p>
        <p>견적은 리전과 실행 시간, 노드 자원, DB 클래스·스토리지, NAT 처리량, 로그 보존, 백업과
        이미지 수를 입력해 만든다. DEV를 PRD와 같은 규모로 복제할 필요는 없지만 공유 NAT의 장애
        영향은 알고 선택해야 한다. 예산 알림·태그·주기적 미사용 자원 점검의 담당자를 정한다.</p>
        <p><a href="../ops/aws-teardown.md">수동 삭제</a>로 EKS·RDS·ALB·NAT 등의 상시 비용을
        줄일 수 있다. 기본값은 최종 DB 스냅샷 보존과 state S3 보존이다. 남은 스냅샷·S3 버전,
        Terraform 관리 밖 로그·외부 runner·추가 디스크·DNS 비용은 별도로 확인한다.
        Secret은 기존 DEV 7일·PRD 30일 유예로 삭제 예약하며, 예약된 동안 과금되지 않지만 값에
        접근할 수 없다. 삭제 workflow 성공이 계정 전체의 비용 0을 뜻하지는 않는다.</p>""",
        "월간 표에는 수량·시간·데이터량부터 적는다. 삭제 전 보존 정책, 삭제 후 남은 리소스와 담당자를 기록한다.",
        "platform/environments/README.md",
    ),
    chapter(
        "incident",
        "장애 실습: 증상에서 경계를 좁히기",
        [
            "이미지 실패: 태그·아키텍처·ECR 권한",
            "DB 실패: DNS·SG·자격 증명·확장·로케일",
            "가이드 실패: agent·NAT·키·제공자·턴 예산",
        ],
        """<table><thead><tr><th>증상</th><th>먼저 확인</th><th>성공 기준</th></tr></thead><tbody>
        <tr><td>ImagePullBackOff</td><td>실제 image URI·digest·노드 권한</td><td>대상 이미지로 파드 기동</td></tr>
        <tr><td>migration 실패</td><td>Job 로그·DB 역할·확장·checksum</td><td>수정 후 Job 성공, 그 전 rollout 중단</td></tr>
        <tr><td>가이드 503</td><td>agent 내부 DNS·키·외부 연결</td><td>상한 안에 응답 또는 명확한 오류</td></tr>
        <tr><td>외부 403/연결 차단</td><td>허용 CIDR·gateway 규칙·등록 여부</td><td>허용 요청만 통과</td></tr></tbody></table>
        <p>DEV에서만 작은 실패를 의도적으로 만들고 관측 결과·원인·복구를 기록한다. secret 원문을
        로그로 출력하거나 PRD의 방화벽을 넓혀 진단하지 않는다.</p>""",
        "한 조는 장애를 만들고 다른 조는 증상만 보고 진단한다. 정답은 명령 개수가 아니라 근거 있는 원인과 복구 검증이다.",
        "docs/project/plans/aws-dev-prd-port.md",
    ),
    chapter(
        "diagrams",
        "구성도와 출처 해시를 읽는 법",
        [
            "tfvars·HCL·앱 설정에서 구성 요약을 생성",
            "SHA256으로 어떤 입력의 그림인지 확인",
            "AWS 실시간 인벤토리·terraform plan 결과와 구별",
        ],
        """<p>생성된 DEV·PRD 구성도는 환경 예제와 구현 파일을 읽어 만든 구성 시각화다. 자원 선언
        참조 그림은 HCL에 적힌 직접 참조를 표시한다. 별도의 <a href="diagrams/terraform-graph.dot">엔진 DOT</a>는
        고정 Terraform을 임시 복사본에서 실행한 실제 <code>graph</code> 출력이다. 어느 쪽도 실제 ALB 주소나
        Auto Mode 노드 수를 보여 주는 배포 후 인벤토리는 아니다.</p>
        <pre>just education-generate
just education-check</pre>
        <p>실제 엔진 DOT는 <code>just education-graph</code>로 만들고
        <code>just education-graph-check</code>로 검사한다. backend는 임시 복사본에서만 제거하며
        AWS에 접속하지 않는다. 도구·명령·입력·DOT의 출처는 별도 manifest에 기록한다.
        Terraform은 VPC·EKS·RDS를 만들고 ALB는 Helm Ingress를 보고 Auto Mode가 생성한다.
        그러므로 실제 Terraform 엔진 DOT에 ALB가 직접 등장하지 않아도 누락이 아니다.
        교육 구성도는 Helm의 Ingress·nginx·NetworkPolicy 소스도 출처 해시에 포함한다.</p>
        <p>입력이나 생성 코드를 바꾸면 그림과 출처 manifest도 다시 생성한다. timestamp 없이 정렬된
        입력·출력으로 동일 소스에서 같은 결과를 만든다. 검증은 drift가 있으면 실패하며 기존 파일을 고치지 않는다.</p>""",
        "입력 NAT 정책을 바꿔 그림과 검사 결과가 함께 달라지는지 시연한다. 그림이 예쁘다는 이유로 실제 배포 상태라고 설명하지 않는다.",
        "tools/education/README.md",
    ),
    chapter(
        "lab",
        "실습: 코드에서 배포 근거 만들기",
        [
            "로컬: API·DB·관측성을 먼저 연결",
            "정적: DEV·PRD 입력과 구성도 검증",
            "클라우드: 수동 적용·smoke·기록, 종료 시 삭제 계획 검토",
        ],
        """<ol><li><code>just doctor</code>와 <code>just --list</code>로 도구와 명령을 확인한다.</li>
        <li><code>just cluster-up</code>, <code>just deploy postgres local</code>, <code>just image scene-api</code>,
        <code>just deploy scene-api local</code>, <code>just health</code>로 로컬 흐름을 익힌다.</li>
        <li><code>just education-check</code>를 실행하고 DEV·PRD SVG와 입력 파일의 값을 대조한다.</li>
        <li>계정 소유자는 환경 예제·backend·인증서·OIDC·비밀값을 준비하고 수동 workflow의 검증 단계를 진행한다.</li>
        <li>ALB 주소를 확인해 DNS를 연결한 뒤 <code>just aws-verify dev</code>로 재검증한다.</li>
        <li>배포 후 이미지 SHA, migration 상태, 한글 검색, 가이드, 허용 CIDR, 외부 Actuator 차단 결과를 남긴다.</li></ol>
        <p>계정이나 인증서가 없으면 3단계까지 수행한 결과만 제출한다. 실행하지 않은 단계를 성공으로 쓰지 않는다.</p>
        <p>실습용 AWS 환경을 정리할 때는 <code>just aws-destroy-plan dev retain</code>으로
        삭제 범위와 보존 정책부터 검토한다. 삭제 실행은 별도 확인을 거친
        <code>just aws-destroy dev retain</code>이며 서비스 삭제 완료 뒤에만
        <code>just aws-bootstrap-delete-plan dev false</code>와
        <code>just aws-bootstrap-delete dev false</code>를 별도로 진행한다.
        이 명령들은 실제 AWS 자격 증명이 필요하고, 삭제에는 <code>AWS_DELETE_CONFIRMATION</code>도
        설정해야 한다. 교육 자료 생성·단위 테스트에서 호출하지 않는다. workflow URL·스냅샷 식별자·
        state 보존 여부와 남은 비용 항목을 결과에 기록한다.</p>""",
        "실습 제출물은 스크린샷만이 아니라 입력·검증 결과·남은 제약이다. 로컬 데이터 seed가 필요한 경우 README의 승인된 데이터 경로를 따른다.",
        "docs/education/k8s-observability-class.html",
    ),
    chapter(
        "sources",
        "읽을 자료와 학습 확인",
        [
            "AWS 공식 문서의 정의와 저장소의 선택을 구별",
            "서비스를 추가하기 전에 해결할 문제를 설명",
            "배포·복구·비용·보안의 근거를 코드로 찾기",
        ],
        """<p>설명에 사용한 공식 자료: <a href="https://docs.aws.amazon.com/eks/latest/userguide/automode.html">EKS Auto Mode</a>,
        <a href="https://docs.aws.amazon.com/eks/latest/userguide/auto-configure-alb.html">Auto Mode ALB</a>,
        <a href="https://docs.aws.amazon.com/elasticloadbalancing/latest/application/introduction.html">ALB의 역할</a>,
        <a href="https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html">ALB XFF 처리</a>,
        <a href="https://nginx.org/en/docs/http/ngx_http_realip_module.html">nginx realip</a>,
        <a href="https://aws.amazon.com/elasticloadbalancing/pricing/">ELB 비용</a>,
        <a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html">IAM OIDC</a>,
        <a href="https://developer.hashicorp.com/terraform/language/backend/s3">Terraform S3 backend</a>,
        <a href="https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.PostGIS.html">RDS PostGIS</a>.</p>
        <p>확인 질문: DEV NAT 장애는 어디까지 영향을 주는가? PRD에도 대화 소실이 남는 이유는 무엇인가?
        DB migration이 성공한 뒤 이미지만 롤백하면 어떤 위험이 있는가? OIDC가 성공했는데 EKS 접근이
        거부될 수 있는 이유는 무엇인가? 각각의 답을 구현 파일과 연결해 설명하면 과정을 마친 것이다.</p>""",
        "자료 확인 기준일은 2026-09-20이다. 가격·지원 버전·관리형 정책은 배포 시 공식 문서로 다시 확인한다.",
        "docs/education/README.md",
    ),
]
