package utils

import actors.workflow.tasks.ASGInfo
import akka.actor._

trait ActorFactory {
  def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef
}

trait PropFactory {
  def props(args: Any*): Props
}

object ActorFactory extends ActorFactory {
  def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
    ref match {
      case ASGInfo => context.actorOf(ASGInfo.props(args), name)
      case x: Any => throw new UnsupportedOperationException("Unmatched type: " + x.getClass.toGenericString)
    }
  }
}
