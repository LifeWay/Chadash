package utils

import actors.workflow.tasks.ASGInfo
import akka.actor._
import com.amazonaws.auth.AWSCredentials

trait ActorFactory {
  def apply(clazz: AnyRef, context: ActorRefFactory, name: String, args: Any*): ActorRef
}

object ActorFactory extends ActorFactory {
  def apply(clazz: AnyRef, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
    clazz match {
      case ASGInfo => context.actorOf(ASGInfo.props(args(0).asInstanceOf[AWSCredentials]), name)
      case x: Any => throw new UnsupportedOperationException("Unmatched type: " + x.getClass.toGenericString)
    }
  }
}
