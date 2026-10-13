"""기존 지역 가이드의 ID·코스를 보존하는 로컬 블로그 사진 적용."""

import argparse
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import uuid
from pathlib import Path

from community_blog_rules import article, load_assets, storage_key
from community_guide_rules import validate_guides
from community_guides_transport import LocalAPI, store_state

UPLOAD_PART_SIZE = "16MiB"

SNAPSHOT_SQL = """
SELECT jsonb_build_object(
 'posts',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY id),'[]'::JSONB) FROM community_post p),
 'photos',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY post_id,sort_order),'[]'::JSONB) FROM community_post_photo p),
 'items',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY post_id,day_no,sort_order),'[]'::JSONB) FROM community_post_item p),
 'courses',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY id),'[]'::JSONB) FROM course p),
 'courseItems',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY course_id,day_no,sort_order),'[]'::JSONB) FROM course_item p));
"""


def digest(blob):
    return hashlib.sha256(blob).hexdigest()


def kube(resource, command, blob=None, *, allow_failure=False):
    result = subprocess.run(
        [
            "kubectl",
            "--context",
            "kind-scenetrip",
            "exec",
            "-i",
            resource,
            "-n",
            "scenetrip",
            "--",
            *command,
        ],
        input=blob,
        capture_output=True,
        timeout=90,
        check=False,
    )
    if result.returncode and not allow_failure:
        raise RuntimeError(
            "로컬 도구 실패: " + result.stderr.decode(errors="replace")[-300:]
        )
    return result


def db(sql, payload=None):
    if payload is not None:
        # JSON은 CSV 데이터 프레임으로 보낸다. SQL 구문에 값이나 따옴표를 대입하지 않는다.
        stream = io.StringIO()
        csv.writer(stream, quoting=csv.QUOTE_ALL, lineterminator="\n").writerow(
            [json.dumps(payload)]
        )
        before, after = sql.split("-- BLOG_DATA", 1)
        sql = before + stream.getvalue() + "\\.\n" + after
    result = kube(
        "statefulset/postgres",
        [
            "psql",
            "-X",
            "-qAt",
            "-U",
            "scenetrip",
            "-d",
            "scenetrip",
            "-v",
            "ON_ERROR_STOP=1",
            "-f",
            "-",
        ],
        sql.encode(),
    )
    return result.stdout.decode().strip()


def guard_local():
    if os.environ.get("SCENETRIP_DB_HOST"):
        raise ValueError("공유 DB 접속 설정에서는 실행하지 않습니다")
    context = (
        subprocess.check_output(["kubectl", "config", "current-context"])
        .decode()
        .strip()
    )
    if context != "kind-scenetrip":
        raise ValueError("현재 컨텍스트가 로컬 kind-scenetrip이어야 합니다")
    if (
        db(
            "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='community_post' AND column_name='body_blocks');"
        )
        != "t"
    ):
        raise ValueError("블로그형 본문의 V32 마이그레이션이 먼저 필요합니다")
    bucket = kube(
        "statefulset/minio", ["sh", "-c", 'printf "%s" "$MEDIA_BUCKET"']
    ).stdout.decode()
    if not re.fullmatch(r"[a-z0-9.-]{3,63}", bucket):
        raise ValueError("로컬 사진 버킷 확인 실패")
    return bucket


def object_blob(bucket, key):
    if not re.fullmatch(r"posts/[0-9a-f-]{36}\.(jpg|png|webp)", key):
        raise ValueError("게시글 사진 키의 형식이 다릅니다")
    return kube("statefulset/minio", ["mc", "cat", f"local/{bucket}/{key}"]).stdout


def ensure_object(bucket, key, blob):
    target = f"local/{bucket}/{key}"
    found = kube(
        "statefulset/minio", ["mc", "stat", "--json", target], allow_failure=True
    )
    if found.returncode:
        records = [
            json.loads(line)
            for line in found.stdout.decode().splitlines()
            if line.strip()
        ]
        causes = [record.get("error", {}).get("cause", {}) for record in records]
        missing = any(
            cause.get("error", {}).get("Code") in ("NoSuchKey", "NoSuchObject")
            or cause.get("message") == "Object does not exist"
            for cause in causes
        )
        if not missing:
            raise RuntimeError("새 사진 키의 빈 상태를 확인하지 못했습니다")
        # 조건부 PUT은 다른 작업이 만든 객체를 덮지 못하게 한다.
        uploaded = kube(
            "statefulset/minio",
            [
                "mc",
                "--custom-header",
                "If-None-Match:*",
                "pipe",
                "--part-size",
                UPLOAD_PART_SIZE,
                "--concurrent",
                "1",
                "--attr",
                "Content-Type=image/jpeg",
                target,
            ],
            blob,
            allow_failure=True,
        )
        # 업로드 응답 유실이나 경쟁도 저장된 내용으로 판정한다.
        if uploaded.returncode and digest(object_blob(bucket, key)) != digest(blob):
            raise RuntimeError("사진 저장 확인 실패")
    if digest(object_blob(bucket, key)) != digest(blob):
        raise RuntimeError("같은 사진 키에 다른 내용이 있습니다. 덮어쓰지 않습니다")


