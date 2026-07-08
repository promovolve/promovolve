package codec

trait StringDecoder[T] {
  def decode(s: String): Either[String, T]
}

object StringDecoder {

  given StringDecoder[String] with {
    def decode(s: String): Either[String, String] = Right(s)
  }

  private def fromConversion[T](from: String => T): StringDecoder[T] =
    (s: String) => Right(from(s))

  inline given derived[T](using inline ev: T =:= String): StringDecoder[T] =
    fromConversion(ev.flip(_))

  given fromCodec[T](using codec: OpaqueCodec[T, String]): StringDecoder[T] =
    (s: String) => Right(codec.decode(s))
}
