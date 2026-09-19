"""로컬 kind·관측성 강의. 클라우드 명령과 섞지 않는다."""

from course_content import chapter

LOCAL_CHAPTERS = [
    chapter(
        "local-start",
        "SceneTrip 로컬 Kubernetes와 관측성",
        [
            "노트북의 kind에서 API·DB·SigNoz를 연결",
            "AWS DEV·PRD는 별도 환경과 배포 경로",
            "동작하는 요청과 관측 신호로 확인",
        ],
        """<p>이 자료는 로컬 학습 과정이다. kind는 Docker 컨테이너를 노드로 사용하는 Kubernetes
        클러스터이며 AWS 계정이나 ALB·RDS를 만들지 않는다. 로컬 준비·배포·검증·장애 분석을 2~3시간에
        익히고 원격 과정은 <a href="aws-eks-course.html">AWS 상세 강의</a>로 이어간다.</p>
        <p>기존 자료의 제거된 smoke 명령과 잘못된 health 주소를 현재 저장소에 맞춰 바꿨다.
        API는 호스트 8081, SigNoz는 호스트 8080이다. 두 주소를 바꾸어 부르면 다른 서비스가 응답한다.</p>""",
        "참석자의 Docker 실행 여부부터 확인한다. 클라우드 자격 증명 없이 이 과정을 진행할 수 있다.",
        "platform/kind/cluster.yaml",
    ),
    chapter(
        "local-product",
        "무엇을 컨테이너로 실행하는가",
        [
            "scene-api는 서버 8080, 사용자 기기의 앱과 구별",
            "PostgreSQL은 장소·코스·검색 데이터를 보관",
            "trip-guide는 별도 Python 런타임",
        ],
        """<p>iOS·Android 앱은 각각 시뮬레이터나 실제 기기에서 실행한다. kind에 올리는 API가 앱의
        검색·코스·가이드 요청을 받는다. 로컬 PostgreSQL은 PostGIS·pg_trgm을 준비하고 SigNoz는
        앱의 관측 신호를 모은다. 서버 파드 내부 8080과 호스트 8081은 같은 포트 번호가 아니다.</p>
        <p>에이전트는 API와 별개 프로세스다. API의 localhost는 노트북이 아니라 API 파드 자신을
        가리킨다. 로컬 기본 설정을 보고 agent가 클러스터 안에 자동 배포됐다고 가정하지 말고 실행
        방식과 <code>SCENETRIP_GUIDE_AGENT_BASE_URL</code>을 확인한다.</p>""",
        "클러스터가 떠도 가이드만 실패할 수 있는 이유를 프로세스 경계로 설명한다.",
        "agents/trip-guide/README.md",
    ),
    chapter(
        "local-declarative",
        "선언한 상태를 계속 맞추는 Kubernetes",
        [
            "Deployment: 유지할 이미지와 파드 수",
            "Service: 파드가 바뀌어도 같은 주소",
            "ConfigMap·Secret: 코드와 실행 설정 분리",
        ],
        """<p>컨테이너를 한 번 실행하는 것과 장애 이후에도 계속 실행되도록 관리하는 것은 다르다.
        Kubernetes는 선언한 상태와 실제 상태를 비교해 파드를 생성·교체한다. 파드를 수동으로
        지웠다가 다시 생기는 것은 오류가 아니라 Deployment가 책임을 수행하는 것이다.</p>
        <p>ConfigMap은 주소·스위치 같은 설정, Secret은 자격 증명 전달에 사용한다. 비밀값을
        이미지나 Git에 넣지 않는다. Service의 선택 라벨이 파드와 다르면 파드는 Running이어도
        요청이 도달하지 않는다. 상태뿐 아니라 연결 관계도 확인해야 한다.</p>""",
        "파드를 지우는 실습은 로컬에 한정한다. DB 볼륨과 파드 수명을 혼동하지 않는다.",
        "platform/kubernetes/scene-api/deployment.yaml",
    ),
    chapter(
        "local-tools",
        "도구와 명령 표면 확인",
        [
            "just doctor: 필수 도구 확인",
            "just cluster-doctor: 클러스터 상태 확인",
            "just --list: 실제 제공하는 명령 확인",
        ],
        """<pre>just doctor
just --list
just cluster-doctor</pre>
        <p>준비되지 않은 도구나 Docker를 먼저 해결하면 이후 오류를 배포 코드 문제와 구별할 수 있다.
        출력의 성공/실패를 읽고 설치 가이드가 요구하는 버전을 확인한다. 이 저장소의 정식 빌드와
        테스트는 Bazel 타깃을 just 레시피로 실행한다.</p>
        <p>인터넷에서 찾은 raw 명령을 모아서 별도 운영 경로를 만들지 않는다. 자주 필요한 기능은
        적절한 tools/just 모듈에 문서화된 레시피로 추가해 팀과 CI가 같은 경로를 사용하게 한다.</p>""",
        "자신의 셸에서 목록을 직접 보게 한다. 슬라이드가 아니라 실행 가능한 레시피가 명령의 진실이다.",
        "tools/just/dev.just",
    ),
    chapter(
        "local-cluster",
        "kind와 포트 매핑",
        [
            "클러스터 이름 scenetrip",
            "8080 → NodePort 30080 → SigNoz",
            "8081 → NodePort 30081 → scene-api",
        ],
        """<pre>just cluster-up
just cluster-status</pre>
        <p>kind의 호스트 포트 매핑은 클러스터 생성 때 결정된다. 이름만 같은 클러스터를 다른 방법으로
        만들면 파드는 정상이어도 호스트에서 API에 연결되지 않을 수 있다. 같은 호스트 포트를 다른
        프로젝트나 프로세스가 쓰는지도 확인한다.</p>
        <p>로컬 NodePort를 AWS ingress로 복사하지 않는다. AWS에서는 제한된 ALB HTTPS 진입과
        private workload를 사용한다. kind 노드 한 개를 PRD의 여러 AZ와 동등한 가용성으로 표현하면 안 된다.</p>""",
        "API 포트 충돌을 재현한다면 다른 프로젝트를 끄기 전에 소유자를 확인한다.",
        "platform/kind/cluster.yaml",
    ),
    chapter(
        "local-db",
        "DB 준비와 migration",
        [
            "just deploy postgres local로 DB를 먼저 배포",
            "UTF8·한글 처리 로케일 검증",
            "로컬 초기화와 원격 DB 복구는 다른 작업",
        ],
        """<p>scene-api는 기본으로 시작할 때 Flyway migration을 적용한다. PostgreSQL이 준비되기
        전에 API가 시작되면 연결·migration 실패가 발생할 수 있다. 로컬 배포의 DB 대기와 migration
        이력을 확인한다. RDS 과정에서는 이 역할을 배포 전 Job으로 분리한다.</p>
        <pre>just deploy postgres local</pre>
        <p><code>cluster-up</code>은 kind·SigNoz를 준비한다. DB는 이 명령으로 따로 배포한다.
        다음 장에서 API를 배포해 migration이 완료된 뒤 DB 구조를 조회한다.</p>
        <p>한국어 검색은 문자열 로케일에 영향을 받는다. 데이터가 있는데 검색되지 않으면 데이터
        개수만 보지 말고 검색어·인덱스·로케일도 확인한다. 삭제·재생성 레시피는 로컬 데이터도 지우므로
        단순 진단 과정에 섞지 않는다.</p>""",
        "seed 파일은 수집한 데이터 출처를 확인한 뒤 사용한다. 빈 DB에서 검색 결과가 없는 것은 배포 실패와 다를 수 있다.",
        "platform/kubernetes/postgres/configmap.yaml",
    ),
    chapter(
        "local-deploy",
        "빌드 → kind 적재 → 배포",
        [
            "just image scene-api: 이미지 준비",
            "just deploy scene-api local: 매니페스트와 rollout",
            "코드 변경 뒤 just update scene-api",
        ],
        """<pre>just image scene-api
just deploy scene-api local
just health
just db-migrations
just db-schema</pre>
        <p>호스트 Docker의 이미지와 kind 노드가 보는 이미지 저장소는 구분해야 한다. 로컬 레시피는
        저장소의 빌드 타깃을 사용한 뒤 kind에 이미지를 적재하는 경로를 제공한다. 잘못된 이미지
        이름이나 pull 정책이면 인터넷에서 이미지를 찾다가 ImagePullBackOff가 날 수 있다.</p>
        <p>조회 실습에는 데이터도 필요하다. 승인된 CSV 경로를 준비한 뒤
        <code>just seed &lt;CSV 경로&gt;</code>와 <code>just db-refresh-search</code>로 수집 데이터와
        검색어를 준비한다. 개인 데이터나 비밀값을 예제 파일에 복사하지 않는다.</p>
        <p><code>just update scene-api</code>는 코드 수정 후 로컬 개발 반복에 쓴다. 원격 DEV·PRD의
        ECR push·migration·Helm 적용은 별도 AWS workflow를 사용한다.</p>""",
        "로컬 이미지 이름만 바꿔 PRD에 배포하지 않는다. 실행한 레시피의 대상 환경을 먼저 읽는다.",
        "tools/just/k8s.just",
    ),
    chapter(
        "local-health",
        "health 주소와 probe의 의미",
        [
            "/v1/actuator/health/liveness",
            "/v1/actuator/health/readiness",
            "startup은 느린 초기 기동을 기다린다",
        ],
        """<p>Spring의 context path가 <code>/v1</code>이므로 Actuator도 그 아래에 있다.
        <code>/actuator/health</code>에 404가 난다고 파드가 죽었다고 판단하면 안 된다. liveness는
        프로세스를 다시 시작해야 하는지, readiness는 요청을 받아도 되는지를 구분한다.</p>
        <pre>just health
just logs scene-api</pre>
        <p>외부 제공자의 일시 장애를 liveness에 묶으면 정상 프로세스가 계속 재시작할 수 있다.
        AWS gateway에서는 Actuator를 공개하지 않으며 kubelet의 내부 probe와 외부 API smoke를
        별개로 확인한다.</p>""",
        "잘못된 경로의 404와 실제 연결 거부의 차이를 설명한다. health 레시피 출력도 사람이 상태값을 읽어야 한다.",
        "services/scene-api/src/main/resources/application.yaml",
    ),
    chapter(
        "local-config",
        "localhost와 비밀값의 범위",
        [
            "파드 안 localhost는 그 파드 자신",
            "Service DNS는 같은 클러스터의 통신 주소",
            "설치 UUID는 비밀 인증 토큰이 아니다",
        ],
        """<p>노트북에서 <code>localhost:8081</code>은 kind 포트 매핑이지만 파드 안에서는 다른 의미다.
        API와 에이전트 사이에는 해당 환경에 맞는 접근 주소를 설정한다. 로컬 편의를 위해 사용한
        계정이나 등록 우회 설정을 원격 환경에 복사하지 않는다.</p>
        <p><code>X-Device-Id</code>는 설치를 식별하는 값이며 안전한 로그인 증명이 아니다. 원격
        환경은 등록 판정을 유지하고 ingress CIDR을 제한한다. API 키는 Secret을 통해 주입하고
        로그·스크린샷·커맨드 이력으로 노출하지 않는다.</p>""",
        "설정이 없을 때 명확한 기능 오류를 내는 것과 전체 서버가 죽는 것을 구별해 본다.",
        "docs/api/auth.md",
    ),
    chapter(
        "local-signals",
        "로그·메트릭·트레이스의 질문",
        [
            "로그: 어떤 사건이 발생했는가",
            "메트릭: 얼마나 자주·얼마나 오래인가",
            "트레이스: 요청 시간이 어느 구간에서 쓰였는가",
        ],
        """<p>로그만으로 오류 사례를 읽을 수 있지만 서비스 전체의 비율과 지연 분포를 파악하기는
        어렵다. 메트릭은 요청률·오류율·지연을 요약하고 트레이스는 요청의 호출 구간을 연결한다.
        trace ID가 연결되면 특정 오류 로그에서 관련 요청의 타이밍을 확인할 수 있다.</p>
        <p>개인정보·API 키·authorization header·사용자 질문 원문을 관측성이라는 이유로 그대로
        저장하지 않는다. 라벨에 사용자 ID나 모든 URL 값을 넣으면 cardinality와 저장 비용도 커진다.
        운영 질문에 필요한 제한된 신호를 설계한다.</p>""",
        "로그 한 줄과 p95 숫자가 각각 답할 수 없는 질문을 예로 든다.",
        "services/scene-api/BUILD.bazel",
    ),
    chapter(
        "local-otel",
        "OpenTelemetry와 OTLP",
        [
            "OpenTelemetry는 계측과 신호 전달의 표준",
            "OTLP는 수집기로 보내는 프로토콜",
            "service.name은 scenetrip-scene-api",
        ],
        """<p>애플리케이션은 계측으로 신호를 만들고 collector는 이를 수신·처리·전달한다. Java
        에이전트와 애플리케이션 로그 설정이 하는 일을 구분하면 누락이나 중복 수집을 진단하기 쉽다.
        동일 서비스 이름을 사용해 로그·트레이스·메트릭에서 찾을 대상을 일관되게 한다.</p>
        <p>수집기 endpoint의 프로토콜·포트가 맞는지 확인한다. 컨테이너 안에서 노트북 localhost를
        collector로 지정하면 신호는 도착하지 않는다. OTEL 변수만 설정했다고 agent까지 모든 신호가
        자동 연결된 것은 아니므로 실제 수집 결과로 범위를 확인한다.</p>""",
        "API 정상 응답과 텔레메트리 수집 성공은 다른 검사다. 수집기 장애가 곧 제품 장애가 되지 않도록 설정을 살핀다.",
        "platform/kubernetes/scene-api/configmap.yaml",
    ),
    chapter(
        "local-signoz",
        "SigNoz에서 신호 찾기",
        [
            "http://localhost:8080 은 로컬 관측 UI",
            "조회 시간 범위와 service.name을 먼저 확인",
            "설치 성공·수집 성공·질의 성공을 각각 확인",
        ],
        """<pre>just signoz
just signoz-status
just signoz-verify scenetrip-scene-api 10</pre>
        <p>처음 설치한 SigNoz는 UI의 초기 등록 단계를 마쳐야 한다. 키나 비밀번호를 강의 코드에
        써 넣지 않는다. 요청이 발생한 시각과 조회 시간 범위를 맞추고 서비스 이름을 선택해 로그와
        트레이스를 찾는다.</p>
        <p>아무것도 보이지 않으면 트래픽 발생, exporter endpoint, collector 수신, 저장·질의 순으로
        경계를 좁힌다. AWS의 CloudWatch 지표가 있다고 이 로컬 SigNoz 화면에도 신호가 나타나는 것은 아니다.</p>""",
        "실습 종료 시 해당 요청의 실제 로그나 trace ID를 기록하게 한다. 단순히 UI가 열리는 것을 수집 성공으로 평가하지 않는다.",
        "docs/installs/signoz_install.md",
    ),
    chapter(
        "local-smoke",
        "기능별 요청으로 검증하기",
        [
            "just poi-smoke: 장소 검색 경로",
            "just poi-card-smoke: 편의시설 카드",
            "just navigation-smoke: 키·권한·길찾기 의존",
        ],
        """<pre>just poi-smoke
just poi-card-smoke</pre>
        <p>실습 데이터와 필요한 외부 API 준비 상태를 확인한 뒤 기능 smoke를 실행한다. 기본 health가
        성공해도 SQL·데이터·외부 제공자 경로는 실패할 수 있다. 각 스크립트의 요구 입력과 기대하는
        상태 코드를 먼저 읽는다.</p>
        <p>길찾기는 Kakao 키와 현재 인증 판정의 영향을 받는다. <code>just navigation-smoke</code>는
        해당 준비가 된 로컬 환경에서 실행한다. 실제 외부 호출에 비용·요청 제한이 있으므로 대량 반복하지 않는다.
        실패를 숨기려고 등록 판정을 원격에서 끄지 않는다.</p>""",
        "빈 DB·외부 API 차단·잘못된 키의 실패를 구별하고 응답 본문에서 근거를 찾는다.",
        "tools/just/k8s.just",
    ),
    chapter(
        "local-troubleshoot",
        "자주 만나는 실패를 순서대로 분리",
        [
            "호스트 연결 실패: Docker·포트·kind 매핑",
            "파드 실패: 이미지·설정·DB·probe",
            "관측 실패: 트래픽·endpoint·수집기·조회 범위",
        ],
        """<p>오류를 만났을 때 전체 클러스터를 삭제하는 것부터 시작하면 증거와 데이터가 사라진다.
        <code>just cluster-doctor</code>와 <code>just logs scene-api</code>로 상태를 확인하고 마지막
        변경과 실패 시점을 연결한다. readiness 실패와 이미지 pull 실패는 해결 경로가 다르다.</p>
        <p>클러스터를 재생성해야 한다면 로컬 데이터 손실 범위를 먼저 확인한다. 원인을 찾은 후
        수정·재검증·증거를 기록한다. 강의에 쓰는 명령이 실제 레시피에 없으면 다른 이름을 추측하지
        말고 <code>just --list</code>와 README를 확인한다.</p>""",
        "장애를 고친 조는 왜 그 수정이 원인에 대응하는지 설명한다. 우연히 재시작해서 나은 경우는 미해결 원인을 적는다.",
        "tools/scripts/cluster-doctor.sh",
    ),
    chapter(
        "local-cloud",
        "로컬에서 AWS로 바뀌는 경계",
        [
            "kind → EKS, 로컬 이미지 → ECR",
            "PostgreSQL StatefulSet → RDS",
            "NodePort → ALB HTTPS·Ingress, 원격 관측은 별도 준비",
        ],
        """<p>로컬은 학습과 반복 개발에 맞고 DEV·PRD는 네트워크·권한·비밀값·백업·복구 책임을
        명시한다. Kubernetes 개념은 이어지지만 파일과 자격 증명은 그대로 복사하지 않는다. 특히
        원격 API 주소를 모바일 앱에 전달하는 과정은 서버 rollout과 별개다. AWS의 gateway는
        ClusterIP Service이며 Auto Mode가 Ingress를 보고 ALB를 만든다. 외부 주소는 gateway
        Service의 EXTERNAL-IP가 아니라 Ingress 상태에서 확인한다. 로컬에 ALB용 인증서나
        IngressClass를 그대로 설치하는 실습은 하지 않는다.</p>
        <p><a href="aws-eks-course.html">AWS 상세 강의</a>와
        <a href="aws-eks-presentation.html">AWS 발표 자료</a>에서 DEV·PRD 차이, AWS 서비스 정의,
        배포 단계와 비용을 이어서 학습한다. 코드 기반 구성도는 선언한 형상을 설명하며 실제 AWS
        생성 결과를 대신하지 않는다.</p>""",
        "마지막으로 한 요청이 지나가는 컴퓨터·네트워크·프로세스를 직접 그리게 한다.",
        "docs/architecture/aws-dev-prd.md",
    ),
]
