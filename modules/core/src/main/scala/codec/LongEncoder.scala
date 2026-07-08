package codec

trait LongEncoder[T] {
  def encode(t: T): Long
}

object LongEncoder {

  given LongEncoder[Long] with {
    def encode(t: Long): Long = t
  }

  private def fromConversion[T](to: T => Long): LongEncoder[T] =
    (t: T) => to(t)

  inline given derived[T](using inline ev: T <:< Long): LongEncoder[T] =
    fromConversion(ev(_))

  given fromCodec[T](using codec: OpaqueCodec[T, Long]): LongEncoder[T] =
    (t: T) => codec.encode(t)
}
