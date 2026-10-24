# Metabase 화면 전용 ALB 의 보안 그룹(ADR 0027, docs/project/plans/metabase-prd.md §3-5) — SigNoz 화면(signoz.tf)과 같은 모양.
# 브라우저 → Cloudflare(Access: 허용 메일만) → 이 ALB → metabase:3000. 443 은 SigNoz 와 같은 Cloudflare 대역 목록
# (signoz.tf 의 local.cloudflare_ipv4)만 받는다 — 대역을 고칠 곳은 한 곳이다.

resource "aws_security_group" "metabase_alb" {
  name        = "${local.name}-metabase-alb"
  description = "Metabase UI ALB - Cloudflare edge only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${local.name}-metabase-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "metabase_alb_https" {
  for_each = local.cloudflare_ipv4

  security_group_id = aws_security_group.metabase_alb.id
  description       = "HTTPS from Cloudflare edge"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "metabase_alb_to_ui" {
  security_group_id            = aws_security_group.metabase_alb.id
  referenced_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  description                  = "Metabase UI traffic and health checks"
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
}

resource "aws_vpc_security_group_ingress_rule" "metabase_from_alb" {
  security_group_id            = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  referenced_security_group_id = aws_security_group.metabase_alb.id
  description                  = "Metabase ALB to Metabase UI"
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
}
