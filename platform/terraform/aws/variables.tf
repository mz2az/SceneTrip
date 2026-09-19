variable "environment" {
  description = "독립 AWS 계정·state·GitHub Environment의 배포 환경."
  type        = string
  validation {
    condition     = contains(["dev", "prd"], var.environment)
    error_message = "environment는 dev 또는 prd여야 합니다."
  }
}
variable "aws_account_id" {
  description = "배포 대상 계정. 다른 계정의 자격증명은 거부합니다."
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id는 12자리 계정 ID여야 합니다."
  }
}
variable "aws_region" {
  description = "모든 리소스와 기존 ACM 인증서가 위치한 AWS 리전."
  type        = string
  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.aws_region))
    error_message = "유효한 AWS 리전을 입력하세요."
  }
}
variable "availability_zones" {
  description = "명시적으로 선택한 AZ: DEV 2개, PRD 3개. 배포 전 계정 내 지원 여부 확인."
  type        = list(string)
  validation {
    condition = length(var.availability_zones) == (var.environment == "prd" ? 3 : 2) && length(distinct(var.availability_zones)) == length(var.availability_zones) && alltrue([
      for az in var.availability_zones : can(regex("^${var.aws_region}[a-z]$", az))
    ])
    error_message = "DEV는 중복 없는 같은 리전 AZ 2개, PRD는 3개가 필요합니다."
  }
}
variable "vpc_cidr" {
  description = "환경별 겹치지 않는 RFC1918 IPv4 /16."
  type        = string
  validation {
    condition = can(cidrnetmask(var.vpc_cidr)) && endswith(var.vpc_cidr, "/16") && (
      startswith(var.vpc_cidr, "10.") || startswith(var.vpc_cidr, "192.168.") ||
      can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", var.vpc_cidr))
    )
    error_message = "vpc_cidr는 사설 IPv4 /16이어야 합니다."
  }
}
variable "eks_version" {
  description = "배포 리전에서 지원하는 Kubernetes minor 버전을 명시적으로 고정."
  type        = string
  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.eks_version))
    error_message = "eks_version은 1.35 같은 minor 버전이어야 합니다."
  }
}
variable "eks_public_access_cidrs" {
  description = "고정 egress runner·운영자만 EKS API에 접근. 전체 인터넷 공개는 거부."
  type        = list(string)
  validation {
    condition = length(var.eks_public_access_cidrs) > 0 && alltrue([
      for cidr in var.eks_public_access_cidrs : can(cidrnetmask(cidr)) && !endswith(cidr, "/0")
    ])
    error_message = "EKS 접근 IPv4 CIDR을 지정하세요. /0은 허용하지 않습니다."
  }
}
variable "ingress_allowed_cidrs" {
  description = "설치 UUID만 사용하는 현 앱의 승인 사용자 CIDR. ALB SG의 HTTPS443과 nginx client 검증에 적용."
  type        = list(string)
  validation {
    condition = length(var.ingress_allowed_cidrs) > 0 && alltrue([
      for cidr in var.ingress_allowed_cidrs : can(cidrnetmask(cidr)) && !endswith(cidr, "/0")
    ])
    error_message = "HTTPS 접근 IPv4 CIDR을 지정하세요. /0은 허용하지 않습니다."
  }
}
variable "ingress_certificate_arn" {
  description = "대상 계정·리전에 미리 검증된 ACM 인증서 ARN."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:acm:${var.aws_region}:${var.aws_account_id}:certificate/[0-9a-f-]{36}$", var.ingress_certificate_arn))
    error_message = "ACM 인증서는 대상 AWS 계정·리전과 일치해야 합니다."
  }
}
variable "database_instance_class" {
  description = "RDS PostgreSQL 17 인스턴스 종류. 리전 지원 여부는 사전 확인."
  type        = string
  validation {
    condition     = can(regex("^db\\.[a-z0-9]+\\.[a-z0-9]+$", var.database_instance_class))
    error_message = "유효한 RDS 인스턴스 종류를 입력하세요."
  }
}
variable "database_allocated_storage" {
  description = "초기 암호화 gp3 저장 공간(GiB)."
  type        = number
  validation {
    condition     = var.database_allocated_storage >= 20 && floor(var.database_allocated_storage) == var.database_allocated_storage
    error_message = "DB 저장 공간은 20 GiB 이상의 정수여야 합니다."
  }
}
variable "database_max_allocated_storage" {
  description = "자동 확장 상한(GiB). 초기 크기보다 최소 10% 크게 설정."
  type        = number
  validation {
    condition     = var.database_max_allocated_storage >= ceil(var.database_allocated_storage * 1.1) && floor(var.database_max_allocated_storage) == var.database_max_allocated_storage
    error_message = "자동 확장 상한은 초기 크기보다 최소 10% 커야 합니다."
  }
}
