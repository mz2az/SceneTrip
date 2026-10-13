# 바깥 감시 알람 — CloudFormation `scenetrip-alerts`

PRD API 를 밖에서 부르는 Route 53 헬스 체크와 그 실패 알람, 계정 월 예산. 계정 공용이라 환경 bootstrap·Terraform 과 따로 둔다
(배포 역할의 정책이 IAM 한도에 가깝다). us-east-1 — Route 53 헬스 체크 지표가 거기 있다.

`just aws-alerts --email <받는 메일>` 미리 보기 · `--execute` 적용. 운영: [aws-deployment.md](../../../docs/ops/aws-deployment.md) §16 ·
계획: [observability-aws.md](../../../docs/project/plans/observability-aws.md) §6-3.
