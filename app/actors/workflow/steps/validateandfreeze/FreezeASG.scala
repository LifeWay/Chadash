package actors.workflow.steps.validateandfreeze


import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest

import scala.concurrent.duration._
import scala.collection.JavaConverters._

class FreezeASG(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.validateandfreeze.FreezeASG._

  override def receive: Receive = {
    case query: FreezeASGWithName =>

      val suspendProcessesRequest = new SuspendProcessesRequest()
        .withAutoScalingGroupName(query.asgName)
        .withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.suspendProcesses(suspendProcessesRequest)
      context.parent ! ASGFrozen(query.asgName)
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: FreezeASGWithName => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object FreezeASG {

  case class FreezeASGWithName(asgName: String)

  case class ASGFrozen(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new FreezeASG(credentials))
}

