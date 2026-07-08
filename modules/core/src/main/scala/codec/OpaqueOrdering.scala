package codec

object OpaqueOrdering {

  given derived[T, U](using codec: OpaqueCodec[T, U], ord: Ordering[U]): Ordering[T] =
    ord.asInstanceOf[Ordering[T]]
}
