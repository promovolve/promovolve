package promovolve.serialization

import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko.serialization.SerializerWithStringManifest
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion }

/**
 * Pekko serializer for scalapb-generated protobuf messages — the wire format
 * for cluster TRANSPORT messages (Phase 1; persistence stays Jackson-CBOR).
 *
 * Why: schema-defined protos cannot silently deserialize to null the way
 * reflective Jackson can (bare specialized tuples deserialized to NULL and
 * killed Artery's inbound stream — the root cause of the 2026-06/07
 * multi-node self-down cascades, see LPWorkerSerializationSpec). With
 * protobuf, an unknown/malformed payload throws — a loud, contained failure.
 *
 * Wiring (application.conf):
 *   serializers { proto-scalapb = "promovolve.serialization.ScalapbSerializer" }
 *   serialization-bindings { "scalapb.GeneratedMessage" = proto-scalapb }
 *
 * The manifest is the generated message's companion class name; the
 * companion is resolved reflectively once per type and cached. Renaming a
 * generated message type is therefore a wire-breaking change across a
 * rolling deploy — add new messages instead of renaming (standard proto
 * discipline).
 */
final class ScalapbSerializer extends SerializerWithStringManifest {

  // Distinct from Pekko's built-ins and jackson-cbor's id space.
  override val identifier: Int = 4001

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case m: GeneratedMessage => m.toByteArray
    case other               =>
      throw new IllegalArgumentException(
        s"ScalapbSerializer can only serialize scalapb GeneratedMessage, got ${other.getClass.getName}"
      )
  }

  private val companions = new ConcurrentHashMap[String, GeneratedMessageCompanion[? <: GeneratedMessage]]()

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val companion = companions.computeIfAbsent(
      manifest,
      m => {
        val clazz = Class.forName(m + "$")
        clazz.getField("MODULE$").get(null).asInstanceOf[GeneratedMessageCompanion[? <: GeneratedMessage]]
      }
    )
    val parsed = companion.parseFrom(bytes)
    // Defense in depth: a null message crashes Artery's whole inbound stream
    // (heartbeats included). Never let one out of a deserializer again.
    if (parsed == null) throw new IllegalStateException(s"protobuf parse returned null for manifest $manifest")
    // GeneratedMessage is a universal trait (extends Any); the concrete
    // generated messages are case classes, so this cast is always sound.
    parsed.asInstanceOf[AnyRef]
  }
}
