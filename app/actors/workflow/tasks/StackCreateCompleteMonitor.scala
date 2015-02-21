package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import play.api.Logger
import utils.{AmazonCloudFormationService, PropFactory}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class StackCreateCompleteMonitor(credentials: AWSCredentials, stackName: String) extends AWSRestartableActor
                                                                                         with AmazonCloudFormationService {

  import actors.workflow.tasks.StackCreateCompleteMonitor._

  override def preStart() = scheduleTick()

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  override def receive: Receive = {
    case Tick =>
      val stackFilter = new DescribeStacksRequest()
                        .withStackName(stackName)

      val awsClient = cloudFormationClient(credentials)
      val stackInfo = awsClient.describeStacks(stackFilter).getStacks.asScala.toSeq(0)
      stackInfo.getStackStatus match {
        case "CREATE_COMPLETE" => context.parent ! StackCreateCompleted(stackName)
        case "CREATE_FAILED" => throw new Exception("Failed to create the new stack!")
        case "CREATE_IN_PROGRESS" =>
          context.parent ! LogMessage(s"$stackName has not yet reached CREATE_COMPLETE status")
          scheduleTick()
        case _ => throw new Exception("unhandled stack status type")
      }
  }

  def scheduleTick() = context.system.scheduler.scheduleOnce(5.seconds, self, Tick)
}

object StackCreateCompleteMonitor extends PropFactory {
  case object Tick
  case class StackCreateCompleted(stackName: String)

  override def props(args: Any*): Props = Props(classOf[StackCreateCompleteMonitor], args: _*)
}
