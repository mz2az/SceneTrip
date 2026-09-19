locals {
  repositories = { scene_api = "scene-api", trip_guide = "trip-guide", migration = "migration" }
  app_secret_descriptions = {
    scene_api  = "SceneTrip 외부 API 인증정보"
    trip_guide = "Trip Guide 모델 API 인증정보"
    database   = "런타임·마이그레이션 PostgreSQL 역할 인증정보"
  }
}
resource "aws_ecr_repository" "app" {
  for_each             = local.repositories
  name                 = "${local.name}/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}
resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "태그 없는 레이어만 14일 뒤 제거. 버전 있는 릴리스는 보존."
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 14
      }
      action = { type = "expire" }
    }]
  })
}
# 비밀값은 배포 시 Secrets Manager에 입력. Terraform은 빈 컨테이너 메타데이터만 관리.
resource "aws_secretsmanager_secret" "app" {
  for_each                = local.app_secret_descriptions
  name                    = "/scenetrip/${var.environment}/${replace(each.key, "_", "-")}"
  description             = each.value
  recovery_window_in_days = local.production ? 30 : 7
}
