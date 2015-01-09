package actors.workflow.tasks

import actors.workflow.RestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{SetDesiredCapacityRequest, DescribeAutoScalingGroupsRequest}

import scala.collection.JavaConverters._

class ASGSize(credentials: AWSCredentials) extends Actor with RestartableActor with ActorLogging {

  import actors.workflow.tasks.ASGSize._

  override def receive: Receive = {
    case msg: ASGDesiredSizeQuery =>
      val asgFilter = new DescribeAutoScalingGroupsRequest()
        .withAutoScalingGroupNames(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      val result = awsClient.describeAutoScalingGroups(asgFilter).getAutoScalingGroups.asScala.toSeq
      context.sender() ! ASGDesiredSizeResult(msg.asgName, result(0).getDesiredCapacity)

    case msg: ASGSetDesiredSizeCommand =>
      val desiredCapRequest = new SetDesiredCapacityRequest()
        .withDesiredCapacity(msg.size)
        .withAutoScalingGroupName(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.setDesiredCapacity(desiredCapRequest)
      context.sender() ! ASGDesiredSizeSet(msg.asgName)
  }
}

object ASGSize {

  case class ASGDesiredSizeQuery(asgName: String)

  case class ASGDesiredSizeResult(asgName: String, size: Int)

  case class ASGSetDesiredSizeCommand(asgName: String, size: Int)

  case class ASGDesiredSizeSet(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new ASGSize(credentials))
}