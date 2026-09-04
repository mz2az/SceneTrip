import json
from pathlib import Path

from poi_pipeline import cli, load, store


def test_select_alive_filters_by_id(tmp_path: Path):
    raw = tmp_path / "raw.jsonl"
    store.write_rows(raw, [{"id": "1", "name": "a"}, {"id": "2", "name": "b"}])
    out = tmp_path / "out.jsonl"
    assert load.select_alive(raw, {"2"}, out) == 1
    assert [r["id"] for r in store.read_rows(out)] == ["2"]
    assert load.select_alive(raw, None, out) == 2  # None 이면 전부(명소·교통)


def test_run_seed_passes_files_to_script(tmp_path: Path):
    script = tmp_path / load.SEED_SCRIPT
    script.parent.mkdir(parents=True)
    script.write_text("#!/bin/sh\n")
    seen = {}

    class Result:
        returncode = 0

    def runner(cmd, cwd, check):
        seen["cmd"], seen["cwd"] = cmd, cwd
        return Result()

    assert load.run_seed([tmp_path / "a.jsonl"], tmp_path, runner=runner) == 0
    assert seen["cmd"][0] == str(script) and seen["cmd"][1].endswith("a.jsonl")


def test_cli_report_counts_raw_and_alive(tmp_path: Path, capsys):
    store.write_rows(tmp_path / "poi_food.jsonl", [{"id": "1"}, {"id": "2"}])
    store.write_rows(tmp_path / "poi_alive_food.jsonl", [{"id": "1"}])
    assert cli.main(["report", "--data", str(tmp_path)]) == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary["food"] == {"raw": 2, "alive": 1}
    assert summary["sight"] == {"raw": 0, "alive": None}


def test_cli_collect_refuses_without_key(tmp_path: Path, monkeypatch, capsys):
    monkeypatch.delenv("TMAP_APP_KEY", raising=False)
    code = cli.main(
        ["collect", "--group", "숙박", "--areas", "제주", "--data", str(tmp_path)]
    )
    assert code == 2
