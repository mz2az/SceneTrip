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
    condition     = length(aws_secretsmanager_secret.app) == 4 && length(aws_ecr_repository.app) == 3
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
# DEV 는 HTTPS 를 인터넷 전체에 연다(ADR 0019). 열어도 443 하나뿐이어야 한다.
run "dev_alb_is_public_on_443_only" {
  command = apply
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == 1 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.security_group_id == aws_security_group.alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443 && rule.cidr_ipv4 == "0.0.0.0/0"
    ])
    error_message = "DEV ALB 는 인터넷 전체의 TCP443 하나만 열어야 합니다."
  }
  assert {
    condition     = output.ingress_public == true
    error_message = "DEV 는 배포기에 공개(ingress_public)를 알려야 합니다."
  }
}

# PRD 는 허용 CIDR 만 — 사용자별 요청 제한·유료 API 한도(MZ2AZ-334) 전에는 열지 않는다.
run "prd_alb_allows_only_approved_cidrs" {
  command = apply
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == length(var.ingress_allowed_cidrs) && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.security_group_id == aws_security_group.alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443 && contains(var.ingress_allowed_cidrs, rule.cidr_ipv4)
    ])
    error_message = "PRD ALB HTTPS는 승인된 client CIDR의 TCP443만 허용해야 합니다."
  }
  assert {
    condition     = output.ingress_public == false
    error_message = "PRD 는 공개되면 안 됩니다."
  }
}

run "alb_frontend_and_backend_security_boundary" {
  command = apply
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

# --- ADR 0019 명세 검사 (MZ2AZ-333) ---------------------------------------------
# DEV 공개 판단은 환경에서 나온다. 허용 CIDR 목록이 여러 개여도 DEV 규칙은 0.0.0.0/0 하나다.
run "spec_dev_public_ignores_allowed_cidr_list" {
  command = plan
  variables {
    ingress_allowed_cidrs = ["198.51.100.0/24", "203.0.113.0/24"]
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == 1 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.cidr_ipv4 == "0.0.0.0/0" && rule.ip_protocol == "tcp" && rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "DEV 는 허용 목록과 무관하게 TCP443 0.0.0.0/0 규칙 하나만 가져야 합니다."
  }
  assert {
    condition     = output.ingress_public == true
    error_message = "DEV 의 ingress_public 출력은 true 여야 합니다."
  }
}

# PRD 의 443 규칙 CIDR 집합은 ingress_allowed_cidrs 와 정확히 같고 0.0.0.0/0 은 없다.
run "spec_prd_rules_equal_allowed_cidrs" {
  command = plan
  variables {
    environment           = "prd"
    availability_zones    = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    ingress_allowed_cidrs = ["198.51.100.0/24", "203.0.113.0/24"]
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == 2 && toset([
      for rule in aws_vpc_security_group_ingress_rule.alb_https : rule.cidr_ipv4
    ]) == toset(["198.51.100.0/24", "203.0.113.0/24"])
    error_message = "PRD 443 규칙 CIDR 은 ingress_allowed_cidrs 와 같아야 합니다."
  }
  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.cidr_ipv4 != "0.0.0.0/0" && rule.ip_protocol == "tcp" && rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "PRD 는 TCP443 만, 그리고 인터넷 전체 CIDR 없이 열어야 합니다."
  }
  assert {
    condition     = output.ingress_public == false
    error_message = "PRD 의 ingress_public 출력은 false 여야 합니다."
  }
}

# EKS 관리 API 의 /0 거부는 DEV 공개와 무관하게 그대로다(명시적으로 dev).
run "spec_dev_still_rejects_world_eks_api" {
  command = plan
  variables {
    environment             = "dev"
    eks_public_access_cidrs = ["192.0.2.1/32", "0.0.0.0/0"]
  }
  expect_failures = [var.eks_public_access_cidrs]
}

run "spec_prd_still_rejects_world_eks_api" {
  command = plan
  variables {
    environment             = "prd"
    availability_zones      = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    eks_public_access_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.eks_public_access_cidrs]
}

# 공개는 환경으로만 정한다 — 입력 목록으로 /0 을 넣는 길은 두 환경 모두 막혀 있다.
run "spec_dev_rejects_world_in_allowed_cidrs_list" {
  command = plan
  variables {
    environment           = "dev"
    ingress_allowed_cidrs = ["198.51.100.0/24", "0.0.0.0/0"]
  }
  expect_failures = [var.ingress_allowed_cidrs]
}

run "spec_prd_rejects_world_in_allowed_cidrs" {
  command = plan
  variables {
    environment           = "prd"
    availability_zones    = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    ingress_allowed_cidrs = ["0.0.0.0/0"]
  }
  expect_failures = [var.ingress_allowed_cidrs]
}

# --- 사용자 사진 버킷(docs/project/plans/review.md §13) ------------------------------
# 버킷과 -media 역할은 bootstrap 이 만든다. Terraform 은 scene-api 서비스 계정을 그 역할에 잇고 이름만 출력한다.
run "scene_api_media_pod_identity" {
  command = plan
  override_data {
    target = data.aws_iam_role.media
    values = {
      arn = "arn:aws:iam::111122223333:role/scenetrip-dev-media"
    }
  }
  assert {
    condition     = data.aws_iam_role.media.name == "scenetrip-dev-media"
    error_message = "Pod Identity 는 그 환경의 -media 역할(scenetrip-<env>-media)을 찾아야 합니다."
  }
  assert {
    condition = (
      aws_eks_pod_identity_association.scene_api_media.cluster_name == aws_eks_cluster.this.name &&
      aws_eks_pod_identity_association.scene_api_media.namespace == "scenetrip" &&
      aws_eks_pod_identity_association.scene_api_media.service_account == "scene-api" &&
      aws_eks_pod_identity_association.scene_api_media.role_arn == "arn:aws:iam::111122223333:role/scenetrip-dev-media"
    )
    error_message = "scenetrip 네임스페이스의 scene-api 서비스 계정만 -media 역할에 이어야 합니다."
  }
  assert {
    condition     = output.user_media_bucket == "scenetrip-user-media-111122223333-ap-northeast-2-dev"
    error_message = "user_media_bucket 은 bootstrap 의 이름 규칙(scenetrip-user-media-<계정>-<리전>-<환경>)과 같아야 합니다."
  }
}

run "prd_media_names_follow_environment" {
  command = plan
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    vpc_cidr           = "10.50.0.0/16"
  }
  assert {
    condition     = data.aws_iam_role.media.name == "scenetrip-prd-media"
    error_message = "PRD 는 PRD 의 -media 역할을 써야 합니다."
  }
  assert {
    condition     = output.user_media_bucket == "scenetrip-user-media-111122223333-ap-northeast-2-prd"
    error_message = "PRD 버킷 이름은 환경 접미사 prd 를 가져야 합니다."
  }
}
