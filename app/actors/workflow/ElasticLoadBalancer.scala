package actors.workflow

import actors.workflow.ElasticLoadBalancer.{CreateELB, ELBCreated}
import akka.actor.{Actor, ActorLogging}

import scala.concurrent.duration._

class ElasticLoadBalancer extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: CreateELB => {
      log.debug("Attempting to create ELB....")
      context.parent ! ELBCreated(x.appVersion)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateELB => context.system.scheduler.scheduleOnce(10 seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object ElasticLoadBalancer {

  case class CreateELB(appVersion: String)

  case class ELBCreated(appVersion: String)

}

