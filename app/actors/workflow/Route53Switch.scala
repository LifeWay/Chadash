package actors.workflow

import actors.workflow.Route53Switch.{RequestRoute53Switch, Route53SwitchCompleted}
import akka.actor.{Actor, ActorLogging}

import scala.concurrent.duration._

class Route53Switch extends Actor with ActorLogging {

  override def receive: Receive = {
    case x: RequestRoute53Switch => {
      log.debug("Attempting to create ELB....")
      context.parent ! Route53SwitchCompleted(x.appVersion)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: RequestRoute53Switch => context.system.scheduler.scheduleOnce(10 seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object Route53Switch {

  case class RequestRoute53Switch(appVersion: String)

  case class Route53SwitchCompleted(appVersion: String)

}