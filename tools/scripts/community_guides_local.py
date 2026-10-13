"""just community-guides-local: 지역별 사진·코스 가이드를 로컬에 적재한다."""

import argparse
import fcntl
import hashlib
import json
import os
import sys
import uuid
from pathlib import Path
from urllib.parse import urlparse

from community_guide_rules import course_items, distance_km, validate_guides
from community_guides_transport import (
    APIError,
    LocalAPI,
    local_author,
    photo_file,
    store_state,
)


def identities(stops):
    return [
        (
            s.get("source"),
            s.get("placeId") if s.get("source") == "place" else s.get("poiId"),
        )
        for s in stops
    ]


def check_target(api, stop):
    path = "/places/" if stop["source"] == "place" else "/pois/"
    detail = api.call("GET", path + str(stop["id"]))
    location = {"lat": detail["latitude"], "lng": detail["longitude"]}
    if detail["name"] != stop["name"] or distance_km(location, stop) > 0.1:
        raise ValueError(
            "DB 대상의 이름·좌표가 준비한 자료와 달라 확인이 필요합니다: "
            + stop["name"]
        )
    if stop["source"] == "place" and stop.get("sourceContentId") not in {
        s["contentId"] for s in detail["scenes"]
    }:
        raise ValueError("코스의 작품 출처가 실제 장소에 연결되지 않았습니다")
    linked = detail.get("placeId") if stop["source"] == "poi" else None
    if linked is not None and (type(linked) is not int or linked < 1):
        raise ValueError("편의시설의 촬영지 연결 번호가 올바르지 않습니다")
    return [
        "place" if linked else stop["source"],
        linked or stop["id"],
        stop["dwellMinutes"],
    ]


def matches_course(course, guide, expected, field):
    days = course.get("days", []) if course else []
    if not course or course.get("title") != guide["courseTitle"] or len(days) != 1:
        return False
    if days[0].get("dayNumber") != 1:
        return False
    actual = [
        list(identity) + [stop.get("dwellMinutes")]
        for identity, stop in zip(
            identities(days[0].get(field, [])), days[0].get(field, []), strict=True
        )
    ]
    return actual == expected


def load_state(path, digest):
    if path.exists():
        state = json.loads(path.read_text())
        if (
            state.get("datasetSha256") != digest
            or state.get("author") != "씬트립가이드"
        ):
            raise ValueError("다른 자료의 상태 파일을 덮어쓰지 않습니다")
        for field in ("user", "install"):
            uuid.UUID(state[field])
        return state
    state = {
        "datasetSha256": digest,
        "author": "씬트립가이드",
        "user": str(uuid.uuid4()),
        "install": str(uuid.uuid4()),
        "entries": {},
    }
    store_state(path, state)
    return state


def find_lost_course(api, guide):
    matches = [
        c
        for c in api.call("GET", "/courses")["items"]
        if c["title"] == guide["courseTitle"]
    ]
    if len(matches) != 1:
        raise RuntimeError(
            "코스 생성 응답이 미확정입니다. 자동으로 다시 만들지 않습니다"
        )
    return matches[0]["id"]


def prepare_course(api, guide, entry, persist):
    if entry.get("coursePending") and not entry.get("courseId"):
        entry["courseId"] = find_lost_course(api, guide)
        persist()
    if not entry.get("courseId"):
        entry["coursePending"] = True
        persist()
        try:
            detail = api.call(
                "POST",
                "/courses",
                {
                    "title": guide["courseTitle"],
                    "dayCount": 1,
                    "origin": "self",
                },
            )
        except APIError as error:
            if error.status in {400, 401, 403, 404, 409, 422}:
                entry["coursePending"] = False
                entry["lastCourseRejection"] = error.status
                persist()
            raise
        entry["courseId"] = detail["id"]
        persist()
    detail = api.call(
        "PUT",
        "/courses/" + str(entry["courseId"]),
        {"title": guide["courseTitle"], "days": [{"items": course_items(guide)}]},
    )
    if not matches_course(detail, guide, entry["expectedStops"], "items"):
        raise RuntimeError("생성 코스의 일차·대상·순서·체류 시간이 요청과 다릅니다")
    entry["coursePending"] = False
    persist()


def prepare_photos(api, guide, entry, directory, persist):
    entry.setdefault("photoKeys", [])
    entry.setdefault("photoHashes", [])
    for photo in guide["photos"][len(entry["photoKeys"]) :]:
        path, content_type = photo_file(photo, directory)
        blob = path.read_bytes()
        ticket = api.call(
            "POST",
            "/uploads",
            {"purpose": "post", "contentType": content_type, "bytes": len(blob)},
        )
        api.call("PUT", ticket["uploadUrl"], blob, headers=ticket["requiredHeaders"])
        entry["photoKeys"].append(ticket["key"])
        entry["photoHashes"].append(hashlib.sha256(blob).hexdigest())
        persist()


def matches_post(detail, guide, entry):
    if detail.get("title") != guide["title"] or detail.get("body") != guide["body"]:
        return False
    filenames = [
        urlparse(url).path.rsplit("/", 1)[-1] for url in detail.get("photoUrls", [])
    ]
    expected = [key.rsplit("/", 1)[-1] for key in entry["photoKeys"]]
    return filenames == expected and matches_course(
        detail.get("course"), guide, entry["expectedStops"], "stops"
    )


