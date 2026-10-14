"""Apple 프로파일의 공개 메타데이터만 검사한다. 키체인 비밀값은 읽지 않는다."""

import argparse
import datetime
import plistlib
import subprocess
import sys
from pathlib import Path

BUNDLE = "com.mz2az.scenetrip"


def validate_profile(profile, team, mode, device, now):
    """서명 전에 확인 가능한 조건. 개인 키 보유와 실제 서명 성공은 별도다."""
    errors = []
    ent = profile.get("Entitlements", {})
    prefixes = profile.get("ApplicationIdentifierPrefix", [])
    prefix = prefixes[0] if prefixes else ""
    expiry = profile.get("ExpirationDate")
    if isinstance(expiry, datetime.datetime) and expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=datetime.UTC)
    if not isinstance(expiry, datetime.datetime) or expiry <= now:
        errors.append("프로파일이 만료됐거나 만료일이 없습니다.")
    if (
        profile.get("TeamIdentifier") != [team]
        or ent.get("com.apple.developer.team-identifier") != team
    ):
        errors.append("요청한 개발자 팀과 프로파일이 다릅니다.")
    app = f"{prefix}.{BUNDLE}"
    if not prefix or ent.get("application-identifier") != app:
        errors.append("SceneTrip 전용 App ID 프로파일이 필요합니다.")
    if "Default" not in ent.get("com.apple.developer.applesignin", []):
        errors.append("Sign in with Apple 권한이 없습니다.")
    if not set(ent.get("keychain-access-groups", [])).intersection(
        {app, f"{prefix}.*"}
    ):
        errors.append("SceneTrip 키체인 그룹이 허용되지 않습니다.")
    if not profile.get("DeveloperCertificates"):
        errors.append("프로파일에 서명 인증서가 없습니다.")
    if ent.get("get-task-allow") is not False:
        errors.append("배포용 프로파일이 필요합니다(get-task-allow=false).")
    if profile.get("ProvisionsAllDevices"):
        errors.append("기업 내부 배포 프로파일은 지원하지 않습니다.")
    devices = profile.get("ProvisionedDevices", [])
    if mode == "app-store":
        if devices or ent.get("beta-reports-active") is not True:
            errors.append("App Store/TestFlight 배포 프로파일이 필요합니다.")
    elif mode == "ad-hoc":
        if not device or device not in devices:
            errors.append(
                "검증할 iPhone/iPad UDID가 등록된 Ad Hoc 프로파일이 필요합니다."
            )
    else:
        errors.append("지원하지 않는 배포 종류입니다.")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("mode", choices=["app-store", "ad-hoc"])
    parser.add_argument("--team", required=True)
    parser.add_argument("--device")
    args = parser.parse_args()
    if not args.profile.is_file():
        parser.error(
            "프로파일 파일이 없습니다. 태환님 개발자 팀에서 발급한 파일을 지정하세요."
        )
    result = subprocess.run(
        ["/usr/bin/security", "cms", "-D", "-i", str(args.profile.resolve())],
        capture_output=True,
        check=False,
    )
    if result.returncode:
        parser.error(
            "프로파일 CMS를 읽지 못했습니다. 키체인 암호 조회는 하지 않았습니다."
        )
    try:
        profile = plistlib.loads(result.stdout)
        if not isinstance(profile, dict):
            raise TypeError
        errors = validate_profile(
            profile,
            args.team,
            args.mode,
            args.device,
            datetime.datetime.now(datetime.UTC),
        )
    except (ValueError, TypeError, AttributeError, plistlib.InvalidFileException):
        parser.error("프로파일의 메타데이터 형식이 올바르지 않습니다.")
    if errors:
        for error in errors:
            print(f"미충족: {error}", file=sys.stderr)
        return 1
    print("프로파일 사전 검사 통과. 개인 키·실제 서명·설치·스토어 검증은 별도입니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
