# SceneTrip 교육 자료 생성기

강의 본문·발표 요약·강사 노트의 정본을 관리하고 HTML·SVG·SHA256 출처를 생성한다.
빌드·실행·단위 테스트는 Bazel 타깃이며 실행 창구는 `just`다.

| 파일 | 역할 |
| --- | --- |
| [course_content.py](course_content.py) | AWS DEV·PRD 과정: 본문·요약·강사 노트·구현 근거 |
| [local_content.py](local_content.py) | 현재 kind·관측성 로컬 과정 |
| [build_course.py](build_course.py) | 오프라인 단일 HTML 렌더링·생성/검사 |
| [source_diagrams.py](source_diagrams.py) | HCL·환경 입력 계산, SVG·DOT·출처 해시 |
| [terraform_graph.py](terraform_graph.py) | 격리한 복사본의 실제 Terraform 엔진 DOT와 별도 출처 |
| [navigation.js](navigation.js) | 키보드·목차·딥링크·읽기·노트·전체 화면·인쇄 |
| [course.css](course.css) | 반응형·다크 모드·인쇄 레이아웃 |
| [test_education.py](test_education.py) | 내용·오프라인·무결성·변경 검출 회귀 테스트 |
| [navigation-browser-tests.js](navigation-browser-tests.js) | 열린 발표 페이지에서 실행하는 DOM 동작 검사 |
| [serve.py](serve.py) | `docs/`만 127.0.0.1에 제공하는 미리보기 |
| [BUILD.bazel](BUILD.bazel) | `:education`, `:unit_test` 타깃 |

```bash
just test //tools/education:unit_test
just education-generate
just education-check
just education-graph
just education-graph-check
just docs-serve 8766
```

HTML은 수강자가 빌드 도구 없이 열도록 배포하는 문서 산출물이다. 정본은 위 파일이며
HTML을 직접 고치지 않는다. CSS와 JS를 문서 안에 넣어 오프라인 단일 파일로 동작한다.
외부 링크는 참고 자료이며 강의 표시·탐색에 네트워크가 필요하지 않다.

## 구성도 해석과 한계

- DEV·PRD SVG는 환경 예제의 AZ·VPC·DB 클래스와 Terraform의 NAT·백업·삭제 보호·
  subnet 공식을 읽는다. 지원하지 않는 식으로 바뀌면 생성이 실패한다.
- 코드에서 나온 subnet 범위가 겹치면 실패한다. 고정된 수치를 조용히 재사용하지 않는다.
- Helm 템플릿의 Auto Mode ALB·HTTPS 443·IP 대상·ClusterIP 선언을 검사하고 Ingress·
  nginx·NetworkPolicy를 포함한 템플릿의 해시를 기록한다. 제한된 정적 검사이며 실제 Helm
  렌더 검사를 대신하지 않는다. 다른 controller나 Service 노출로 바뀌면 생성을 중단한다.
- HCL 직접 참조 SVG·DOT는 리소스 선언의 명시적 참조만 나타낸다. `local`·module을
  통한 간접 의존은 계산하지 않는다. Terraform 엔진의 `graph` 출력이 아니다.
- 별도의 `terraform-graph.dot`는 Bazel이 고정한 Terraform과 provider로 만든 실제 엔진
  그래프다. 임시 복사본에서만 backend를 제거하고 로컬 provider mirror로 초기화한다.
  backend와 AWS에 접속하지 않으며 원본 `.tf`·lockfile을 수정하지 않는다. 도구 버전,
  명령 인자, provider 아카이브 해시, 입력·DOT 해시는 별도 provenance에 기록한다.
  다른 플랫폼의 provider 아카이브를 쓰면 그 출처 해시도 달라진다.
- ALB 자체는 Terraform이 아닌 Ingress/Auto Mode 소유다. 실제 엔진 DOT에 ALB 노드를
  끼워 넣지 않는다. 개요 SVG는 이 경계를 설명하고 ALB → gateway Pod의 데이터 경로와
  Ingress → ClusterIP Service의 논리적 참조를 구분한다.
- 요청 흐름·운영 경계 설명은 관련 앱 설정과 Helm 소스를 근거로 작성한 교육용 해설이다.
  실제 AWS 인벤토리·계정 조회·Terraform plan·배포 성공 보고서가 아니다.
- 출처 manifest는 정렬된 입력 파일과 생성 SVG·DOT의 SHA256을 기록한다. 생성 시각이나
  머신 절대 경로는 넣지 않는다. `education-check`는 비교만 하고 파일을 고치지 않는다.
- 실제 환경 파일·state·비밀값을 입력하거나 산출물에 넣지 않는다. 예제 형상만 사용한다.

## 이식 출처

교육 구성과 탐색 구현은 [TripPilot PR #645](https://github.com/ASM-TripPilot/trippilot/pull/645),
커밋 `8420d8e09bd48dc2650883a3f2fe43fc96dea231`을 검토했다.
`navigation.js`는 해당 커밋의 `docs/education/assets/course-navigation.js`를 가져왔다.
본문·발표 요약·로컬 실습·구성도 생성과 테스트는 SceneTrip 소스에 맞게 작성했다.
Terraform 엔진 그래프와 HCL 직접 참조 그림을 별도 파일·출처로 구분한다.
발표 페이지를 연 뒤 `navigation-browser-tests.js`의 `testSceneTripNavigation()`을
브라우저 평가 기능에서 실행하면 실제 버튼·키보드·목차·노트·읽기·hash·인쇄 동작을 검사한다.
