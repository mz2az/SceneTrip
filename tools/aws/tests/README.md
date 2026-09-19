# AWS 배포 회귀 검사

실제 AWS 호출 없이 입력 검증·비밀값 전달·DB 작업·실패 정리를 검사한다.
`just test //tools/aws:unit_test`로 실행한다.

`test_deploy.py`는 DB 역할·비밀값·정리, `test_commands.py`는 저장 계획·이미지 발행,
`test_boundaries.py`는 소스·AWS 역할·DNS·배포 경계, `test_entrypoint.py`는 전체 명령의
실행 순서와 조기 차단을 확인한다. `test_aws.py`가 이 테스트를 모아 실행한다.
`test_workflows.py`는 권한 없는 preflight와 검증된 SHA만 사용하는 배포 잡의 경계를 확인한다.
`test_alb.py`는 ALB·subnet·보안 그룹·IP target의 실제 출력 경계와 DEV/PRD Helm 렌더링을
검사한다. HTTPS 일시 오류 재시도와 읽기 전용 verify 경로도 별도 회귀 검사로 보호한다.
