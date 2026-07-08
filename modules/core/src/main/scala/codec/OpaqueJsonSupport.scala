package codec

import spray.json.*

object OpaqueJsonSupport extends LowPriorityOpaqueJsonSupport {

  // --- Higher priority: OpaqueCodec-based (=:= types) ---

  given opaqueStringJsonFormat[T](using codec: OpaqueCodec[T, String]): JsonFormat[T] with {
    def write(t: T) = JsString(codec.encode(t))
    def read(json: JsValue): T = json match {
      case JsString(s) => codec.decode(s)
      case other => deserializationError(s"Expected JSON string, got $other")
    }
  }

  given opaqueLongJsonFormat[T](using codec: OpaqueCodec[T, Long]): JsonFormat[T] with {
    def write(t: T) = JsNumber(codec.encode(t))
    def read(json: JsValue): T = json match {
      case JsNumber(n) => codec.decode(n.toLongExact)
      case other => deserializationError(s"Expected JSON number, got $other")
    }
  }

  given opaqueBigDecimalJsonFormat[T](using codec: OpaqueCodec[T, BigDecimal]): JsonFormat[T] with {
    def write(t: T) = JsNumber(codec.encode(t))
    def read(json: JsValue): T = json match {
      case JsNumber(n) => codec.decode(n)
      case other => deserializationError(s"Expected JSON number, got $other")
    }
  }
}

trait LowPriorityOpaqueJsonSupport {

  // --- Lower priority: Encoder + Decoder pair (<:< types with validation) ---

  given validatedStringJsonFormat[T](using encoder: StringEncoder[T], decoder: StringDecoder[T]): JsonFormat[T] with {
    def write(t: T) = JsString(encoder.encode(t))
    def read(json: JsValue): T = json match {
      case JsString(s) =>
        decoder.decode(s) match {
          case Right(t) => t
          case Left(err) => deserializationError(err)
        }
      case other =>
        deserializationError(s"Expected JSON string, got $other")
    }
  }

  given validatedLongJsonFormat[T](using encoder: LongEncoder[T], decoder: LongDecoder[T]): JsonFormat[T] with {
    def write(t: T) = JsNumber(encoder.encode(t))
    def read(json: JsValue): T = json match {
      case JsNumber(n) =>
        decoder.decode(n.toLongExact) match {
          case Right(t) => t
          case Left(err) => deserializationError(err)
        }
      case other =>
        deserializationError(s"Expected JSON number, got $other")
    }
  }
}