def load_baseline(path, data_bytes, guides, assets):
    baseline = json.loads((path / "state.json").read_text())
    if (
        baseline.get("datasetSha256") != digest(data_bytes)
        or baseline.get("author") != "씬트립가이드"
    ):
        raise ValueError("기존 가이드 상태와 현재 자료 판이 다릅니다")
    baseline["user"] = str(uuid.UUID(baseline["user"]))
    baseline["install"] = str(uuid.UUID(baseline["install"]))
    if set(baseline.get("entries", {})) != {g["slug"] for g in guides}:
        raise ValueError("기존 글의 가이드 목록이 다릅니다")
    ids = []
    for guide in guides:
        entry = baseline["entries"][guide["slug"]]
        if (
            entry.get("postPending")
            or entry.get("coursePending")
            or type(entry.get("postId")) is not int
            or entry["postId"] < 1
        ):
            raise ValueError("기존 글의 확정 상태가 필요합니다")
        if len(entry.get("photoKeys", [])) != len(guide["photos"]) or len(
            entry.get("photoHashes", [])
        ) != len(guide["photos"]):
            raise ValueError("기존 사진 상태의 수가 다릅니다")
        ids.append(entry["postId"])
        for index, expected in enumerate(entry["photoHashes"]):
            if assets[(guide["slug"], index)]["inputSha256"] != expected:
                raise ValueError(
                    "변환 사진의 입력 원본이 기존 첨부 사진과 다릅니다: "
                    + guide["slug"]
                )
    if len(set(ids)) != len(ids):
        raise ValueError("기존 글 ID가 중복됐습니다")
    return baseline


def make_entry(guide, baseline, assets, snapshot):
    original = baseline["entries"][guide["slug"]]
    post_id, course_id = original["postId"], original["courseId"]
    post = next(p for p in snapshot["posts"] if p["id"] == post_id)
    photos = [p for p in snapshot["photos"] if p["post_id"] == post_id]
    keys = ["posts/" + Path(key).name for key in original["photoKeys"]]
    if (
        post["user_id"] != baseline["user"]
        or post["title"] != guide["title"]
        or post["body"] != guide["body"]
        or post.get("removed_at")
        or post.get("body_blocks") is not None
    ):
        raise ValueError("기존 글이 가이드 기준과 다릅니다. 기존 내용을 보존했습니다")
    if [p["storage_key"] for p in photos] != keys or [
        p["sort_order"] for p in photos
    ] != list(range(len(keys))):
        raise ValueError("기존 글의 사진 키·순서가 다릅니다")
    rendered = article(guide, [assets[(guide["slug"], i)] for i in range(len(keys))])
    new_post = {**post, "body": rendered["body"], "body_blocks": rendered["blocks"]}
    new_photos = [
        {
            **p,
            "storage_key": storage_key(
                guide["slug"], i, assets[(guide["slug"], i)]["outputSha256"]
            ),
        }
        for i, p in enumerate(photos)
    ]
    return {
        "id": post_id,
        "slug": guide["slug"],
        "courseId": course_id,
        "oldPost": post,
        "newPost": new_post,
        "oldPhotos": photos,
        "newPhotos": new_photos,
        "oldItems": [p for p in snapshot["items"] if p["post_id"] == post_id],
        "oldCourse": next(p for p in snapshot["courses"] if p["id"] == course_id),
        "oldCourseItems": [
            p for p in snapshot["courseItems"] if p["course_id"] == course_id
        ],
    }


def journal(directory, guides, baseline, assets, data_hash, manifest_hash):
    path = directory / "patch.json"
    if path.exists():
        patch = json.loads(path.read_text())
        if (
            patch.get("datasetSha256") != data_hash
            or patch.get("manifestSha256") != manifest_hash
            or patch.get("owner") != baseline["user"]
        ):
            raise ValueError(
                "이 상태 폴더의 자료 판이 다릅니다. 원래 기록을 보존했습니다"
            )
        return patch
    snapshot = json.loads(db(SNAPSHOT_SQL))
    entries = [make_entry(g, baseline, assets, snapshot) for g in guides]
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    store_state(directory / "rows-before.json", snapshot)
    patch = {
        "owner": baseline["user"],
        "datasetSha256": data_hash,
        "manifestSha256": manifest_hash,
        "entries": entries,
    }
    store_state(path, patch)
    return patch


