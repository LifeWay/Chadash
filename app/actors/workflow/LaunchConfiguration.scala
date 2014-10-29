package actors.workflow

import actors.workflow.LaunchConfiguration.{CreateLaunchConfig, LaunchConfigCreated}
import akka.actor.{Actor, ActorLogging}

import scala.concurrent.duration._

class LaunchConfiguration extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: CreateLaunchConfig => {
      log.debug("Attempting to create launch config....")
      context.parent ! LaunchConfigCreated(x.appVersion)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateLaunchConfig => context.system.scheduler.scheduleOnce(10 seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object LaunchConfiguration {

  case class CreateLaunchConfig(appVersion: String)

  case class LaunchConfigCreated(appVersion: String)

}
