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
    error_message = "네 가지 OCI 저장소는 태그 불변·강제 삭제 금지여야 합니다."
  }
  assert {
    condition     = length(aws_secretsmanager_secret.app) == 5 && length(aws_ecr_repository.app) == 12
    error_message = "Scene API·DB·auth·SigNoz·Metabase 비밀값, 앱·에이전트·migration·data-load 이미지와 SigNoz 미러 일곱·Metabase 미러 하나 저장소가 필요합니다."
  }
  # trip-guide 비밀값은 없다 — Bedrock 을 Pod Identity 역할로 부른다(MZ2AZ-317). 이미지 저장소는 그대로다.
  # signoz 는 SigNoz 관리자 비밀번호 칸이다(ADR 0025). metabase 는 Metabase 계정 암호·암호화 키 칸이다(ADR 0027).
  assert {
    condition = (
      toset(keys(aws_secretsmanager_secret.app)) == toset(["scene_api", "database", "auth", "signoz", "metabase"]) &&
      toset([for s in aws_secretsmanager_secret.app : s.name]) == toset(["/scenetrip/dev/scene-api", "/scenetrip/dev/database", "/scenetrip/dev/auth", "/scenetrip/dev/signoz", "/scenetrip/dev/metabase"])
    )
    error_message = "앱 비밀값은 /scenetrip/dev/{scene-api,database,auth,signoz,metabase} 다섯뿐이어야 합니다 — trip-guide 비밀값은 없습니다."
  }
  assert {
    condition     = toset(keys(output.app_secret_arns)) == toset(["scene_api", "database", "auth", "signoz", "metabase"])
    error_message = "app_secret_arns 출력의 키는 scene_api·database·auth·signoz·metabase 여야 합니다."
  }
  # 원격 데이터 적재 이미지(MZ2AZ-414) — 다른 저장소와 같은 규칙, 환경 이름 아래.
  assert {
    condition = (
      aws_ecr_repository.app["data_load"].name == "scenetrip-dev/data-load" &&
      aws_ecr_repository.app["data_load"].image_tag_mutability == "IMMUTABLE" &&
      !aws_ecr_repository.app["data_load"].force_delete &&
      aws_ecr_repository.app["data_load"].image_scanning_configuration[0].scan_on_push
    )
    error_message = "data-load 저장소는 scenetrip-<env>/data-load, 태그 불변·강제 삭제 금지·푸시 시 검사여야 합니다."
  }
  assert {
    condition     = toset(keys(aws_ecr_repository.app)) == toset(["scene_api", "trip_guide", "migration", "data_load", "mirror_signoz", "mirror_otel_collector", "mirror_clickhouse", "mirror_alpine", "mirror_clickhouse_operator", "mirror_metrics_exporter", "mirror_zookeeper", "mirror_metabase"]) && toset(keys(aws_ecr_lifecycle_policy.app)) == toset(keys(aws_ecr_repository.app))
    error_message = "ECR 저장소 키는 scene_api·trip_guide·migration·data_load 와 SigNoz 미러 mirror_* 일곱·mirror_metabase 이고, 저장소마다 수명 주기 정책이 있어야 합니다."
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
  assert {
    condition     = aws_ecr_repository.app["data_load"].name == "scenetrip-prd/data-load" && !aws_ecr_repository.app["data_load"].force_delete
    error_message = "PRD data-load 저장소는 scenetrip-prd/data-load 이고 강제 삭제 금지여야 합니다."
  }
  assert {
    condition = (
      toset([for s in aws_secretsmanager_secret.app : s.name]) == toset(["/scenetrip/prd/scene-api", "/scenetrip/prd/database", "/scenetrip/prd/auth", "/scenetrip/prd/signoz", "/scenetrip/prd/metabase"]) &&
      alltrue([for s in aws_secretsmanager_secret.app : s.recovery_window_in_days == 30])
    )
    error_message = "PRD 앱 비밀값은 /scenetrip/prd/{scene-api,database,auth,signoz,metabase} 다섯이고 복구 기간 30일이어야 합니다."
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
  # 공개는 사용자 HTTPS 만이다 — EKS 관리 API 허용 CIDR 은 입력 그대로다.
  assert {
    condition     = toset(aws_eks_cluster.this.vpc_config[0].public_access_cidrs) == toset(var.eks_public_access_cidrs)
    error_message = "DEV EKS 관리 API 허용 CIDR 은 eks_public_access_cidrs 그대로여야 합니다."
  }
}

# PRD 도 HTTPS 를 인터넷 전체에 연다(ADR 0024, MZ2AZ-417). 열어도 443 하나뿐이어야 한다.
run "prd_alb_is_public_on_443_only" {
  command = apply
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == 1 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.security_group_id == aws_security_group.alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443 && rule.cidr_ipv4 == "0.0.0.0/0"
    ])
    error_message = "PRD ALB 는 인터넷 전체의 TCP443 하나만 열어야 합니다."
  }
  assert {
    condition     = output.ingress_public == true
    error_message = "PRD 는 배포기에 공개(ingress_public)를 알려야 합니다."
  }
  # 공개는 사용자 HTTPS 만이다 — EKS 관리 API 허용 CIDR 은 PRD 도 입력 그대로다(ADR 0024).
  assert {
    condition     = toset(aws_eks_cluster.this.vpc_config[0].public_access_cidrs) == toset(var.eks_public_access_cidrs)
    error_message = "PRD EKS 관리 API 허용 CIDR 은 eks_public_access_cidrs 그대로여야 합니다."
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

# ADR 0024 — PRD 도 DEV 와 같다. 허용 CIDR 목록이 여러 개여도 PRD 규칙은 0.0.0.0/0 하나다.
run "spec_prd_public_ignores_allowed_cidr_list" {
  command = plan
  variables {
    environment           = "prd"
    availability_zones    = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    ingress_allowed_cidrs = ["198.51.100.0/24", "203.0.113.0/24"]
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.alb_https) == 1 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.alb_https :
      rule.cidr_ipv4 == "0.0.0.0/0" && rule.ip_protocol == "tcp" && rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "PRD 는 허용 목록과 무관하게 TCP443 0.0.0.0/0 규칙 하나만 가져야 합니다."
  }
  assert {
    condition     = output.ingress_public == true
    error_message = "PRD 의 ingress_public 출력은 true 여야 합니다."
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

# --- trip-guide 의 Bedrock 역할(MZ2AZ-317, docs/project/plans/bedrock-iam-role.md) ----------
# 역할은 bootstrap 이 만든다. Terraform 은 trip-guide 서비스 계정을 그 역할에 잇기만 한다.
run "trip_guide_bedrock_pod_identity" {
  command = plan
  override_data {
    target = data.aws_iam_role.bedrock
    values = {
      arn = "arn:aws:iam::111122223333:role/scenetrip-dev-bedrock"
    }
  }
  assert {
    condition     = data.aws_iam_role.bedrock.name == "scenetrip-dev-bedrock"
    error_message = "Pod Identity 는 그 환경의 -bedrock 역할(scenetrip-<env>-bedrock)을 찾아야 합니다."
  }
  assert {
    condition = (
      aws_eks_pod_identity_association.trip_guide_bedrock.cluster_name == aws_eks_cluster.this.name &&
      aws_eks_pod_identity_association.trip_guide_bedrock.namespace == "scenetrip" &&
      aws_eks_pod_identity_association.trip_guide_bedrock.service_account == "trip-guide" &&
      aws_eks_pod_identity_association.trip_guide_bedrock.role_arn == "arn:aws:iam::111122223333:role/scenetrip-dev-bedrock"
    )
    error_message = "scenetrip 네임스페이스의 trip-guide 서비스 계정만 -bedrock 역할에 이어야 합니다."
  }
  assert {
    condition     = aws_eks_pod_identity_association.scene_api_media.service_account == "scene-api"
    error_message = "Bedrock 연결이 scene-api 의 사진 버킷 연결을 바꾸면 안 됩니다."
  }
}

run "prd_bedrock_role_follows_environment" {
  command = plan
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    vpc_cidr           = "10.50.0.0/16"
  }
  assert {
    condition     = data.aws_iam_role.bedrock.name == "scenetrip-prd-bedrock"
    error_message = "PRD 는 PRD 의 -bedrock 역할을 써야 합니다."
  }
}

# --- SigNoz 화면 전용 ALB(ADR 0025, signoz.tf) -----------------------------------------
# 화면 ALB 는 Cloudflare 엣지 대역에서 온 TCP443 만 받고, 클러스터 SG 의 8080(signoz UI)으로만 보낸다.
run "signoz_alb_accepts_only_cloudflare_on_443" {
  command = apply
  assert {
    condition = (
      aws_security_group.signoz_alb.name == "scenetrip-dev-signoz-alb" &&
      aws_security_group.signoz_alb.vpc_id == aws_vpc.this.id &&
      aws_security_group.signoz_alb.id != aws_security_group.alb.id
    )
    error_message = "SigNoz 화면 ALB 는 환경 VPC 의 scenetrip-<env>-signoz-alb 전용 SG 를 API ALB 와 따로 가져야 합니다."
  }
  assert {
    condition = length(aws_vpc_security_group_ingress_rule.signoz_alb_https) == 15 && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.signoz_alb_https :
      rule.security_group_id == aws_security_group.signoz_alb.id && rule.ip_protocol == "tcp" &&
      rule.from_port == 443 && rule.to_port == 443
    ])
    error_message = "SigNoz 화면 ALB ingress 는 Cloudflare 대역 15 개의 TCP443 뿐이어야 합니다."
  }
  assert {
    condition = toset([for rule in aws_vpc_security_group_ingress_rule.signoz_alb_https : rule.cidr_ipv4]) == toset([
      "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
      "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
      "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ])
    error_message = "SigNoz 화면 ALB 는 Cloudflare IPv4 대역(https://www.cloudflare.com/ips-v4)만 받아야 합니다 — 0.0.0.0/0 금지."
  }
  assert {
    condition = (
      aws_vpc_security_group_egress_rule.signoz_alb_to_ui.security_group_id == aws_security_group.signoz_alb.id &&
      aws_vpc_security_group_egress_rule.signoz_alb_to_ui.referenced_security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_egress_rule.signoz_alb_to_ui.ip_protocol == "tcp" &&
      aws_vpc_security_group_egress_rule.signoz_alb_to_ui.from_port == 8080 &&
      aws_vpc_security_group_egress_rule.signoz_alb_to_ui.to_port == 8080
    )
    error_message = "SigNoz 화면 ALB egress 는 클러스터 SG 의 TCP8080 만 허용해야 합니다."
  }
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.signoz_from_alb.security_group_id == aws_eks_cluster.this.vpc_config[0].cluster_security_group_id &&
      aws_vpc_security_group_ingress_rule.signoz_from_alb.referenced_security_group_id == aws_security_group.signoz_alb.id &&
      aws_vpc_security_group_ingress_rule.signoz_from_alb.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.signoz_from_alb.from_port == 8080 &&
      aws_vpc_security_group_ingress_rule.signoz_from_alb.to_port == 8080
    )
    error_message = "클러스터 SG 는 SigNoz 화면 ALB SG 에서 온 TCP8080 만 더 받아야 합니다."
  }
  assert {
    condition     = output.signoz_alb_security_group_id == aws_security_group.signoz_alb.id && output.signoz_certificate_arn == ""
    error_message = "SigNoz 화면 출력은 실제 SG 이고, 인증서 변수를 비우면 인증서 출력도 비어야 합니다."
  }
  # 화면 ALB 가 생겨도 API ALB 의 공개 규칙은 그대로다.
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.alb_https) == 1
    error_message = "SigNoz 화면 ALB 가 API ALB 규칙을 바꾸면 안 됩니다."
  }
}