def apply(root, directory, bucket, patch, baseline, assets):
    originals = directory / "original-objects"
    originals.mkdir(mode=0o700, exist_ok=True)
    for entry in patch["entries"]:
        for index, (old, new) in enumerate(zip(entry["oldPhotos"], entry["newPhotos"])):
            path = originals / Path(old["storage_key"]).name
            expected = baseline["entries"][entry["slug"]]["photoHashes"][index]
            if not path.exists():
                original_blob = object_blob(bucket, old["storage_key"])
                if digest(original_blob) != expected:
                    raise ValueError("기존 사진 원본의 해시가 다릅니다")
                temporary = path.with_suffix(".partial")
                temporary.write_bytes(original_blob)
                temporary.chmod(0o600)
                if digest(temporary.read_bytes()) != expected:
                    raise ValueError(
                        "사진 백업이 불완전합니다. 원래 사진을 보존했습니다"
                    )
                temporary.replace(path)
            if digest(path.read_bytes()) != expected:
                raise ValueError("기존 사진의 원본 해시가 기준 상태와 다릅니다")
            asset = assets[(entry["slug"], index)]
            blob = (root / asset["file"]).read_bytes()
            if digest(blob) != asset["outputSha256"]:
                raise ValueError("업로드 직전 변환 사진의 내용이 달라졌습니다")
            ensure_object(bucket, new["storage_key"], blob)
        print(
            json.dumps(
                {"guide": entry["slug"], "photosReady": len(entry["newPhotos"])}
            ),
            flush=True,
        )
    db(Path(__file__).with_name("community-blog-apply.sql").read_text(), patch)
    store_state(
        directory / "applied.json",
        {"posts": len(patch["entries"]), "manifestSha256": patch["manifestSha256"]},
    )


def verify_rows(entry, snapshot):
    current = next(p for p in snapshot["posts"] if p["id"] == entry["id"])
    photos = [p for p in snapshot["photos"] if p["post_id"] == entry["id"]]
    if current != entry["newPost"] or photos != entry["newPhotos"]:
        raise ValueError("적용된 글·사진 순서가 제작 결과와 다릅니다")
    if [p for p in snapshot["items"] if p["post_id"] == entry["id"]] != entry[
        "oldItems"
    ]:
        raise ValueError("기존 글의 첨부 코스 사본이 달라졌습니다")
    if (
        next(p for p in snapshot["courses"] if p["id"] == entry["courseId"])
        != entry["oldCourse"]
        or [p for p in snapshot["courseItems"] if p["course_id"] == entry["courseId"]]
        != entry["oldCourseItems"]
    ):
        raise ValueError("기존 원본 코스가 달라졌습니다")


def verify_detail(api, entry, bucket, baseline, assets):
    detail = api.call("GET", f"/posts/{entry['id']}")
    clean = clean_blocks(detail)
    if (
        detail["body"] != entry["newPost"]["body"]
        or clean != entry["newPost"]["body_blocks"]
        or len(detail["photoUrls"]) != len(entry["newPhotos"])
    ):
        raise ValueError("실제 API의 글·문단 배치·사진 수가 다릅니다")
    for index, url in enumerate(detail["photoUrls"]):
        if (
            digest(api.call("GET", url, member=False))
            != assets[(entry["slug"], index)]["outputSha256"]
        ):
            raise ValueError("API 사진 주소의 실제 파일이 제작 결과와 다릅니다")
        if (
            digest(object_blob(bucket, entry["oldPhotos"][index]["storage_key"]))
            != baseline["entries"][entry["slug"]]["photoHashes"][index]
        ):
            raise ValueError("기존 사진 원본이 달라졌습니다")


def clean_blocks(detail):
    return [
        {k: v for k, v in block.items() if v is not None}
        for block in detail.get("bodyBlocks", [])
    ]


def verify(bucket, patch, baseline, assets):
    api = LocalAPI(baseline["user"], baseline["install"])
    snapshot = json.loads(db(SNAPSHOT_SQL))
    for entry in patch["entries"]:
        verify_rows(entry, snapshot)
        verify_detail(api, entry, bucket, baseline, assets)
        print(
            json.dumps(
                {"guide": entry["slug"], "postId": entry["id"], "verified": True}
            ),
            flush=True,
        )


def smoke_upload(api, root, asset):
    blob = (root / asset["file"]).read_bytes()
    if digest(blob) != asset["outputSha256"]:
        raise ValueError("시험 사진의 해시가 달라졌습니다")
    uploaded = api.call(
        "POST",
        "/uploads",
        {"purpose": "post", "contentType": "image/jpeg", "bytes": len(blob)},
    )
    key = uploaded["key"]
    if not re.fullmatch(r"uploads/tmp/[0-9a-f-]{36}\.jpg", key):
        raise ValueError("시험 사진의 임시 키 형식이 다릅니다")
    api.call(
        "PUT",
        uploaded["uploadUrl"],
        blob,
        headers=uploaded["requiredHeaders"],
        member=False,
    )
    return key


