package mesosphere.marathon.state

import com.google.protobuf.Message

/**
  * A Converter that takes care of converting Protobuf Messages into Marathon classes.
  *
  * Usually, there are specific scala classes representing state in Marathon, these could implement this trait directly.
  * There are cases, however, were a generated Protobuf message is stored. Since these do not provide conversion
  * functionality, a separate converter needs to be created by extending this trait.
  *
  * @tparam M The Protobuf Message
  * @tparam S The State representation
  */
trait StateConverter[M <: Message, S] {

  def fromProto(message: M): S

  def fromProto(bytes: Array[Byte]): S

  def toProto(state: S): M

  def toProtoByteArray(s: S): Array[Byte] = toProto(s).toByteArray

}
