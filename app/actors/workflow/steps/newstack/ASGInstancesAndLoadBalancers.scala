package actors.workflow.steps.newstack

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest

import scala.collection.JavaConverters._

class ASGInstancesAndLoadBalancers(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.newstack.ASGInstancesAndLoadBalancers._

  override def receive: Receive = {
    case msg: ASGAndELBQuery =>

      val request = new DescribeAutoScalingGroupsRequest()
        .withAutoScalingGroupNames(msg.asgName)

      val awsClient = new AmazonAutoScalingClient(credentials)
      val asg = awsClient.describeAutoScalingGroups(request).getAutoScalingGroups.asScala.toSeq(0)

      val instanceIds = asg.getInstances.asScala.toSeq.foldLeft(Seq.empty[String])((sum, i) => sum :+ i.getInstanceId)
      val elbNames = asg.getLoadBalancerNames.asScala.toSeq

      context.parent ! ASGAndELBResult(elbNames, instanceIds)
  }
}

object ASGInstancesAndLoadBalancers {

  case class ASGAndELBQuery(asgName: String)

  case class ASGAndELBResult(elbNames: Seq[String], instanceIds: Seq[String])

  def props(credentials: AWSCredentials): Props = Props(new ASGInstancesAndLoadBalancers(credentials))
}

