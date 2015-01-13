package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest

import scala.collection.JavaConverters._

class ASGInfo(credentials: AWSCredentials) extends Actor with AWSRestartableActor with ActorLogging {

  import actors.workflow.tasks.ASGInfo._

  override def receive: Receive = {
    case msg: ASGInServiceInstancesAndELBSQuery =>

      val request = new DescribeAutoScalingGroupsRequest()
        .withAutoScalingGroupNames(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      val asg = awsClient.describeAutoScalingGroups(request).getAutoScalingGroups.asScala.toSeq(0)

      val instanceIds = asg.getInstances.asScala.toSeq.foldLeft(Seq.empty[String]){
        (sum, i) => i.getLifecycleState match {
          case "InService" => sum :+ i.getInstanceId
          case _ => sum
        }
      }

      val elbNames = asg.getLoadBalancerNames.asScala.toSeq

      context.sender() ! ASGInServiceInstancesAndELBSResult(elbNames, instanceIds)
  }
}

object ASGInfo {

  case class ASGInServiceInstancesAndELBSQuery(asgName: String)

  case class ASGInServiceInstancesAndELBSResult(elbNames: Seq[String], instanceIds: Seq[String])

  def props(credentials: AWSCredentials): Props = Props(new ASGInfo(credentials))
}

