package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest

import scala.collection.JavaConverters._

class FreezeASG(credentials: AWSCredentials) extends Actor with AWSRestartableActor with ActorLogging {

  import actors.workflow.tasks.FreezeASG._

  override def receive: Receive = {
    case query: FreezeASGCommand =>

      val suspendProcessesRequest = new SuspendProcessesRequest()
        .withAutoScalingGroupName(query.asgName)
        .withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.suspendProcesses(suspendProcessesRequest)
      context.sender() ! FreezeASGCompleted(query.asgName)
  }
}

object FreezeASG {

  case class FreezeASGCommand(asgName: String)

  case class FreezeASGCompleted(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new FreezeASG(credentials))
}

