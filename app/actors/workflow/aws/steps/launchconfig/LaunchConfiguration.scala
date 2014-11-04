package actors.workflow.aws.steps.launchconfig

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{CreateLaunchConfigurationRequest, InstanceMonitoring}
import com.google.common.base.Charsets
import com.google.common.io.BaseEncoding

import scala.concurrent.duration._

class LaunchConfiguration(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.launchconfig.LaunchConfiguration._

  override def receive: Receive = {
    case x: CreateLaunchConfig => {
      val instanceMonitoring = new InstanceMonitoring()
      x.detailedMonitoring match {
        case Some(y) => instanceMonitoring.setEnabled(y)
        case None => instanceMonitoring.setEnabled(false)
      }

      val launchConfig = new CreateLaunchConfigurationRequest()
        .withImageId(x.amiImageId)
        .withInstanceMonitoring(instanceMonitoring)
        .withInstanceType(x.instanceType)
        .withKeyName(x.keyName)
        .withLaunchConfigurationName(x.labelName)
        .withSecurityGroups(x.securityGroups.toArray: _*)

      x.publicIpAddress match {
        case Some(y) => launchConfig.setAssociatePublicIpAddress(y)
        case None => launchConfig.setAssociatePublicIpAddress(false)
      }

      x.ebsOptimized match {
        case Some(y) => launchConfig.setEbsOptimized(y)
        case None => ()
      }
      x.iamInstanceProfile match {
        case Some(y) => launchConfig.setIamInstanceProfile(y)
        case None => ()
      }
      x.placementTenancy match {
        case Some(y) => launchConfig.setPlacementTenancy(y)
        case None => ()
      }
      x.userData match {
        //TODO: pick a different base64 encoding util thanks to guavas warning when compiling with scala
        case Some(y) => launchConfig.setUserData(BaseEncoding.base64().encode(y.getBytes(Charsets.UTF_8)))
        case None => ()
      }

      val awsClient = new AmazonAutoScalingClient(credentials)
      awsClient.createLaunchConfiguration(launchConfig)

      context.parent ! LaunchConfigCreated
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateLaunchConfig => context.system.scheduler.scheduleOnce(10.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object LaunchConfiguration {

  case class CreateLaunchConfig(labelName: String,
                                amiImageId: String,
                                instanceType: String,
                                keyName: String,
                                securityGroups: Seq[String],
                                detailedMonitoring: Option[Boolean] = Some(false),
                                publicIpAddress: Option[Boolean] = Some(false),
                                userData: Option[String] = None,
                                ebsOptimized: Option[Boolean] = None,
                                iamInstanceProfile: Option[String] = None,
                                placementTenancy: Option[String] = None
                                 )

  case object LaunchConfigCreated


  def props(creds: AWSCredentials): Props = Props(new LaunchConfiguration(creds))
}
