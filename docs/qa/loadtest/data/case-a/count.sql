      WITH origin AS (
          SELECT CASE
                     WHEN CAST($2 AS DOUBLE PRECISION) IS NULL THEN NULL
                     ELSE ST_SetSRID(
                              ST_MakePoint(CAST($3 AS DOUBLE PRECISION),
                                           CAST($2 AS DOUBLE PRECISION)),
                              4326)::geography
                 END AS point
      )
      SELECT count(*) FROM poi p CROSS JOIN origin o
      WHERE
        -- 뷰포트는 평면(geometry)으로 본다. 구면으로 만들면 경도 -180~180 사각형에서 180 도 간선의
        -- 방향을 정할 수 없어 질의가 실패한다(place 의 V6 주석). V13 의 표현식 인덱스가 이 && 를 받는다.
        (CAST($6 AS DOUBLE PRECISION) IS NULL
         OR p.geom::geometry && ST_MakeEnvelope(
                CAST($6 AS DOUBLE PRECISION),
                CAST($7 AS DOUBLE PRECISION),
                CAST($8 AS DOUBLE PRECISION),
                CAST($9 AS DOUBLE PRECISION),
                4326))
        -- 반경은 구면(geography)이다 — "몇 미터 안" 은 구면 거리가 맞다. ST_DWithin 이 V12 의 GiST 를 탄다.
        AND (CAST($4 AS INTEGER) IS NULL
             OR o.point IS NULL
             OR ST_DWithin(p.geom, o.point, CAST($4 AS INTEGER)))
        AND (CAST($5 AS TEXT) IS NULL OR p.category_group = CAST($5 AS TEXT))
        -- 폐업 표시된 POI 는 어디에도 내보내지 않는다(V18). 위의 위치·갈래 인덱스가 먼저 좁히고 여기서 거른다.
        AND p.closed_at IS NULL
      