run "prd_signoz_alb_name_follows_environment" {
  command = plan
  variables {
    environment        = "prd"
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
    vpc_cidr           = "10.50.0.0/16"
  }
  assert {
    condition     = aws_security_group.signoz_alb.name == "scenetrip-prd-signoz-alb" && length(aws_vpc_security_group_ingress_rule.signoz_alb_https) == 15
    error_message = "PRD 화면 ALB SG 는 scenetrip-prd-signoz-alb 이고 같은 Cloudflare 대역을 받아야 합니다."
  }
}

run "signoz_certificate_passes_through_to_output" {
  command = plan
  variables {
    signoz_certificate_arn = "arn:aws:acm:ap-northeast-2:111122223333:certificate/11111111-2222-3333-4444-555555555555"
  }
  assert {
    condition     = output.signoz_certificate_arn == "arn:aws:acm:ap-northeast-2:111122223333:certificate/11111111-2222-3333-4444-555555555555"
    error_message = "signoz_certificate_arn 출력은 입력 변수 그대로여야 합니다."
  }
}

run "reject_cross_account_signoz_certificate" {
  command = plan
  variables {
    signoz_certificate_arn = "arn:aws:acm:ap-northeast-2:444455556666:certificate/11111111-2222-3333-4444-555555555555"
  }
  expect_failures = [var.signoz_certificate_arn]
}

run "reject_other_region_signoz_certificate" {
  command = plan
  variables {
    signoz_certificate_arn = "arn:aws:acm:us-east-1:111122223333:certificate/11111111-2222-3333-4444-555555555555"
  }
  expect_failures = [var.signoz_certificate_arn]
}

run "reject_malformed_signoz_certificate" {
  command = plan
  variables {
    signoz_certificate_arn = "signoz-dev.scenetrip.io"
  }
  expect_failures = [var.signoz_certificate_arn]
}
