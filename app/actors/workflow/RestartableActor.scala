package actors.workflow

import akka.actor.ActorLogging

import scala.concurrent.duration._

trait RestartableActor extends ActorLogging {
  this: akka.actor.Actor =>

  override def postRestart(reason: Throwable): Unit = {
    this.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message match {
      case Some(msg) =>
        log.info(s"Restarting Actor with a msg type of: ${msg.getClass.toString}")
        context.system.scheduler.scheduleOnce(15.seconds, self, msg)
      case None => log.warning("Actor restarting, but no message to replay")
    }
  }
}
