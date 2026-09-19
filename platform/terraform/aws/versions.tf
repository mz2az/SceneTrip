terraform {
  required_version = "= 1.13.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.64.0"
    }
  }
  backend "s3" {
    encrypt      = true
    use_lockfile = true
  }
}
provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]
  default_tags {
    tags = {
      Project     = "scenetrip"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
