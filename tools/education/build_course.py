"""오프라인 HTML 과정과 출처 기반 SVG를 재현 가능하게 생성·검사한다."""

import argparse
import html
import os
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

from course_content import CHAPTERS
from local_content import LOCAL_CHAPTERS
from source_diagrams import diagram_outputs

HERE = Path(__file__).resolve().parent
SOURCE_LINK_SCRIPT = """if (location.protocol === 'http:' || location.protocol === 'https:') {
  document.querySelectorAll('a.source-file').forEach((link) => {
    const text = document.createElement('code'); text.textContent = link.textContent; link.replaceWith(text);
  });
}"""


def controls():
    return """<nav class="controls" aria-label="발표 탐색">
<button id="previous-slide" type="button" aria-label="이전 슬라이드">← 이전</button>
<button id="next-slide" type="button" aria-label="다음 슬라이드">다음 →</button>
<label for="slide-select">이동</label><select id="slide-select"></select><span id="slide-count"></span>
<progress id="slide-progress" aria-label="진행률"></progress>
<button id="toggle-overview" aria-controls="overview">목차</button>
<button id="toggle-notes" aria-pressed="false">강사 노트</button>
<button id="toggle-reading" aria-pressed="false">읽기</button>
<button id="toggle-fullscreen" aria-pressed="false">전체 화면</button>
<button id="print-slides">인쇄</button></nav>
<dialog id="overview" aria-labelledby="overview-title"><h2 id="overview-title">목차</h2>
<button id="close-overview">닫기</button><ol id="overview-list"></ol></dialog>"""


def slide_visual(chapter):
    if chapter["id"] == "environments":
        return re.search(r"<table>.*?</table>", chapter["body"], flags=re.DOTALL)[0]
    flows = {
        "request": [
            ("네이티브 앱", "ALB · nginx", "scene-api :8080"),
            ("scene-api", "trip-guide :8899", "DeepSeek"),
            ("trip-guide 조회", "내부 scene-api", "PostgreSQL"),
        ],
        "db-roles": [("bootstrap · 관리자", "migration · DDL", "runtime · DML")],
        "deploy": [("입력·plan 검토", "DB Job 성공", "Helm · smoke")],
        "oidc": [("GitHub 신원 토큰", "STS 임시 자격 증명", "환경별 AWS 역할")],
    }
    if chapter["id"] not in flows:
        return ""
    rows = [
        '<div class="flow">'
        + '<span class="arrow">→</span>'.join(
            f'<span class="node">{html.escape(label)}</span>' for label in row
        )
        + "</div>"
        for row in flows[chapter["id"]]
    ]
    return (
        '<div class="flow-figure" role="img" aria-label="처리 순서">'
        + "".join(rows)
        + "</div>"
    )


def section(chapter, index, presentation, detail_page="aws-eks-course.html"):
    anchor = html.escape(chapter["id"], quote=True)
    summary = "".join(f"<li>{html.escape(line)}</li>" for line in chapter["summary"])
    source = html.escape(chapter["source"], quote=True)
    if source.startswith("docs/"):
        source_link = f'<a href="../{source[5:]}">{source}</a>'
    else:
        source_link = f'<a class="source-file" href="../../{source}">{source}</a>'
    body = f'<ul class="summary">{summary}</ul>' if presentation else chapter["body"]
    visual = slide_visual(chapter) if presentation else ""
    detail = (
        f' · <a href="{detail_page}#{anchor}">이 장의 상세 설명·실습</a>'
        if presentation
        else ""
    )
    return f'''<section class="slide" id="{anchor}">
<div class="eyebrow">SCENETRIP / {index:02d}</div>
<h2>{html.escape(chapter["title"])}</h2>
{body}
{visual}
<aside class="notes"><b>강사 노트</b>{html.escape(chapter["notes"])}</aside>
<p class="source">구현 근거: {source_link}{detail}</p>
</section>'''


