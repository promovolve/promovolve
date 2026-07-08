package codec

object OpaqueNumeric {

  given derived[T, U](using codec: OpaqueCodec[T, U], num: Numeric[U]): Numeric[T] =
    num.asInstanceOf[Numeric[T]]

  extension [T](x: T)(using num: Numeric[T]) {
    def +(y: T): T = num.plus(x, y)
    def -(y: T): T = num.minus(x, y)
    def *(y: T): T = num.times(x, y)
    def unary_- : T = num.negate(x)
  }
}
