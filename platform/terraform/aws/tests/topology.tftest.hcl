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

run "dev_private_and_cost_boundary" {
  command = plan
  assert {
    condition     = length(aws_subnet.public) == 2 && length(aws_subnet.private) == 2 && length(aws_subnet.data) == 2 && length(aws_nat_gateway.this) == 1
    error_message = "DEV는 2 AZ의 3계층 서브넷과 단일 NAT여야 합니다."
  }
  assert {
    condition     = !aws_db_instance.postgres.publicly_accessible && !aws_db_instance.postgres.multi_az && aws_db_instance.postgres.backup_retention_period == 7
    error_message = "DEV DB는 비공개 Single-AZ·7일 백업이어야 합니다."
  }
  assert {
    condition     = aws_db_instance.postgres.manage_master_user_password && aws_db_instance.postgres.storage_encrypted && aws_db_instance.postgres.engine_version == "17"
    error_message = "RDS17은 암호화와 AWS 관리 관리자 비밀번호가 필수입니다."
  }
  assert {
    condition     = aws_eks_cluster.this.compute_config[0].enabled && aws_eks_cluster.this.storage_config[0].block_storage[0].enabled && aws_eks_cluster.this.kubernetes_network_config[0].elastic_load_balancing[0].enabled
    error_message = "Auto Mode의 컴퓨트·블록스토리지·LB 기능을 함께 활성화해야 합니다."
  }
  assert {
    condition     = aws_eks_cluster.this.access_config[0].authentication_mode == "API" && !aws_eks_cluster.this.access_config[0].bootstrap_cluster_creator_admin_permissions
    error_message = "EKS 생성자 관리자 권한을 끄고 명시적 API 접근만 사용해야 합니다."
  }
  assert {
    condition     = alltrue([for r in aws_ecr_repository.app : r.image_tag_mutability == "IMMUTABLE" && !r.force_delete])
    error_message = "세 가지 OCI 저장소는 태그 불변·강제 삭제 금지여야 합니다."
  }
  assert {
    condition     = length(aws_secretsmanager_secret.app) == 3 && length(aws_ecr_repository.app) == 3
    error_message = "Scene API·Trip Guide·DB 비밀값, 앱·에이전트·migration 이미지가 필요합니다."
  }
}

run "prd_high_availability_and_retention" {
  command = plan
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    vpc_cidr           = "10.50.0.0/16"
  }
  assert {
    condition     = length(aws_subnet.private) == 3 && length(aws_subnet.data) == 3 && length(aws_nat_gateway.this) == 3
    error_message = "PRD는 3 AZ·AZ별 NAT여야 합니다."
  }
  assert {
    condition     = aws_db_instance.postgres.multi_az && aws_db_instance.postgres.deletion_protection && !aws_db_instance.postgres.skip_final_snapshot && aws_db_instance.postgres.backup_retention_period == 14
    error_message = "PRD DB는 Multi-AZ·삭제 보호·최종 스냅샷·14일 백업이어야 합니다."
  }
  assert {
    condition     = aws_eks_cluster.this.deletion_protection && aws_cloudwatch_log_group.eks.retention_in_days == 90
    error_message = "PRD EKS 삭제 보호와 90일 제어면 로그 보존이 필요합니다."
  }
}

# 모든 리소스는 위 mock_provider를 사용하며 AWS API에 접근하지 않습니다.
# apply는 생성된 SG ID를 비교하기 위해 mock state만 구체화합니다.
run "alb_frontend_and_backend_security_boundary" {
  command = apply
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == length(var.ingress_allowed_cidrs) && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.security_group_id == aws_security_group.alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443 && contains(var.ingress_allowed_cidrs, rule.cidr_ipv4)
    ])
    error_message = "ALB HTTPS는 승인된 client CIDR의 TCP443만 허용해야 합니다."
  }
  assert {
    condition = (
      aws_vpc_security_group_egress_rule.alb_to_gateway.security_group_id == aws_security_group.alb.id &&
      aws_vpc_security_group_egress_rule.alb_to_gateway.referenced_security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_egress_rule.alb_to_gateway.ip_protocol == "tcp" &&
      aws_vpc_security_group_egress_rule.alb_to_gateway.from_port == 8080 &&
      aws_vpc_security_group_egress_rule.alb_to_gateway.to_port == 8080
    )
    error_message = "ALB egress는 workload SG의 gateway TCP8080만 허용해야 합니다."
  }
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.gateway_from_alb.security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_ingress_rule.gateway_from_alb.referenced_security_group_id == aws_security_group.alb.id &&
      aws_vpc_security_group_ingress_rule.gateway_from_alb.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.gateway_from_alb.from_port == 8080 &&
      aws_vpc_security_group_ingress_rule.gateway_from_alb.to_port == 8080
    )
    error_message = "gateway의 추가 ingress는 ALB SG의 TCP8080이어야 합니다."
  }
  assert {
    condition = (
      output.public_subnet_ids == aws_subnet.public[*].id &&
      output.public_subnet_cidrs == ["10.40.0.0/24", "10.40.1.0/24"] &&
      output.alb_security_group_id == aws_security_group.alb.id &&
      output.workload_security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
    )
    error_message = "Helm의 ALB subnet·proxy 신뢰 CIDR·SG 출력은 실제 Terraform 리소스와 같아야 합니다."
  }
}

run "reject_world_readable_api" {
  command = plan
  variables {
    eks_public_access_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.eks_public_access_cidrs]
}

run "reject_world_readable_app" {
  command = plan
  variables {
    ingress_allowed_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.ingress_allowed_cidrs]
}

run "reject_insufficient_prd_zones" {
  command = plan
  variables {
    environment = "prd"
  }
  expect_failures = [var.availability_zones]
}

run "reject_cross_account_certificate" {
  command = plan
  variables {
    ingress_certificate_arn = "arn:aws:acm:ap-northeast-2:444455556666:certificate/00000000-0000-0000-0000-000000000000"
  }
  expect_failures = [var.ingress_certificate_arn]
}