def render_page(title, chapters, presentation=False):
    css = (HERE / "course.css").read_text()
    javascript = (HERE / "navigation.js").read_text() if presentation else ""
    toc = "".join(
        f'<li><a href="#{item["id"]}">{html.escape(item["title"])}</a></li>'
        for item in chapters
    )
    detail_page = (
        "k8s-observability-course.html"
        if chapters is LOCAL_CHAPTERS
        else "aws-eks-course.html"
    )
    sections = "\n".join(
        section(item, index, presentation, detail_page)
        for index, item in enumerate(chapters, 1)
    )
    links = '<a href="aws-eks-course.html">AWS 상세 강의</a> · <a href="aws-eks-presentation.html">AWS 발표</a> · <a href="k8s-observability-course.html">로컬 상세 실습</a> · <a href="k8s-observability-class.html">로컬 발표</a>'
    return f'''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>{html.escape(title)}</title>
<style>{css}</style></head><body class="{"deck-body" if presentation else "course"}">
<a class="skip" href="#deck">본문으로 건너뛰기</a>
<header><div class="eyebrow">SCENETRIP ENGINEERING / 2026-09-20</div><h1>{html.escape(title)}</h1>
<p class="lede">{links}</p><p class="lede">오프라인 사용 가능. ← → / PageUp·PageDown / Space·Shift+Space / Home·End로 탐색합니다.
키보드 입력 중인 버튼·링크·폼에는 단축키를 적용하지 않습니다. 읽기 모드와 인쇄는 전체 내용을 보여줍니다.</p></header>
{"" if presentation else f'<nav class="toc" aria-label="강의 목차"><h2>과정 목차</h2><ol>{toc}</ol></nav>'}
<main id="deck">{sections}</main>
{controls() if presentation else ""}
<footer>{links}<p>구성도: <a href="diagrams/dev-overview.svg">DEV</a> · <a href="diagrams/prd-overview.svg">PRD</a> ·
<a href="diagrams/hcl-references.svg">HCL 참조</a> · <a href="diagrams/provenance.json">SHA256 출처</a></p>
<p>TripPilot PR #645의 교육 구성·탐색 방식을 SceneTrip 구현에 맞춰 이식. 실제 AWS 배포 결과가 아닙니다.</p></footer>
<script>{SOURCE_LINK_SCRIPT}</script>
{"<script>" + javascript + "</script>" if presentation else ""}
</body></html>\n'''


def write_outputs(root, outputs, check):
    differences = []
    for name, content in sorted(outputs.items()):
        path = root / name
        if check:
            if not path.is_file() or path.read_text() != content:
                differences.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    if differences:
        print("교육 산출물이 정본과 다릅니다: " + ", ".join(differences))
    return not differences


def validate_links(root, outputs):
    missing = []
    documents = {
        root / "docs/education" / name: text
        for name, text in outputs.items()
        if name.endswith(".html")
    }
    paths = list((root / "docs/education").rglob("*.md"))
    paths += [
        root / "docs" / name
        for name in (
            "architecture/aws-dev-prd.md",
            "architecture/aws-services.md",
            "ops/aws-deployment.md",
        )
    ]
    documents.update({path: path.read_text() for path in paths if path.exists()})
    generated = {root / "docs/education" / name for name in outputs}
    for path, text in documents.items():
        references = re.findall(r'href="([^"]+)"', text) + re.findall(
            r"\]\(([^)]+)\)", text
        )
        for reference in references:
            url = urlsplit(reference)
            if url.scheme or not url.path:
                continue
            target = Path(os.path.normpath(path.parent / unquote(url.path)))
            if target not in generated and not target.exists():
                missing.append(f"{path.relative_to(root)} → {reference}")
    if missing:
        raise ValueError("존재하지 않는 문서 링크: " + "; ".join(missing))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", ".")),
    )
    args = parser.parse_args()
    outputs = diagram_outputs(args.root)
    outputs.update(
        {
            "aws-eks-course.html": render_page("AWS DEV·PRD 상세 강의", CHAPTERS),
            "aws-eks-presentation.html": render_page(
                "AWS DEV·PRD 배포", CHAPTERS, True
            ),
            "k8s-observability-course.html": render_page(
                "로컬 Kubernetes와 관측성 상세 실습", LOCAL_CHAPTERS
            ),
            "k8s-observability-class.html": render_page(
                "로컬 Kubernetes와 관측성", LOCAL_CHAPTERS, True
            ),
        }
    )
    validate_links(args.root, outputs)
    if not write_outputs(args.root / "docs/education", outputs, args.check):
        return 1
    print("교육 자료 검사 통과" if args.check else "교육 자료 생성 완료")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
