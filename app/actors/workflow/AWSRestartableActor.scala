package actors.workflow

import akka.actor.ActorLogging

import scala.concurrent.duration._

/**
 * This trait should only be mixed in on actors where the only type of exceptions that are typically thrown are
 * AWS Exceptions. This trait is used in combination with the ASWSupervisorStrategy. The supervisor strategy will
 * restart actors that throw an AmazonClientException - that exception type typically occurs when something happened
 * with the connection to the AWS API. By mixing in that strategy on your supervisors and the AWSRestartableActor
 * trait, it allows akka to re-play any messages that were lost in attempting communication with AWS.
 *
 * You can try out the power of what this enables you to do by starting a deployment and pulling your network cable.
 * Re-insert your cable after a minute or two and you'll see the deployment pick up right were it left off without
 * skipping a beat.
 *
 */
trait AWSRestartableActor extends ActorLogging {
  this: akka.actor.Actor =>

  override def postRestart(reason: Throwable): Unit = {
    this.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message match {
      case Some(msg) =>
        log.info(s"Restarting Actor and replaying with a msg type of: ${msg.getClass.toString}")
        context.system.scheduler.scheduleOnce(15.seconds, self, msg)
      case None => log.warning("Actor restarting, but no message to replay")
    }
  }
}
