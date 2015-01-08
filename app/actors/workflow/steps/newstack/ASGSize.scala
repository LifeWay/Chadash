package actors.workflow.steps.newstack

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest

import scala.collection.JavaConverters._

class ASGSize(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.newstack.ASGSize._

  override def receive: Receive = {
    case msg: ASGSizeQuery =>
      val asgFilter = new DescribeAutoScalingGroupsRequest()
        .withAutoScalingGroupNames(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      val result = awsClient.describeAutoScalingGroups(asgFilter).getAutoScalingGroups.asScala.toSeq
      context.parent ! ASGSizeResult(msg.asgName, result(0).getDesiredCapacity)
  }
}

object ASGSize {

  case class ASGSizeQuery(asgName: String)

  case class ASGSizeResult(asgName: String, size: Int)

  def props(credentials: AWSCredentials): Props = Props(new ASGSize(credentials))
}