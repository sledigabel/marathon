package mesosphere.marathon.state

import mesosphere.marathon.Protos.MarathonTask

class MarathonTaskConverter extends StateConverter[MarathonTask, MarathonTask] {

  override def fromProto(message: MarathonTask): MarathonTask = message

  override def fromProto(bytes: Array[Byte]): MarathonTask = MarathonTask.parseFrom(bytes)

  override def toProto(state: MarathonTask): MarathonTask = state

}
