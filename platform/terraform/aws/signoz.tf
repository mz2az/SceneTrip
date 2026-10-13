# SigNoz 화면 전용 ALB 의 보안 그룹(ADR 0025, docs/project/plans/observability-aws.md §6-2).
# API ALB 는 인터넷 전체에 열려 있고(ADR 0024) 보안 그룹은 ALB 단위라, 화면은 ALB 를 따로 두고 Cloudflare 에서 온 것만 받는다.
# 브라우저 → Cloudflare(Access: 허용 메일만) → 이 ALB → signoz:8080. 주소를 알아도 Cloudflare 를 건너뛸 수 없다.

locals {
  # https://www.cloudflare.com/ips-v4 (2026-10-13). Cloudflare 가 대역을 바꾸면 여기를 고친다 — 빠진 대역에서 온 접속은 막힌다.
  cloudflare_ipv4 = toset([
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
  ])
}

resource "aws_security_group" "signoz_alb" {
  name        = "${local.name}-signoz-alb"
  description = "SigNoz UI ALB - Cloudflare edge only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${local.name}-signoz-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "signoz_alb_https" {
  for_each = local.cloudflare_ipv4

  security_group_id = aws_security_group.signoz_alb.id
  description       = "HTTPS from Cloudflare edge"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "signoz_alb_to_ui" {
  security_group_id            = aws_security_group.signoz_alb.id
  referenced_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  description                  = "SigNoz UI traffic and health checks"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "signoz_from_alb" {
  security_group_id            = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  referenced_security_group_id = aws_security_group.signoz_alb.id
  description                  = "SigNoz ALB to SigNoz UI"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
