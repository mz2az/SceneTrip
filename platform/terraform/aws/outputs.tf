output "aws_region" {
  value = var.aws_region
}
output "aws_account_id" {
  value = var.aws_account_id
}
output "environment" {
  value = var.environment
}
output "cluster_name" {
  value = aws_eks_cluster.this.name
}
output "namespace" {
  value = "scenetrip"
}
output "ecr_repository_urls" {
  value = { for name, repository in aws_ecr_repository.app : name => repository.repository_url }
}
output "database_host" {
  value = aws_db_instance.postgres.address
}
output "database_port" {
  value = aws_db_instance.postgres.port
}
output "database_name" {
  value = "scenetrip"
}
output "database_master_secret_arn" {
  description = "관리자 Secret의 ARN만 반환. 비밀번호는 출력하지 않습니다."
  value       = aws_db_instance.postgres.master_user_secret[0].secret_arn
}
output "app_secret_arns" {
  value = { for name, secret in aws_secretsmanager_secret.app : name => secret.arn }
}
output "ingress_certificate_arn" {
  value = var.ingress_certificate_arn
}
output "ingress_allowed_cidrs" {
  value = var.ingress_allowed_cidrs
}
output "vpc_id" {
  value = aws_vpc.this.id
}
output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}
output "public_subnet_ids" {
  description = "IngressClassParams에서 명시적으로 선택할 ALB 배치용 public 서브넷."
  value       = aws_subnet.public[*].id
}
output "public_subnet_cidrs" {
  description = "ALB의 private source IP 범위. nginx proxy 신뢰와 gateway NetworkPolicy가 사용."
  value       = aws_subnet.public[*].cidr_block
}
output "alb_security_group_id" {
  description = "승인 CIDR의 443만 받는 Terraform 관리 ALB frontend SG."
  value       = aws_security_group.alb.id
}
output "workload_security_group_id" {
  description = "기본 Auto Mode NodeClass의 실제 SG와 대조할 EKS cluster SG."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}
output "topology" {
  description = "구성도·운영 검토용 형상이며 실제 운영 상태를 의미하지 않습니다."
  value = {
    availability_zones    = local.azs
    nat_gateway_count     = local.nat_count
    database_multi_az     = local.production
    backup_retention_days = local.production ? 14 : 7
    deletion_protection   = local.production
  }
}
