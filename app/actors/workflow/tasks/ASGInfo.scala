package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import utils.{AmazonAutoScalingService, PropFactory}

import scala.collection.JavaConverters._

class ASGInfo(credentials: AWSCredentials) extends AWSRestartableActor with AmazonAutoScalingService {

  import actors.workflow.tasks.ASGInfo._

  override def receive: Receive = {
    case msg: ASGInServiceInstancesAndELBSQuery =>

      val request = new DescribeAutoScalingGroupsRequest()
                    .withAutoScalingGroupNames(msg.asgName)

      val awsClient = autoScalingClient(credentials)
      val asg = awsClient.describeAutoScalingGroups(request).getAutoScalingGroups.asScala.toSeq(0)

      val instanceIds = asg.getInstances.asScala.toSeq.foldLeft(Seq.empty[String]) {
        (sum, i) => i.getLifecycleState match {
          case "InService" => sum :+ i.getInstanceId
          case _ => sum
        }
      }

      val elbNames = asg.getLoadBalancerNames.asScala.toSeq

      context.parent ! ASGInServiceInstancesAndELBSResult(elbNames, instanceIds)

    case m: Any =>
      log.debug(s"unhandled message: ${m.toString}")
  }
}

object ASGInfo extends PropFactory {
  case class ASGInServiceInstancesAndELBSQuery(asgName: String)
  case class ASGInServiceInstancesAndELBSResult(elbNames: Seq[String], instanceIds: Seq[String])

  def props(args: Any*): Props = Props(classOf[ASGInfo], args)

  def props(credentials: AWSCredentials): Props = Props(new ASGInfo(credentials))
}

