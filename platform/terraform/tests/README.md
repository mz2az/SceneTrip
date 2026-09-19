# 인프라 정적 회귀 테스트

`test_infrastructure.py`는 CloudFormation의 OIDC 신뢰, IAM 변경 금지,
state 삭제 금지, RDS 비밀값 접근 경계와 DEV·PRD 예제를 검증한다.

`just test //platform/terraform:unit_test`로 실행한다.
