# AWS 배포 회귀 검사

실제 AWS 호출 없이 입력 검증·비밀값 전달·DB 작업·실패 정리를 검사한다.
`just test //tools/aws:unit_test`로 실행한다.

`test_deploy.py`는 DB 역할·비밀값·정리, `test_commands.py`는 저장 계획·이미지 발행,
`test_boundaries.py`는 소스·AWS 역할·DNS·배포 경계, `test_entrypoint.py`는 전체 명령의
실행 순서와 조기 차단을 확인한다. `test_aws.py`가 이 테스트를 모아 실행한다.
`test_workflows.py`는 권한 없는 preflight와 검증된 SHA만 사용하는 배포 잡의 경계를 확인한다.
`test_alb.py`는 ALB·subnet·보안 그룹·IP target의 실제 출력 경계와 DEV/PRD Helm 렌더링을
검사한다. HTTPS 일시 오류 재시도와 읽기 전용 verify 경로도 별도 회귀 검사로 보호한다.

삭제 경로는 `test_teardown_entrypoint.py`가 인증 전 확인값·역할·실행 분기를 검사한다.
`test_destroy*.py`는 저장 계획의 허용 변경, 환경 소유권, Ingress·ALB 정리 순서,
보호 해제와 삭제 후 빈 state, 부분 삭제 재시도를 확인한다.
`test_bootstrap_delete.py`·`test_bootstrap_state.py`는 CloudFormation 리소스 식별,
서비스 잔존·잠금 차단, state 이력·S3 페이지 처리와 영구 삭제 오류를 검사한다.
클라우드 응답은 가짜 실행기로 대체하며 실제 AWS 삭제를 실행하지 않는다.

별도로 `just test //tools/bazel/cloud:terraform_lifecycle_test`는 고정 Terraform과
AWS mock provider로 override·계획 JSON·삭제 후 state를 확인한다.
검증 수치와 실환경 검증 범위는 [검증 기록](../../../docs/qa/aws-teardown-verification.md)에 적는다.
