"""서로 먼 지역·잘못된 사진 출처·서버 대상 혼동을 적재 전에 거절한다."""

import base64
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from community_guide_rules import course_items, local_url, validate_guides
from community_guides_local import (
    check_target,
    load_state,
    matches_course,
    matches_post,
    publish,
)
from community_guides_transport import (
    MAX_PHOTO_BYTES,
    APIError,
    LocalRedirect,
    PhotoRedirect,
    access_token,
    download_photo,
    photo_file,
    store_state,
)


def sample():
    return [
        {
            "slug": "seoul-example",
            "title": "서울 성지 산책",
            "courseTitle": "서울 성지 산책",
            "body": "SceneTrip 추천 가이드\n경복궁에서 북촌까지 걷습니다.",
            "region": "seoul",
            "sources": ["https://www.seoul.go.kr/story/seoultravel/pc.html"],
            "stops": [
                {
                    "source": "place",
                    "id": 90,
                    "name": "경복궁",
                    "lat": 37.58,
                    "lng": 126.977,
                    "dwellMinutes": 90,
                    "sourceContentId": 1,
                },
                {
                    "source": "place",
                    "id": 85,
                    "name": "북촌 한옥마을",
                    "lat": 37.581,
                    "lng": 126.983,
                    "dwellMinutes": 60,
                },
                {
                    "source": "poi",
                    "id": 100,
                    "name": "쉬어 가는 정원",
                    "lat": 37.582,
                    "lng": 126.983,
                    "dwellMinutes": 30,
                },
            ],
            "photos": [
                {
                    "url": "https://tong.visitkorea.or.kr/cms/resource/1.jpg",
                    "caption": "경복궁",
                    "credit": "사진: 한국관광공사 · 공공누리 제1유형",
                    "lat": 37.58,
                    "lng": 126.977,
                    "sourceId": "tour-126508",
                }
            ],
        }
    ]


class GuideRulesTests(unittest.TestCase):
    def test_actual_twenty_guides_keep_regional_course_and_photo_rules(self):
        root = Path(__file__).resolve().parents[2]
        data = json.loads(
            (
                root / "docs/product/community-guides/regional-guides-2026-10.json"
            ).read_text()
        )
        self.assertEqual(len(validate_guides(data)), 20)
        self.assertGreaterEqual(sum(len(guide["stops"]) for guide in data), 60)
        self.assertGreaterEqual(sum(len(guide["photos"]) for guide in data), 20)

    def test_same_region_is_accepted_and_input_is_not_changed(self):
        data = sample()
        before = copy.deepcopy(data)
        self.assertEqual(len(validate_guides(data)), 1)
        self.assertEqual(data, before)

    def test_seoul_jeju_mix_is_rejected(self):
        data = sample()
        data[0]["stops"][1].update(lat=33.46, lng=126.93)
        with self.assertRaises(ValueError):
            validate_guides(data)

    def test_wrong_photo_region_or_license_is_rejected(self):
        for changed in (
            {"lat": 33.46},
            {"credit": "사진: 한국관광공사 · 공공누리 제3유형"},
        ):
            data = sample()
            data[0]["photos"][0].update(changed)
            with self.assertRaises(ValueError):
                validate_guides(data)

    def test_invalid_text_duplicate_and_photo_source_are_rejected(self):
        for mutation in (
            lambda g: g.update(title=" " * 3),
            lambda g: g.update(body="x" * 5001),
            lambda g: g.update(sources=[]),
            lambda g: g["stops"].append(copy.deepcopy(g["stops"][0])),
            lambda g: g["photos"][0].update(url="https://example.com/stolen.jpg"),
            lambda g: g["stops"][0].update(dwellMinutes=0),
        ):
            data = sample()
            mutation(data[0])
            with self.assertRaises(ValueError):
                validate_guides(data)
        data = sample()
        data += copy.deepcopy(data)
        with self.assertRaises(ValueError):
            validate_guides(data)

    def test_only_local_api_and_storage_urls_are_accepted(self):
        self.assertEqual(
            local_url("http://localhost:8081/v1/posts"),
            "http://localhost:8081/v1/posts",
        )
        self.assertEqual(
            local_url("http://localhost:9000/media/a?signature=x"),
            "http://localhost:9000/media/a?signature=x",
        )
        for url in (
            "https://prod.example.com/v1/posts",
            "http://localhost:8082/v1/posts",
            "http://user:secret@localhost:8081/v1/posts",
            "http://localhost:8081.evil.test/v1/posts",
        ):
            with self.assertRaises(ValueError):
                local_url(url)

    def test_place_and_poi_with_same_number_keep_distinct_sources(self):
        guide = sample()[0]
        guide["stops"][2]["id"] = 90
        items = course_items(guide)
        self.assertEqual(
            items[0], {"placeId": 90, "dwellMinutes": 90, "sourceContentId": 1}
        )
        self.assertEqual(items[2], {"poiId": 90, "dwellMinutes": 30})


