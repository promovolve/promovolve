package codec

trait StringEncoder[T] {
  def encode(t: T): String
}

object StringEncoder {

  given StringEncoder[String] with {
    def encode(t: String): String = t
  }

  private def fromConversion[T](to: T => String): StringEncoder[T] =
    (t: T) => to(t)

  inline given derived[T](using inline ev: T <:< String): StringEncoder[T] =
    fromConversion(ev(_))

  given fromCodec[T](using codec: OpaqueCodec[T, String]): StringEncoder[T] =
    (t: T) => codec.encode(t)
}
