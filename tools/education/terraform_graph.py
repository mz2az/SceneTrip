"""고정 Terraform과 provider로 격리된 복사본에서 실제 엔진 그래프를 만든다."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

from python.runfiles import runfiles
from source_diagrams import block_body, fingerprint


def without_backend(source):
    matches = list(re.finditer(r'backend\s+"[^"]+"\s*\{', source))
    for match in reversed(matches):
        body = block_body(source, match.end())
        source = source[: match.start()] + source[match.end() + len(body) + 1 :]
    return source


def invoke(binary, arguments, directory, environment):
    result = subprocess.run(
        [str(binary), *arguments],
        cwd=directory,
        env=environment,
        text=True,
        capture_output=True,
        timeout=120,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(
            f"Terraform {arguments[0]} 실패: {result.stderr or result.stdout}"
        )
    return result.stdout


def engine_graph(root, binary, provider):
    source = root / "platform/terraform/aws"
    paths = sorted(
        str(path.relative_to(root))
        for path in source.iterdir()
        if path.suffix == ".tf" or path.name == ".terraform.lock.hcl"
    )
    before = fingerprint(root, paths)
    with tempfile.TemporaryDirectory(prefix="scenetrip-education-graph-") as temporary:
        work = Path(temporary)
        isolated = work / "aws"
        isolated.mkdir()
        for name in paths:
            path = root / name
            text = path.read_text()
            (isolated / path.name).write_text(
                without_backend(text) if path.suffix == ".tf" else text
            )
        config = work / "terraform.rc"
        config.write_text(
            "provider_installation {\n filesystem_mirror {\n"
            f" path = {json.dumps(str(provider.parents[3]))}\n }}\n}}\n"
        )
        inherited = {
            key: value
            for key, value in os.environ.items()
            if key
            in (
                "PATH",
                "HOME",
                "TMPDIR",
                "RUNFILES_DIR",
                "RUNFILES_MANIFEST_FILE",
                "TEST_SRCDIR",
                "TEST_WORKSPACE",
                "LANG",
                "LC_ALL",
                "SYSTEMROOT",
            )
        }
        environment = {
            **inherited,
            "TF_CLI_CONFIG_FILE": str(config),
            "TF_DATA_DIR": str(work / "terraform-data"),
            "TF_IN_AUTOMATION": "true",
            "TF_INPUT": "false",
            "AWS_EC2_METADATA_DISABLED": "true",
            "AWS_SHARED_CREDENTIALS_FILE": os.devnull,
            "AWS_CONFIG_FILE": os.devnull,
        }
        commands = [
            [
                "init",
                "-backend=false",
                "-input=false",
                "-lockfile=readonly",
                "-no-color",
            ],
            ["graph"],
        ]
        for arguments in commands[:1]:
            invoke(binary, arguments, isolated, environment)
        graph = invoke(binary, commands[1], isolated, environment)
        version = json.loads(
            invoke(binary, ["version", "-json"], isolated, environment)
        )
    if fingerprint(root, paths) != before:
        raise RuntimeError(
            "원본 Terraform 소스가 실행 중 변경되었습니다. 안정된 소스로 다시 실행하세요"
        )
    if not graph.lstrip().startswith("digraph"):
        raise ValueError("Terraform graph가 DOT 형식으로 응답하지 않았습니다")
    provenance = {
        "kind": "terraform-engine-configuration-graph-not-plan-not-live-aws",
        "terraform_version": version["terraform_version"],
        "commands": commands,
        "source_transform": "backend blocks removed from temporary copy only",
        "sources": before,
        "provider_archive_sha256": hashlib.sha256(provider.read_bytes()).hexdigest(),
        "dot_sha256": hashlib.sha256(graph.encode()).hexdigest(),
    }
    return graph, provenance


def save_graph(root, graph, provenance, check):
    directory = root / "docs/education/diagrams"
    outputs = {
        "terraform-graph.dot": graph,
        "terraform-graph-provenance.json": json.dumps(
            provenance, ensure_ascii=False, sort_keys=True, indent=2
        )
        + "\n",
    }
    valid = True
    for name, content in outputs.items():
        path = directory / name
        if check:
            if not path.exists() or path.read_text() != content:
                print(f"Terraform 그래프가 소스와 다릅니다: {name}")
                valid = False
        else:
            directory.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    return valid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider-runfile", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    locator = runfiles.Create()
    root = Path(os.environ["BUILD_WORKSPACE_DIRECTORY"])
    binary = Path(locator.Rlocation("_main/tools/bazel/cloud/terraform"))
    provider = Path(locator.Rlocation(args.provider_runfile))
    graph, provenance = engine_graph(root, binary, provider)
    provenance["sources"].update(
        fingerprint(root, ["tools/education/terraform_graph.py"])
    )
    return 0 if save_graph(root, graph, provenance, args.check) else 1


if __name__ == "__main__":
    raise SystemExit(main())
