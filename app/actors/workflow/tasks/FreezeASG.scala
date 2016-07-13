package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest
import utils.{AmazonAutoScalingService, PropFactory}

import scala.collection.JavaConverters._

class FreezeASG(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonAutoScalingService {

  import actors.workflow.tasks.FreezeASG._

  override def receive: Receive = {
    case query: FreezeASGCommand =>

      val suspendProcessesRequest = new SuspendProcessesRequest()
                                    .withAutoScalingGroupName(query.asgName)
                                    .withScalingProcesses(Seq("AlarmNotification", "ScheduledActions").asJava)

      val awsClient = autoScalingClient(credentials)
      awsClient.suspendProcesses(suspendProcessesRequest)
      context.parent ! FreezeASGCompleted(query.asgName)
  }
}

object FreezeASG extends PropFactory {
  case class FreezeASGCommand(asgName: String)
  case class FreezeASGCompleted(asgName: String)

  override def props(args: Any*): Props = Props(classOf[FreezeASG], args: _*)
}

