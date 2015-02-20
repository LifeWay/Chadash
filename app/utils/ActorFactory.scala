package utils

import akka.actor._

trait PropFactory {
  def props(args: Any*): Props
}

trait ActorFactory {
  def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef
}

object ActorFactory extends ActorFactory {
  def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
    context.actorOf(ref.props(args), name)
  }
}
