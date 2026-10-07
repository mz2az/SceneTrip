"""로컬 kind 매니페스트의 MinIO ↔ scene-api 약속을 클러스터 없이 확인한다(review.md §14).

kubectl apply 는 값이 서로 어긋나도 성공한다. 그러면 서버는 뜨고 사진 올리기·옮기기만 서명 불일치나 연결 실패로
조용히 깨진다. 여기서 같은 값을 같은 자리에 두었는지 본다.

외부 패키지(PyYAML)가 없어 필요한 몇 줄만 읽는다 — 매니페스트는 평평한 `키: 값` 줄이다.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
K8S = ROOT / "platform/kubernetes"


def config_map_data(path):
    """ConfigMap 의 data: 아래 `KEY: value` 들. 따옴표는 벗긴다."""
    data = {}
    inside = False
    for line in path.read_text().splitlines():
        if re.match(r"^data:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line.startswith(" "):
                break
            m = re.match(r"^\s{2}([A-Za-z0-9_]+):\s*(.*?)\s*$", line)
            if m:
                value = m.group(2)
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                data[m.group(1)] = value
    return data


class LocalMediaManifestTest(unittest.TestCase):
    def setUp(self):
        self.minio = config_map_data(K8S / "minio/configmap.yaml")
        self.api = config_map_data(K8S / "scene-api/configmap.yaml")

    def test_scene_api_uses_minio_root_credentials(self):
        self.assertTrue(self.minio["MINIO_ROOT_USER"])
        self.assertTrue(self.minio["MINIO_ROOT_PASSWORD"])
        self.assertEqual(
            self.api["SCENETRIP_MEDIA_ACCESS_KEY"], self.minio["MINIO_ROOT_USER"]
        )
        self.assertEqual(
            self.api["SCENETRIP_MEDIA_SECRET_KEY"], self.minio["MINIO_ROOT_PASSWORD"]
        )

    def test_bucket_created_by_minio_is_the_one_scene_api_uses(self):
        self.assertTrue(self.minio["MEDIA_BUCKET"])
        self.assertEqual(self.api["SCENETRIP_MEDIA_BUCKET"], self.minio["MEDIA_BUCKET"])
        # 버킷은 StatefulSet 의 postStart 가 그 이름으로 만든다.
        statefulset = (K8S / "minio/statefulset.yaml").read_text()
        self.assertIn('mc mb --ignore-existing "local/$MEDIA_BUCKET"', statefulset)
        self.assertRegex(statefulset, r"configMapRef:\s*\n\s*name: minio\b")

    def test_endpoint_is_minio_service_in_cluster(self):
        self.assertEqual(self.api["SCENETRIP_MEDIA_ENDPOINT"], "http://minio:9000")
        service = (K8S / "minio/service.yaml").read_text()
        self.assertRegex(service, r"(?m)^  name: minio$")
        self.assertRegex(service, r"(?m)^\s+port: 9000$")

    def test_public_port_is_kind_host_port_of_minio_node_port(self):
        service = (K8S / "minio/service.yaml").read_text()
        self.assertRegex(service, r"(?m)^\s+type: NodePort$")
        node_port = re.search(r"(?m)^\s+nodePort: (\d+)$", service).group(1)
        self.assertEqual(node_port, "30090")

        cluster = (ROOT / "platform/kind/cluster.yaml").read_text()
        mappings = re.findall(r"containerPort: (\d+)\s*\n\s*hostPort: (\d+)", cluster)
        host_ports = [host for container, host in mappings if container == node_port]
        self.assertEqual(
            host_ports, ["9000"], f"kind 가 NodePort {node_port} 를 잇지 않는다"
        )
        self.assertEqual(self.api["SCENETRIP_MEDIA_PUBLIC_PORT"], host_ports[0])

    def test_region_is_set_for_signing(self):
        # MinIO 의 기본 리전. 서명 범위에 들어가므로 비어 서버 기본값(ap-northeast-2)이 되면 안 된다.
        self.assertEqual(self.api["SCENETRIP_MEDIA_REGION"], "us-east-1")

    def test_scene_api_deployment_reads_its_config_map(self):
        deployment = (K8S / "scene-api/deployment.yaml").read_text()
        self.assertRegex(deployment, r"configMapRef:\s*\n\s*name: scene-api\b")

    def test_stack_up_deploys_minio(self):
        script = (ROOT / "tools/scripts/stack-up.sh").read_text()
        self.assertRegex(
            script,
            r'(?m)^"\$SCRIPTS/deploy\.sh" minio local$',
            "stack-up.sh 가 minio 를 배포하지 않는다",
        )


if __name__ == "__main__":
    unittest.main()
