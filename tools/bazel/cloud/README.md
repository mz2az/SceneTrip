# 고정된 AWS 배포 도구

배포 러너의 사전 설치 프로그램 대신 `MODULE.bazel`에 선언한 공식 릴리스와 SHA-256을
사용한다. Bazel의 의존성 취득 단계에서 다운로드하고 실행할 때는 runfiles의 파일만
사용한다. 실행 중에 도구를 다시 설치하거나 호스트의 Python을 사용하지 않는다.

| 타깃 | 버전 | 지원 플랫폼 |
| --- | --- | --- |
| `//tools/bazel/cloud:terraform` | 1.13.5 | Linux amd64, macOS arm64 |
| `//tools/bazel/cloud:helm` | 3.19.0 | Linux amd64, macOS arm64 |
| `//tools/bazel/cloud:kubectl` | 1.34.0 | Linux amd64, macOS arm64 |
| `//tools/bazel/cloud:aws` | 2.31.4 | Linux amd64 배포 러너 |
| `//tools/bazel/cloud:aws_provider` | AWS provider 6.64.0 | Linux amd64, macOS arm64 |

AWS CLI는 자체 Python·공유 라이브러리가 들어 있는 공식 ZIP의 `dist/` 전체를 전달한다.
macOS에서 AWS 변경 명령을 실행하면 지원 범위를 알리고 실패한다. Terraform과 Helm의
오프라인 검증은 macOS에서도 가능하다. Linux의 기본 OS ABI(glibc)는 운영체제가 제공한다.

`tool.bzl`은 선택한 플랫폼의 실행 파일을 runfiles에서 찾는 작은 실행기를 만든다.
배포 도구가 이 타깃들을 `data`에 선언하면 실행 파일과 필요한 런타임이 함께 전달된다.
AWS EKS 인증 플러그인이 같은 AWS CLI를 사용하도록 배포 도구는 이 실행기 디렉터리를
`PATH`의 앞에 추가한다. 인증값을 런처 인자나 로그에 넣지 않는다.

버전과 runfiles 연결은 실제 바이너리를 실행하여 확인한다. 이 검사는 AWS 자격 증명이나
클러스터를 사용하지 않는다.

```sh
just test //tools/bazel/cloud:unit_test
just test //tools/bazel/cloud:terraform_test
```

서비스 배포·Terraform 적용은 환경을 검증하고 확인을 받는 `just` 배포 레시피에서만
실행한다. 개별 CLI 타깃은 그 레시피의 구현에 쓰는 도구이다.

고정값의 출처:

- [Terraform 1.13.5 공식 SHA-256 목록](https://releases.hashicorp.com/terraform/1.13.5/terraform_1.13.5_SHA256SUMS)
- [Helm 3.19.0 공식 릴리스와 플랫폼별 체크섬](https://github.com/helm/helm/releases/tag/v3.19.0)
- [kubectl 공식 Linux 설치·체크섬 검증 절차](https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/)
- [AWS CLI 과거 릴리스 설치](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-version.html)
- [AWS provider 6.64.0 공식 SHA-256 목록](https://releases.hashicorp.com/terraform-provider-aws/6.64.0/terraform-provider-aws_6.64.0_SHA256SUMS)

kubectl 체크섬은 버전별 `dl.k8s.io/release/v1.34.0/bin/<os>/<arch>/kubectl.sha256`과
대조했다. AWS CLI ZIP은 공식 버전 URL에서 받은 파일의 SHA-256을 계산해 고정했다.
AWS의 서명 검증과는 별개이며, 현재 검증 범위를 서명 검증 완료로 표시하지 않는다.
버전을 올릴 때는 원본 배포처의 체크섬을 다시 확인하고 `MODULE.bazel`, 테스트 기대 버전,
이 표를 함께 수정한 다음 `just deps-update`와 관련 검증을 실행한다.

`terraform_test`는 소스를 임시 디렉터리에 복사하고 고정된 provider ZIP을 packed
filesystem mirror로 설정한다. `direct` 설치 경로를 두지 않으므로 실행 중 registry나
AWS에서 내려받을 수 없다. S3 backend를 초기화하지 않고 `validate`와 mock provider
테스트를 실행한다. 원본 lockfile과 state는 변경하지 않는다.
