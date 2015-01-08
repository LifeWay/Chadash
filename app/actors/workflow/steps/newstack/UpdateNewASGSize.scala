package actors.workflow.steps.newstack

import actors.WorkflowStatus.LogMessage
import actors.workflow.steps.newstack.ASGSize.{ASGSizeQuery, ASGSizeResult}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.SetDesiredCapacityRequest

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class UpdateNewASGSize(credentials: AWSCredentials, desiredSize: Int, asgName: String) extends Actor with ActorLogging {

  import actors.workflow.steps.newstack.UpdateNewASGSize._

  override def receive: Receive = {
    case ASGToDesiredSize =>

      val desiredCapRequest = new SetDesiredCapacityRequest()
        .withDesiredCapacity(desiredSize)
        .withAutoScalingGroupName(asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.setDesiredCapacity(desiredCapRequest)

      scheduleTick()
      context.become(waitForDesiredSize())
  }

  def waitForDesiredSize():Receive = {
    case Tick =>
      val asgSize = context.actorOf(ASGSize.props(credentials), "queryASGSize")
      context.watch(asgSize)
      asgSize ! ASGSizeQuery(asgName)
      context.parent ! LogMessage("Checking current size")

    case msg: ASGSizeResult =>
      context.unwatch(sender())
      context.stop(sender())

      msg.size match {
        case i if i >= desiredSize =>
          context.parent ! ASGDesiredSizeMet(asgName, i)
        case i if i < desiredSize =>
          context.parent ! LogMessage(s"Size not yet met. Current size: $i Desired Size: $desiredSize")
          scheduleTick()
      }

    case Terminated(actorRef) =>
      context.parent ! LogMessage("A child died... I need to feed myself poison")
      self ! PoisonPill
  }

  def scheduleTick() = context.system.scheduler.scheduleOnce(5.seconds, self, Tick)
}

object UpdateNewASGSize {

  case object ASGToDesiredSize

  case class ASGDesiredSizeMet(asgName: String, size: Int)

  case object Tick

  def props(credentials: AWSCredentials, desiredSize: Int, asgName: String): Props = Props(new UpdateNewASGSize(credentials, desiredSize, asgName))
}

