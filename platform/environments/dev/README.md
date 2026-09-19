# DEV 환경

[terraform.tfvars.json.example](terraform.tfvars.json.example)의 계정·인증서·
CIDR 자리표시자를 실제 DEV 값으로 채운다. [backend.hcl.example](backend.hcl.example)은
DEV 부트스트랩의 버킷 출력을 사용한다. 실설정·state는 커밋하지 않는다.

2 AZ·단일 NAT·Single-AZ RDS는 비용 절충이다. NAT가 있는 AZ 장애 시
다른 AZ의 외부 API 호출도 중단될 수 있다. DB 백업은 7일 보존한다.
공용 PRD 계정·역할·DB를 DEV에서 재사용하지 않는다.
