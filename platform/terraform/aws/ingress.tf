# ALB 자체는 EKS Auto Mode가 Ingress를 보고 생성합니다.
# frontend와 backend SG 규칙은 Terraform만 소유해 controller와 중복 관리하지 않습니다.
resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "SceneTrip approved HTTPS clients to ALB"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${local.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = toset(var.ingress_allowed_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "Approved HTTPS client range"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "alb_to_gateway" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  description                  = "Gateway traffic and health checks"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# 기본 Auto Mode NodeClass의 실제 SG가 이 출력과 일치하는지 배포기가 검사합니다.
# EKS가 관리하는 기존 self/control-plane 규칙은 그대로 두고 ALB 규칙만 추가합니다.
resource "aws_vpc_security_group_ingress_rule" "gateway_from_alb" {
  security_group_id            = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  referenced_security_group_id = aws_security_group.alb.id
  description                  = "ALB to private gateway"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
