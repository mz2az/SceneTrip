# Terraform 모의 테스트

실제 AWS API 호출 없이 DEV·PRD의 가용성·삭제 보호·비밀값·네트워크 접근 경계를
검증한다. `topology.tftest.hcl`의 계정과 CIDR은 테스트용 값이다.
7개 모의 실행에는 ALB frontend443·backend8080 SG 방향과 subnet·신뢰 CIDR 출력
계약이 포함된다. SG 참조 검사는 `mock_provider`의 `apply`로 식별자를 구체화하며,
실제 AWS 리소스를 생성하지 않는다. 나머지 실행은 모의 계획과 입력 거부를 검사한다.

프로바이더 설치 이후 `just tf-check dev`에서 실행한다. 실제 리전에서의
AZ·인스턴스·RDS 확장 지원과 IAM 권한 평가는 배포 전 계획·운영 검증이 필요하다.
