package tasks

import actors.workflow.AWSSupervisorStrategy
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import utils.{ActorFactory, PropFactory}

object TaskProxyBuilder {
  def apply[T <: PropFactory](probe: TestProbe, ref: T, actorSystem: ActorSystem,
                              actorFactory: ActorFactory): ActorRef = {
    actorSystem.actorOf(Props(new Actor {
      val parent = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = actorFactory(ref, context, "", null)

        def receive = {
          case x if sender() == child => context.parent ! x
          case x => child forward x
        }
      }))

      def receive = {
        case x if sender() == parent => probe.ref forward x
        case x => parent forward x
      }
    }))
  }
}
