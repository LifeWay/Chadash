package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.model.{DescribeAutoScalingGroupsRequest, SetDesiredCapacityRequest}
import utils.{AmazonAutoScalingService, PropFactory}

import scala.collection.JavaConverters._

class ASGSize(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonAutoScalingService {

  import actors.workflow.tasks.ASGSize._

  override def receive: Receive = {
    case msg: ASGDesiredSizeQuery =>
      val asgFilter = new DescribeAutoScalingGroupsRequest()
                      .withAutoScalingGroupNames(msg.asgName)

      val awsClient = autoScalingClient(credentials)
      val result = awsClient.describeAutoScalingGroups(asgFilter).getAutoScalingGroups.asScala.toSeq
      context.parent ! ASGDesiredSizeResult(result(0).getDesiredCapacity)

    case msg: ASGSetDesiredSizeCommand =>
      val desiredCapRequest = new SetDesiredCapacityRequest()
                              .withDesiredCapacity(msg.size)
                              .withAutoScalingGroupName(msg.asgName)

      val awsClient = autoScalingClient(credentials)
      awsClient.setDesiredCapacity(desiredCapRequest)
      context.parent ! ASGSetDesiredSizeRequested
  }
}

object ASGSize extends PropFactory {
  case class ASGDesiredSizeQuery(asgName: String)
  case class ASGDesiredSizeResult(size: Int)
  case class ASGSetDesiredSizeCommand(asgName: String, size: Int)
  case object ASGSetDesiredSizeRequested

  override def props(args: Any*): Props = Props(classOf[ASGSize], args: _*)
}