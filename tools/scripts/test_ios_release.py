"""실제 계정 없이 배포 전 거부 조건을 검증한다."""

import datetime
import unittest

from ios_release import validate_profile


class ReleaseTests(unittest.TestCase):
    def profile(self):
        return {
            "ExpirationDate": datetime.datetime(2030, 1, 1, tzinfo=datetime.UTC),
            "TeamIdentifier": ["TEAM123456"],
            "ApplicationIdentifierPrefix": ["PREFIX1234"],
            "DeveloperCertificates": [b"test-certificate"],
            "Entitlements": {
                "application-identifier": "PREFIX1234.com.mz2az.scenetrip",
                "com.apple.developer.team-identifier": "TEAM123456",
                "com.apple.developer.applesignin": ["Default"],
                "keychain-access-groups": ["PREFIX1234.*"],
                "get-task-allow": False,
                "beta-reports-active": True,
            },
        }

    def check(self, p, mode="app-store", device=None):
        return validate_profile(
            p,
            "TEAM123456",
            mode,
            device,
            datetime.datetime(2026, 10, 14, tzinfo=datetime.UTC),
        )

    def test_store(self):
        self.assertEqual(self.check(self.profile()), [])

    def test_wrong_app_team_and_expired(self):
        for field, value in [
            ("ExpirationDate", datetime.datetime(2020, 1, 1, tzinfo=datetime.UTC)),
            ("TeamIdentifier", ["WRONG"]),
        ]:
            with self.subTest(field=field):
                p = self.profile()
                p[field] = value
                self.assertTrue(self.check(p))
        p = self.profile()
        p["Entitlements"]["application-identifier"] = "PREFIX1234.*"
        self.assertTrue(self.check(p))

    def test_missing_capabilities(self):
        for field in [
            "com.apple.developer.applesignin",
            "keychain-access-groups",
            "com.apple.developer.team-identifier",
            "beta-reports-active",
        ]:
            with self.subTest(field=field):
                p = self.profile()
                del p["Entitlements"][field]
                self.assertTrue(self.check(p))

    def test_wrong_distribution(self):
        for field, value in [
            ("ProvisionedDevices", ["device"]),
            ("ProvisionsAllDevices", True),
        ]:
            p = self.profile()
            p[field] = value
            self.assertTrue(self.check(p))
        p = self.profile()
        p["Entitlements"]["get-task-allow"] = True
        self.assertTrue(self.check(p))

    def test_registered_ipad(self):
        p = self.profile()
        p["ProvisionedDevices"] = ["ipad-udid"]
        self.assertEqual(self.check(p, "ad-hoc", "ipad-udid"), [])
        self.assertTrue(self.check(p, "ad-hoc", "other-udid"))
        self.assertTrue(self.check(p, "ad-hoc"))

    def test_missing_certificate_and_invalid_profile(self):
        p = self.profile()
        p["DeveloperCertificates"] = []
        self.assertTrue(self.check(p))
        self.assertTrue(self.check({}))


if __name__ == "__main__":
    unittest.main()
