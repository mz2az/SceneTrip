# PRD 환경

[terraform.tfvars.json.example](terraform.tfvars.json.example)의 계정·인증서·
CIDR 자리표시자를 실제 PRD 값으로 채운다. [backend.hcl.example](backend.hcl.example)은
PRD 부트스트랩의 버킷 출력을 사용한다. 실설정·state는 커밋하지 않는다.

3 AZ·AZ별 NAT·Multi-AZ RDS·14일 백업·삭제 보호를 사용한다.
EKS 제어면 로그는 90일 보존한다. 이 설정만으로 공개 서비스 준비가 끝나는
것은 아니다. 설치 UUID를 대체하는 인증·인가와 부하·복구 검증 전에는
HTTPS 허용 CIDR을 승인된 사용자 네트워크로 제한한다.
