package actors.workflow.aws.steps.asg

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{CreateAutoScalingGroupRequest, Tag}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class AutoScalingGroup(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.asg.AutoScalingGroup._

  override def receive: Receive = {
    case x: CreateASG =>
      val asgRequest = new CreateAutoScalingGroupRequest()
        .withAutoScalingGroupName(x.labelName)
        .withDesiredCapacity(x.desiredCapacity)
        .withHealthCheckGracePeriod(x.healthCheckGracePeriod)
        .withHealthCheckType(x.healthCheckType)
        .withLaunchConfigurationName(x.labelName)
        .withLoadBalancerNames(x.labelName)
        .withMaxSize(x.maxSize)
        .withMinSize(x.minSize)
        .withVPCZoneIdentifier(x.vpcSubnets.mkString(","))

      x.defaultCoolDown match {
        case Some(y) => asgRequest.withDefaultCooldown(y)
        case None => ()
      }

      x.placementGroup match {
        case Some(y) => asgRequest.withPlacementGroup(y)
        case None => ()
      }

      x.tags match {
        case Some(y) => {
          asgRequest.setTags(y.seq.foldLeft(Seq.empty[Tag])((x, i) =>
            x :+ new Tag().withKey(i._1).withValue(i._2)
          ).asJava)
        }
        case None => ()
      }

      x.terminationPolicies match {
        case Some(y) => asgRequest.withTerminationPolicies(y.asJava)
        case None => ()
      }

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.createAutoScalingGroup(asgRequest)

      context.parent ! ASGCreated
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateASG => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object AutoScalingGroup {

  case class CreateASG(labelName: String,
                       desiredCapacity: Int,
                       minSize: Int,
                       maxSize: Int,
                       healthCheckGracePeriod: Int,
                       healthCheckType: String,
                       vpcSubnets: Seq[String],
                       tags: Option[Seq[(String, String)]] = None,
                       defaultCoolDown: Option[Int] = None,
                       placementGroup: Option[String] = None,
                       terminationPolicies: Option[Seq[String]] = None
                        )

  case object ASGCreated

  def props(creds: AWSCredentials): Props = Props(new AutoScalingGroup(creds))
}
