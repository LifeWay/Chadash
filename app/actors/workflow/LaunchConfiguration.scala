package actors.workflow

import actors.workflow.LaunchConfiguration.{CreateLaunchConfig, LaunchConfigCreated}
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{CreateLaunchConfigurationRequest, InstanceMonitoring}
import com.google.common.base.Charsets
import com.google.common.io.BaseEncoding

import scala.concurrent.duration._

class LaunchConfiguration(credentials: AWSCredentials) extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: CreateLaunchConfig => {
      val instanceMonitoring = new InstanceMonitoring()
        .withEnabled(x.detailedMonitoring)

      val launchConfig = new CreateLaunchConfigurationRequest()
        .withAssociatePublicIpAddress(x.publicIpAddress)
        .withImageId(x.amiImageId)
        .withInstanceMonitoring(instanceMonitoring)
        .withInstanceType(x.instanceType)
        .withKeyName(x.keyName)
        .withLaunchConfigurationName(x.labelName)
        .withSecurityGroups(x.securityGroups.toArray: _*)

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
                                detailedMonitoring: Boolean,
                                publicIpAddress: Boolean,
                                amiImageId: String,
                                instanceType: String,
                                keyName: String,
                                securityGroups: Seq[String],
                                userData: Option[String] = None,
                                ebsOptimized: Option[Boolean] = None,
                                iamInstanceProfile: Option[String] = None,
                                placementTenancy: Option[String] = None
                                 )

  case object LaunchConfigCreated


  def props(creds: AWSCredentials): Props = Props(new LaunchConfiguration(creds))
}
