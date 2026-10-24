# Metabase 화면 전용 ALB(ADR 0027, docs/project/plans/metabase-prd.md §3-5) — SigNoz 화면(signoz.tf)과 같은 모양.
# 화면 ALB 는 SigNoz 와 같은 Cloudflare 엣지 대역에서 온 TCP443 만 받고, 클러스터 SG 의 3000(metabase)으로만 보낸다.
# 모든 리소스는 mock_provider 를 쓰며 AWS API 에 접근하지 않는다.

mock_provider "aws" {
  mock_data "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111122223333:role/scenetrip-dev-eks-cluster"
    }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      master_user_secret = [{
        secret_arn = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:rds!db-mock"
      }]
    }
  }
}

variables {
  environment                    = "dev"
  aws_account_id                 = "111122223333"
  aws_region                     = "ap-northeast-2"
  availability_zones             = ["ap-northeast-2a", "ap-northeast-2c"]
  vpc_cidr                       = "10.40.0.0/16"
  eks_version                    = "1.35"
  eks_public_access_cidrs        = ["192.0.2.1/32"]
  ingress_allowed_cidrs          = ["198.51.100.0/24"]
  ingress_certificate_arn        = "arn:aws:acm:ap-northeast-2:111122223333:certificate/00000000-0000-0000-0000-000000000000"
  database_instance_class        = "db.t4g.medium"
  database_allocated_storage     = 20
  database_max_allocated_storage = 200
}

run "metabase_alb_accepts_only_cloudflare_on_443" {
  command = apply
  assert {
    condition = (
      aws_security_group.metabase_alb.name == "scenetrip-dev-metabase-alb" &&
      aws_security_group.metabase_alb.vpc_id == aws_vpc.this.id &&
      aws_security_group.metabase_alb.id != aws_security_group.alb.id &&
      aws_security_group.metabase_alb.id != aws_security_group.signoz_alb.id
    )
    error_message = "Metabase 화면 ALB 는 환경 VPC 의 scenetrip-<env>-metabase-alb 전용 SG 를 API·SigNoz ALB 와 따로 가져야 합니다."
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.metabase_alb_https) > 0 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.metabase_alb_https :
      rule.security_group_id == aws_security_group.metabase_alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "Metabase 화면 ALB ingress 는 TCP443 뿐이어야 합니다."
  }
  assert {
    condition = (
      toset([for rule in aws_vpc_security_group_ingress_rule.metabase_alb_https : rule.cidr_ipv4]) ==
      toset([for rule in aws_vpc_security_group_ingress_rule.signoz_alb_https : rule.cidr_ipv4])
    )
    error_message = "Metabase 화면 ALB 는 SigNoz 와 같은 Cloudflare 대역 목록만 받아야 합니다 — 0.0.0.0/0 금지."
  }
  assert {
    condition = (
      aws_vpc_security_group_egress_rule.metabase_alb_to_ui.security_group_id == aws_security_group.metabase_alb.id &&
      aws_vpc_security_group_egress_rule.metabase_alb_to_ui.referenced_security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_egress_rule.metabase_alb_to_ui.ip_protocol == "tcp" &&
      aws_vpc_security_group_egress_rule.metabase_alb_to_ui.from_port == 3000 &&
      aws_vpc_security_group_egress_rule.metabase_alb_to_ui.to_port == 3000
    )
    error_message = "Metabase 화면 ALB egress 는 클러스터 SG 의 TCP3000 만 허용해야 합니다."
  }
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.metabase_from_alb.security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_ingress_rule.metabase_from_alb.referenced_security_group_id == aws_security_group.metabase_alb.id &&
      aws_vpc_security_group_ingress_rule.metabase_from_alb.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.metabase_from_alb.from_port == 3000 &&
      aws_vpc_security_group_ingress_rule.metabase_from_alb.to_port == 3000
    )
    error_message = "클러스터 SG 는 Metabase 화면 ALB SG 에서 온 TCP3000 을 받아야 합니다."
  }
  assert {
    condition     = output.metabase_alb_security_group_id == aws_security_group.metabase_alb.id && output.metabase_certificate_arn == ""
    error_message = "Metabase 화면 출력은 실제 SG 이고, 인증서 변수를 비우면 인증서 출력도 비어야 합니다."
  }
  # Metabase 화면 ALB 가 생겨도 API·SigNoz ALB 의 규칙은 그대로다.
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.alb_https) == 1 && length(aws_vpc_security_group_ingress_rule.signoz_alb_https) == 15
    error_message = "Metabase 화면 ALB 가 API·SigNoz ALB 규칙을 바꾸면 안 됩니다."
  }
}

run "metabase_repository_and_secret" {
  command = plan
  assert {
    condition = (
      aws_ecr_repository.app["mirror_metabase"].name == "scenetrip-dev/mirror/metabase/metabase" &&
      aws_ecr_repository.app["mirror_metabase"].image_tag_mutability == "IMMUTABLE" &&
      !aws_ecr_repository.app["mirror_metabase"].force_delete
    )
    error_message = "Metabase 복사 저장소는 scenetrip-<env>/mirror/metabase/metabase, 태그 불변·강제 삭제 금지여야 합니다."
  }
  assert {
    condition     = aws_secretsmanager_secret.app["metabase"].name == "/scenetrip/dev/metabase"
    error_message = "Metabase 비밀값 컨테이너는 /scenetrip/<env>/metabase 여야 합니다."
  }
}

run "prd_metabase_alb_name_follows_environment" {
  command = plan
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    vpc_cidr           = "10.50.0.0/16"
  }
  assert {
    condition     = aws_security_group.metabase_alb.name == "scenetrip-prd-metabase-alb" && length(aws_vpc_security_group_ingress_rule.metabase_alb_https) == length(aws_vpc_security_group_ingress_rule.signoz_alb_https)
    error_message = "PRD Metabase 화면 ALB SG 는 scenetrip-prd-metabase-alb 이고 SigNoz 와 같은 Cloudflare 대역을 받아야 합니다."
  }
}

run "metabase_certificate_passes_through_to_output" {
  command = plan
  variables {
    metabase_certificate_arn = "arn:aws:acm:ap-northeast-2:111122223333:certificate/11111111-2222-3333-4444-666666666666"
  }
  assert {
    condition     = output.metabase_certificate_arn == "arn:aws:acm:ap-northeast-2:111122223333:certificate/11111111-2222-3333-4444-666666666666"
    error_message = "metabase_certificate_arn 출력은 입력 변수 그대로여야 합니다."
  }
}

run "reject_cross_account_metabase_certificate" {
  command = plan
  variables {
    metabase_certificate_arn = "arn:aws:acm:ap-northeast-2:444455556666:certificate/11111111-2222-3333-4444-666666666666"
  }
  expect_failures = [var.metabase_certificate_arn]
}

run "reject_other_region_metabase_certificate" {
  command = plan
  variables {
    metabase_certificate_arn = "arn:aws:acm:us-east-1:111122223333:certificate/11111111-2222-3333-4444-666666666666"
  }
  expect_failures = [var.metabase_certificate_arn]
}

run "reject_malformed_metabase_certificate" {
  command = plan
  variables {
    metabase_certificate_arn = "metabase-dev.scenetrip.io"
  }
  expect_failures = [var.metabase_certificate_arn]
}