def recover_post(api, guide, entry):
    candidates = api.call("GET", "/me/posts?limit=100")["items"]
    matches = []
    for summary in candidates:
        if summary["title"] == guide["title"]:
            detail = api.call("GET", "/posts/" + str(summary["id"]))
            if matches_post(detail, guide, entry):
                matches.append(detail["id"])
    if len(matches) != 1:
        raise RuntimeError(
            "게시 응답이 미확정입니다. 같은 글을 자동으로 다시 보내지 않습니다"
        )
    return matches[0]


def publish(api, guide, entry, persist):
    if entry.get("postPending"):
        entry["postId"] = recover_post(api, guide, entry)
    elif not entry.get("postId"):
        entry["postPending"] = True
        persist()
        try:
            detail = api.call(
                "POST",
                "/posts",
                {
                    "title": guide["title"],
                    "body": guide["body"],
                    "courseId": entry["courseId"],
                    "photoKeys": entry["photoKeys"],
                },
            )
        except APIError as error:
            if error.status in {400, 401, 403, 404, 409, 422}:
                entry["postPending"] = False
                entry["lastRejection"] = error.status
                if error.status == 400:
                    entry.setdefault("rejectedPhotoKeys", []).extend(entry["photoKeys"])
                    entry["photoKeys"] = []
                    entry["photoHashes"] = []
                persist()
            raise
        if not matches_post(detail, guide, entry):
            raise RuntimeError("새 게시 응답의 본문·사진·코스를 확인하지 못했습니다")
        entry["postId"] = detail["id"]
    entry["postPending"] = False
    persist()


def verify(api, guides, state):
    public = api.call("GET", "/posts?limit=100", member=False)
    public_ids = {post["id"] for post in public["items"]}
    for guide in guides:
        entry = state["entries"][guide["slug"]]
        if entry["postId"] not in public_ids:
            raise RuntimeError("공개 목록에서 가이드가 보이지 않습니다")
        detail = api.call("GET", "/posts/" + str(entry["postId"]), member=False)
        if detail["isMine"] or not matches_post(detail, guide, entry):
            raise RuntimeError(
                "비회원 조회의 본문·소유·사진·코스를 확인하지 못했습니다"
            )
        for url, expected in zip(
            detail["photoUrls"], entry["photoHashes"], strict=True
        ):
            if (
                hashlib.sha256(api.call("GET", url, member=False)).hexdigest()
                != expected
            ):
                raise RuntimeError("공개 사진 바이트가 원본과 다릅니다")
        print("검증:", guide["slug"], len(detail["photoUrls"]), "장", flush=True)


def apply_guides(api, guides, state, directory, state_path):
    checked = {}
    for guide in guides:
        for stop in guide["stops"]:
            identity = (stop["source"], stop["id"], stop.get("sourceContentId"))
            if identity not in checked:
                checked[identity] = check_target(api, stop)[:2]
    for guide in guides:
        entry = state["entries"].setdefault(guide["slug"], {})
        persist = lambda: store_state(state_path, state)
        if entry.get("postId"):
            print("이미 준비됨:", guide["slug"], flush=True)
            continue
        entry["expectedStops"] = [
            checked[(stop["source"], stop["id"], stop.get("sourceContentId"))]
            + [stop["dwellMinutes"]]
            for stop in guide["stops"]
        ]
        prepare_course(api, guide, entry, persist)
        prepare_photos(api, guide, entry, directory, persist)
        publish(api, guide, entry, persist)
        print(
            "게시:",
            guide["slug"],
            "글",
            entry["postId"],
            "코스",
            entry["courseId"],
            flush=True,
        )


def persist_guides(args, guides, source, directory):
    directory.chmod(0o700)
    with (directory / ".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("같은 가이드 적재가 이미 실행 중입니다") from None
        state_path = directory / "state.json"
        if args.verify and not args.apply and not state_path.exists():
            raise ValueError("먼저 로컬에 적재한 자료의 상태 파일이 필요합니다")
        state = load_state(state_path, hashlib.sha256(source.read_bytes()).hexdigest())
        if args.apply:
            local_author(state["user"])
        api = LocalAPI(state["user"], state["install"])
        if args.apply:
            apply_guides(api, guides, state, directory, state_path)
        verify(api, guides, state)
        state["verifiedPosts"] = len(guides)
        store_state(state_path, state)
    print("가이드 20개 본문·공개 사진·코스 사본 검증 완료")


def main():
    parser = argparse.ArgumentParser(
        description="지역 가이드 자료를 로컬에만 준비합니다"
    )
    parser.add_argument("dataset")
    parser.add_argument("state_directory")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    workspace = Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", "."))
    source = Path(args.dataset)
    if not source.is_absolute():
        source = workspace / source
    guides = validate_guides(json.loads(source.read_text()))
    if len(guides) != 20:
        raise ValueError("이번 자료는 지역 가이드 20개여야 합니다")
    directory = Path(args.state_directory).expanduser().resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if args.download:
        for guide in guides:
            for photo in guide["photos"]:
                path, _ = photo_file(photo, directory)
                print("사진 확인:", photo["caption"], path, flush=True)
    if not args.apply and not args.verify:
        print(
            "자료 검증 통과:", len(guides), "개. --apply를 지정하면 로컬에 적재합니다"
        )
        return
    persist_guides(args, guides, source, directory)


if __name__ == "__main__":
    try:
        main()
    except (TypeError, ValueError, RuntimeError, APIError, KeyError) as error:
        print("가이드 준비 중단:", str(error), file=sys.stderr)
        raise SystemExit(1) from None
