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
      WHERE p.geom::geometry && ST_MakeEnvelope(CAST($6 AS DOUBLE PRECISION), CAST($7 AS DOUBLE PRECISION),
                                                CAST($8 AS DOUBLE PRECISION), CAST($9 AS DOUBLE PRECISION), 4326)
        AND (CAST($5 AS TEXT) IS NULL OR p.category_group = CAST($5 AS TEXT))
        AND p.closed_at IS NULL
      