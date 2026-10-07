# 신뢰 정책과 고정 역할은 CloudFormation이 소유합니다. 배포 역할은 읽기·전달만 가능.
data "aws_iam_role" "cluster" {
  name = "${local.name}-eks-cluster"
}

data "aws_iam_role" "node" {
  name = "${local.name}-eks-node"
}

resource "aws_cloudwatch_log_group" "eks" {
  name              = "/aws/eks/${local.name}/cluster"
  retention_in_days = local.production ? 90 : 14
}

resource "aws_eks_cluster" "this" {
  name                          = local.name
  role_arn                      = data.aws_iam_role.cluster.arn
  version                       = var.eks_version
  deletion_protection           = local.production
  bootstrap_self_managed_addons = false
  enabled_cluster_log_types     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  # 기본 NodeClass는 클러스터의 private compute 서브넷만 사용합니다.
  compute_config {
    enabled       = true
    node_pools    = ["general-purpose", "system"]
    node_role_arn = data.aws_iam_role.node.arn
  }

  kubernetes_network_config {
    ip_family = "ipv4"
    elastic_load_balancing {
      enabled = true
    }
  }

  storage_config {
    block_storage {
      enabled = true
    }
  }

  vpc_config {
    subnet_ids              = aws_subnet.private[*].id
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = var.eks_public_access_cidrs
  }

  # 표준 지원 버전을 명시적으로 선택하고 업그레이드는 별도로 검토합니다.
  upgrade_policy {
    support_type = "STANDARD"
  }

  depends_on = [
    aws_cloudwatch_log_group.eks,
    aws_route.nat,
  ]
}

resource "aws_eks_access_entry" "deployment" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = "arn:aws:iam::${var.aws_account_id}:role/${local.name}-deploy"
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "deployment" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_eks_access_entry.deployment.principal_arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }
}

# scene-api 파드가 사용자 사진 버킷(리뷰 사진)을 쓰는 역할 — bootstrap 이 만든다(내려도 남는다).
# 파드에 키를 두지 않고 Pod Identity 로 그 역할을 받는다. 연결은 클러스터와 함께 생기고 사라진다.
# 계획: docs/project/plans/review.md §13.
data "aws_iam_role" "media" {
  name = "${local.name}-media"
}

resource "aws_eks_pod_identity_association" "scene_api_media" {
  cluster_name    = aws_eks_cluster.this.name
  namespace       = "scenetrip"
  service_account = "scene-api"
  role_arn        = data.aws_iam_role.media.arn
}
