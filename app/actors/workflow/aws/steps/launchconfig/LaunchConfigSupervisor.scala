package actors.workflow.aws.steps.launchconfig

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.steps.launchconfig.LaunchConfiguration.{CreateLaunchConfig, _}
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import utils.ConfigHelpers.RichConfig

import scala.collection.JavaConversions._

class LaunchConfigSupervisor(var credentials: AWSCredentials) extends Actor with AWSSupervisorStrategy {

  override def receive: Receive = {
    case x: StartStep =>
      val config = x.configData.getConfig(s"steps.${aws.CreateLaunchConfig}")

      val launchConfig = context.actorOf(LaunchConfiguration.props(credentials), "launchConfig")
      context.watch(launchConfig)

      val amiName = (x.deployData \ "imageId").as[String]
      val userData = (x.deployData \ "userData").asOpt[String]

      context.parent ! LogMessage("Launch Config: Attempting to create")
      launchConfig ! CreateLaunchConfig(
        labelName = s"${x.appName}-v${x.appVersion}",
        detailedMonitoring = config.getOptBoolean("detailedMonitoring"),
        publicIpAddress = config.getOptBoolean("publicIpAddress"),
        amiImageId = amiName,
        instanceType = config.getString("instanceType"),
        keyName = config.getString("keyName"),
        securityGroups = config.getStringList("securityGroups").toList.toSeq,
        userData = userData,
        ebsOptimized = config.getOptBoolean("ebsOptimized"),
        iamInstanceProfile = config.getOptString("iamInstanceProfile"),
        placementTenancy = config.getOptString("placementTenancy")
      )
      context.become(stepInProcess)

  }

  def stepInProcess: Receive = {
    case LaunchConfigCreated =>
      context.parent ! LogMessage("Launch Config: Completed")
      context.parent ! StepFinished(None)
      context.unbecome()

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed

  }
}

object LaunchConfigSupervisor {

  def props(credentials: AWSCredentials): Props = Props(new LaunchConfigSupervisor(credentials))
}
