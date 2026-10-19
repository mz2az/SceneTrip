      WITH origin AS (
          SELECT CASE
                     WHEN CAST($2 AS DOUBLE PRECISION) IS NULL THEN NULL
                     ELSE ST_SetSRID(
                              ST_MakePoint(CAST($3 AS DOUBLE PRECISION),
                                           CAST($2 AS DOUBLE PRECISION)),
                              4326)::geography
                 END AS point
      )
                SELECT
              p.id, p.category, p.category_group,
                        p.name, p.address,
              CASE WHEN $1 = 'ko' THEN NULL
                   ELSE COALESCE(tr.name, te.name) END AS display_name,
              CASE WHEN $1 = 'ko' THEN NULL
                   ELSE COALESCE(tr.address, te.address) END AS display_address,
              CASE WHEN $1 = 'ko' THEN p.category
                   ELSE COALESCE(cr.name, ce.name, p.category) END AS category_label,
              CASE WHEN $1 = 'ko' THEN 'ko'
                   WHEN cr.name IS NOT NULL THEN $1
                   WHEN ce.name IS NOT NULL THEN 'en'
                   ELSE 'ko' END AS label_lang,
              p.name_roman,
              lk.place_id AS linked_place_id,
                    -- geography 에서 좌표를 꺼내려면 geometry 로 캐스팅한다. ST_X 가 경도, ST_Y 가 위도다.
              ST_Y(p.geom::geometry) AS latitude,
              ST_X(p.geom::geometry) AS longitude,
              CASE WHEN o.point IS NULL THEN NULL
                   ELSE round(ST_Distance(p.geom, o.point))::INT END AS distance_meters
          FROM poi p
          CROSS JOIN origin o
                    LEFT JOIN poi_i18n tr ON tr.poi_id = p.id AND tr.lang = $1
          LEFT JOIN poi_i18n te ON te.poi_id = p.id AND te.lang = 'en'
          LEFT JOIN poi_category_i18n cr ON cr.ko = p.category AND cr.lang = $1
          LEFT JOIN poi_category_i18n ce ON ce.ko = p.category AND ce.lang = 'en'
          -- 같은 곳인 촬영지(V25, MZ2AZ-371). 숨긴 촬영지의 연결은 쓰지 않는다 — 지도에 없으니 편의시설이 스스로 보여야 한다.
          LEFT JOIN (SELECT l.poi_id, l.place_id
                     FROM place_poi_link l
                     JOIN place lp ON lp.id = l.place_id AND lp.hidden_at IS NULL) lk ON lk.poi_id = p.id
            WHERE p.geom::geometry && ST_MakeEnvelope(CAST($6 AS DOUBLE PRECISION), CAST($7 AS DOUBLE PRECISION),
                                                CAST($8 AS DOUBLE PRECISION), CAST($9 AS DOUBLE PRECISION), 4326)
        AND (CAST($5 AS TEXT) IS NULL OR p.category_group = CAST($5 AS TEXT))
        AND p.closed_at IS NULL
                ORDER BY p.geom::geometry <-> ST_SetSRID(ST_MakePoint(CAST($3 AS DOUBLE PRECISION), CAST($2 AS DOUBLE PRECISION)), 4326), p.id
          LIMIT $10 OFFSET $11
          