def smoke_blocks(asset):
    return [
        {"kind": "text", "text": "사진 앞의 문단"},
        {
            "kind": "photo",
            "photoIndex": 0,
            "caption": asset["place"],
            "credit": asset["credit"],
            "aiGenerated": True,
        },
        {"kind": "text", "text": "사진 뒤의 문단"},
    ]


def verify_smoke(api, post_id, blocks, asset):
    read = api.call("GET", f"/posts/{post_id}")
    if (
        clean_blocks(read) != blocks
        or read["body"] != "사진 앞의 문단\n\n사진 뒤의 문단"
        or digest(api.call("GET", read["photoUrls"][0], member=False))
        != asset["outputSha256"]
    ):
        raise ValueError("실제 게시·문단 순서·사진 바이트 확인 실패")


def smoke(root, directory, bucket, baseline, assets):
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    state_path = directory / "smoke.json"
    if state_path.exists():
        raise ValueError("앞선 시험 게시 기록이 있습니다. 중복으로 보내지 않습니다")
    api = LocalAPI(baseline["user"], baseline["install"])
    asset = next(iter(assets.values()))
    key = smoke_upload(api, root, asset)
    blocks = smoke_blocks(asset)
    record = {
        "title": "QA428-" + str(uuid.uuid4()),
        "pending": True,
        "temporaryKey": key,
    }
    store_state(state_path, record)
    detail = api.call(
        "POST",
        "/posts",
        {
            "title": record["title"],
            "body": "사진 앞의 문단\n\n사진 뒤의 문단",
            "photoKeys": [key],
            "bodyBlocks": blocks,
        },
    )
    record.update(postId=detail["id"], pending=False)
    store_state(state_path, record)
    verify_smoke(api, record["postId"], blocks, asset)
    api.call("DELETE", f"/posts/{record['postId']}")
    # 이 시험에서 새로 만든 사진 한 장만 정리한다.
    kube("statefulset/minio", ["mc", "rm", f"local/{bucket}/posts/{Path(key).name}"])
    record.update(verified=True, cleanedUp=True)
    store_state(state_path, record)
    print(
        json.dumps(
            {
                "apiPostLayoutRoundTrip": True,
                "photoUploadAndRead": True,
                "testPostRemoved": True,
            }
        )
    )


def arguments():
    parser = argparse.ArgumentParser(
        description="기존 추천 글의 로컬 블로그형 본문·사진 적용"
    )
    parser.add_argument("baseline_directory", type=Path)
    parser.add_argument("state_directory", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--smoke", action="store_true")
    return parser.parse_args()


def materials(root, baseline_directory):
    data_bytes = (
        root / "docs/product/community-guides/regional-guides-2026-10.json"
    ).read_bytes()
    guides = json.loads(data_bytes)
    validate_guides(guides)
    manifest_bytes = (
        root / "docs/product/community-guides/ai-images/manifest.json"
    ).read_bytes()
    assets = load_assets(root, json.loads(manifest_bytes), guides)
    baseline = load_baseline(baseline_directory, data_bytes, guides, assets)
    return guides, assets, baseline, digest(data_bytes), digest(manifest_bytes)


def main():
    args = arguments()
    root = Path(os.environ["BUILD_WORKSPACE_DIRECTORY"])
    guides, assets, baseline, data_hash, manifest_hash = materials(
        root, args.baseline_directory
    )
    print(
        json.dumps(
            {
                "environment": "local kind-scenetrip",
                "guides": len(guides),
                "attachments": len(assets),
                "apply": args.apply,
            }
        )
    )
    if not args.apply and not args.verify and not args.smoke:
        for guide in guides:
            article(
                guide, [assets[(guide["slug"], i)] for i in range(len(guide["photos"]))]
            )
        return
    bucket = guard_local()
    if args.smoke:
        smoke(root, args.state_directory, bucket, baseline, assets)
        return
    patch = journal(
        args.state_directory,
        guides,
        baseline,
        assets,
        data_hash,
        manifest_hash,
    )
    if args.apply:
        apply(root, args.state_directory, bucket, patch, baseline, assets)
    verify(bucket, patch, baseline, assets)
    store_state(
        args.state_directory / "verified.json",
        {
            "posts": len(guides),
            "photos": len(assets),
            "manifestSha256": manifest_hash,
        },
    )


if __name__ == "__main__":
    main()
