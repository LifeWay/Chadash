package actors.workflow

import actors.workflow.AutoScalingGroup.{ASGCreated, CreateASG}
import akka.actor.{Actor, ActorLogging}

import scala.concurrent.duration._

class AutoScalingGroup extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: CreateASG => {
      log.debug("Attempting to create autoscaling group....")
      throw new UnsupportedOperationException("boom goes java")
      //context.parent ! ASGCreated(x.appVersion)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateASG => context.system.scheduler.scheduleOnce(10 seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object AutoScalingGroup {

  case class CreateASG(appVersion: String)

  case class ASGCreated(appVersion: String)

}
