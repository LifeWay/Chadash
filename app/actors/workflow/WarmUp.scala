package actors.workflow

import actors.workflow.WarmUp.{CheckWarmUp, WaitForWarmUp, WarmUpCompleted}
import akka.actor.{Actor, ActorLogging}

import scala.concurrent.duration._

class WarmUp extends Actor with ActorLogging {

  import context._

  var temporaryTestCounter = 0

  var appVersion: String = ""

  override def receive: Receive = {
    case x: WaitForWarmUp => {
      log.debug("waiting for warm-up...")
      appVersion = x.appVersion
      become(warming)

      self ! CheckWarmUp
    }
  }

  def warming: Receive = {
    case x: WaitForWarmUp => {
      log.debug("already waiting for the warm up to pass.... ignoring message")
    }
    case CheckWarmUp => {
      log.debug("checking ELB health...")
      //TODO: start ticking, checking every 15 seconds to see if instances are all healthy in the ELB.
      if (temporaryTestCounter < 2) {
        temporaryTestCounter = temporaryTestCounter + 1
        system.scheduler.scheduleOnce(15.seconds, self, CheckWarmUp)
      } else {
        log.debug("we have completed... notify upstream")
        parent ! WarmUpCompleted(appVersion)
        unbecome()
      }
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {

    message.get match {
      case x: WaitForWarmUp => context.system.scheduler.scheduleOnce(10.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object WarmUp {

  case class WaitForWarmUp(appVersion: String)

  case class WarmUpCompleted(appVersion: String)

  case object CheckWarmUp

}

