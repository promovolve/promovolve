package promovolve

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant

object SlickMappers {

  given BaseColumnType[Instant] =
    MappedColumnType.base[Instant, java.sql.Timestamp](
      instant => java.sql.Timestamp.from(instant),
      ts => ts.toInstant
    )
}