class PersistenceTests(unittest.TestCase):
    def test_state_round_trip_and_other_dataset_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            state = load_state(path, "digest-one")
            state["entries"]["kept"] = {"postId": 7}
            store_state(path, state)
            self.assertEqual(
                load_state(path, "digest-one")["entries"]["kept"]["postId"], 7
            )
            original = path.read_bytes()
            with self.assertRaises(ValueError):
                load_state(path, "digest-two")
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_photo_filenames_and_typed_course_are_needed_to_recover(self):
        guide = sample()[0]
        entry = {
            "photoKeys": ["uploads/tmp/unique.jpg"],
            "expectedStops": [["place", 90, 90], ["poi", 90, 30]],
        }
        detail = {
            "title": guide["title"],
            "body": guide["body"],
            "photoUrls": ["http://localhost:9000/media/posts/unique.jpg?signature=a"],
            "course": {
                "title": guide["courseTitle"],
                "days": [
                    {
                        "dayNumber": 1,
                        "stops": [
                            {"source": "place", "placeId": 90, "dwellMinutes": 90},
                            {"source": "poi", "poiId": 90, "dwellMinutes": 30},
                        ],
                    }
                ],
            },
        }
        self.assertTrue(matches_post(detail, guide, entry))
        wrong = copy.deepcopy(detail)
        wrong["photoUrls"] = ["http://localhost:9000/media/posts/other.jpg"]
        self.assertFalse(matches_post(wrong, guide, entry))
        wrong = copy.deepcopy(detail)
        wrong["course"]["days"].append(copy.deepcopy(wrong["course"]["days"][0]))
        self.assertFalse(matches_post(wrong, guide, entry))
        wrong = copy.deepcopy(detail)
        wrong["course"]["days"][0]["stops"][1] = {"source": "place", "placeId": 90}
        self.assertFalse(matches_post(wrong, guide, entry))

    def test_lost_post_is_checked_without_reposting(self):
        class MissingAPI:
            def __init__(self):
                self.calls = []

            def call(self, method, path):
                self.calls.append((method, path))
                return {"items": []}

        api = MissingAPI()
        entry = {"postPending": True, "photoKeys": ["uploads/tmp/unique.jpg"]}
        with self.assertRaises(RuntimeError):
            publish(api, sample()[0], entry, lambda: None)
        self.assertEqual(api.calls, [("GET", "/me/posts?limit=100")])
        self.assertTrue(entry["postPending"])

    def test_confirmed_rejection_and_uncertain_failure_have_different_recovery(self):
        class FailedAPI:
            def __init__(self, status):
                self.status = status

            def call(self, method, path, body):
                raise APIError(self.status)

        for status in (400, 500):
            entry = {
                "courseId": 7,
                "photoKeys": ["uploads/tmp/photo.jpg"],
                "photoHashes": ["hash"],
            }
            with self.assertRaises(APIError):
                publish(FailedAPI(status), sample()[0], entry, lambda: None)
            self.assertEqual(entry["postPending"], status == 500)
            if status == 400:
                self.assertEqual(entry["photoKeys"], [])
                self.assertEqual(entry["rejectedPhotoKeys"], ["uploads/tmp/photo.jpg"])

    def test_token_is_generated_only_from_environment_and_has_short_expiry(self):
        generated = base64.b64encode(bytes(range(32))).decode()
        with patch.dict("os.environ", {"SCENETRIP_AUTH_JWT_SECRET": generated}):
            token = access_token("00000000-0000-0000-0000-000000000001")
        payload = token.split(".")[1]
        values = json.loads(
            base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        )
        self.assertEqual(values["iss"], "scene-api")
        self.assertEqual(values["exp"] - values["iat"], 3600)
        self.assertNotEqual(values["jti"], values["sub"])

    def test_remote_redirect_is_rejected_before_credential_forwarding(self):
        with self.assertRaises(ValueError):
            LocalRedirect().redirect_request(
                None, None, 302, "redirect", {}, "https://external.example/v1/posts"
            )

    def test_oversize_photo_uses_providers_smaller_file_without_editing_bytes(self):
        photo = sample()[0]["photos"][0]
        photo["url"] = "https://tong.visitkorea.or.kr/cms/resource/1_image2_1.jpg"
        supplied = b"\xff\xd8\xff" + bytes(range(128)) + b"\xff\xd9"
        with tempfile.TemporaryDirectory() as folder:
            with patch(
                "community_guides_transport.download_photo",
                side_effect=[None, supplied],
            ) as download:
                path, kind = photo_file(photo, Path(folder))
            self.assertEqual(kind, "image/jpeg")
            self.assertEqual(path.read_bytes(), supplied)
            self.assertEqual(
                download.call_args_list[1].args[0],
                photo["url"].replace("_image2_1", "_image3_1"),
            )
            with patch("community_guides_transport.download_photo") as download:
                self.assertEqual(
                    photo_file(photo, Path(folder))[0].read_bytes(), supplied
                )
            download.assert_not_called()

    def test_cached_oversize_photo_is_rejected(self):
        photo = sample()[0]["photos"][0]
        with tempfile.TemporaryDirectory() as folder:
            with patch(
                "community_guides_transport.download_photo",
                return_value=b"\xff\xd8\xffok\xff\xd9",
            ):
                path, _ = photo_file(photo, Path(folder))
            path.write_bytes(b"\xff\xd8\xff" + b"x" * MAX_PHOTO_BYTES)
            with self.assertRaises(ValueError):
                photo_file(photo, Path(folder))

    def test_large_content_length_is_checked_before_reading_photo_body(self):
        from unittest.mock import MagicMock

        response = MagicMock()
        response.__enter__.return_value = response
        response.geturl.return_value = sample()[0]["photos"][0]["url"]
        response.headers = {"Content-Length": str(MAX_PHOTO_BYTES + 1)}
        with patch("urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = response
            self.assertIsNone(download_photo(response.geturl.return_value))
        response.read.assert_not_called()

    def test_truncated_cached_photo_and_changed_cached_bytes_are_rejected(self):
        photo = sample()[0]["photos"][0]
        with tempfile.TemporaryDirectory() as folder:
            with patch(
                "community_guides_transport.download_photo",
                return_value=b"\xff\xd8\xffvalid\xff\xd9",
            ):
                path, _ = photo_file(photo, Path(folder))
            for changed in (b"\xff\xd8\xff", b"\xff\xd8\xffdifferent\xff\xd9"):
                path.write_bytes(changed)
                with self.assertRaises(ValueError):
                    photo_file(photo, Path(folder))

    def test_photo_redirect_is_rejected_before_external_or_internal_get(self):
        for destination in (
            "https://external.example/photo.jpg",
            "http://localhost:9000/media/private",
            "http://tong.visitkorea.or.kr/photo.jpg",
        ):
            with self.assertRaises(ValueError):
                PhotoRedirect().redirect_request(
                    None, None, 302, "redirect", {}, destination
                )

    def test_course_response_cannot_replace_requested_identity_time_or_day(self):
        guide = sample()[0]
        expected = [["place", 90, 90], ["poi", 90, 30]]
        detail = {
            "title": guide["courseTitle"],
            "days": [
                {
                    "dayNumber": 1,
                    "items": [
                        {"source": "place", "placeId": 90, "dwellMinutes": 90},
                        {"source": "poi", "poiId": 90, "dwellMinutes": 30},
                    ],
                }
            ],
        }
        self.assertTrue(matches_course(detail, guide, expected, "items"))
        for change in (
            lambda d: d["days"][0].update(dayNumber=2),
            lambda d: d["days"][0]["items"][0].update(placeId=91),
            lambda d: d["days"][0]["items"][0].update(dwellMinutes=89),
            lambda d: d["days"][0]["items"].reverse(),
            lambda d: d.update(days=[]),
        ):
            wrong = copy.deepcopy(detail)
            change(wrong)
            self.assertFalse(matches_course(wrong, guide, expected, "items"))

    def test_linked_poi_expected_place_is_resolved_before_course_write(self):
        stop = sample()[0]["stops"][2]

        class LinkedAPI:
            def call(self, method, path):
                return {
                    "name": stop["name"],
                    "latitude": stop["lat"],
                    "longitude": stop["lng"],
                    "placeId": 123,
                }

        self.assertEqual(check_target(LinkedAPI(), stop), ["place", 123, 30])


if __name__ == "__main__":
    unittest.main()
