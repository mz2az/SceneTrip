-- 촬영지와 같은 곳인 편의시설 — 그곳은 촬영지가 대표한다.
--
-- 계획: docs/project/plans/place-poi-link.md (MZ2AZ-371). 계약: scene-api 1.7.0 PoiSummary.placeId.
--
-- 이 표를 채우는 것은 마이그레이션이 아니라 적재(seed/place_poi_link.sql, `just place-poi-link`)다 — 촬영지와
-- 편의시설이 들어온 뒤에야 같은 곳을 견줄 수 있고, 둘 다 다시 적재될 때마다 다시 맞춘다.
--
-- 편의시설 하나는 촬영지 하나에만 붙는다(기본 키). 촬영지 하나에는 여럿이 붙을 수 있다 — 「대학로 마로니에공원」 에
-- 「마로니에공원」 과 「대학로」.

CREATE TABLE place_poi_link (
    poi_id    BIGINT PRIMARY KEY REFERENCES poi (id) ON DELETE CASCADE,
    place_id  BIGINT NOT NULL REFERENCES place (id) ON DELETE CASCADE,
    -- address: 도로명 주소가 같아 자동 · near: 주소를 견줄 수 없고 5 m 안이라 자동 · manual: 사람이 판정
    method    TEXT NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT place_poi_link_method_ck CHECK (method IN ('address', 'near', 'manual'))
);

CREATE INDEX place_poi_link_place_idx ON place_poi_link (place_id);

COMMENT ON TABLE place_poi_link IS
    '촬영지와 같은 곳인 편의시설. 숨긴 촬영지(hidden_at)의 연결은 쓰지 않는다 — 그 촬영지는 지도에 없으므로 편의시설이 스스로 보여야 한다';
COMMENT ON COLUMN place_poi_link.method IS
    'address(도로명 주소가 같음) · near(주소를 견줄 수 없고 5 m 안) · manual(판정 파일 seed/place_poi_links.tsv)';
