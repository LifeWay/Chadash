package actors.workflow.aws.steps.launchconfig

import javax.naming.LimitExceededException

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSWorkflow
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.steps.launchconfig.LaunchConfiguration.{CreateLaunchConfig, _}
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.model.AlreadyExistsException
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import utils.ConfigHelpers.RichConfig

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class LaunchConfigSupervisor(var credentials: AWSCredentials) extends Actor with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5.minutes, loggingEnabled = false) {
    case ex: LimitExceededException => {
      context.parent ! LogMessage(ex.toString)
      log.error(ex, "Limit has been exceeded")
      Stop
    }
    case ex: AlreadyExistsException => {
      context.parent ! LogMessage(ex.toString)
      log.error(ex, "Already exists")
      Stop
    }
    case _: AmazonServiceException => Restart
    case _: AmazonClientException => Restart
    case ex: Exception => {
      context.parent ! LogMessage(ex.toString)
      log.error(ex, "Catch-all Exception Handler.")
      Stop
    }
  }

  override def receive: Receive = {
    case x: StartStep => {
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
  }

  def stepInProcess: Receive = {
    case LaunchConfigCreated => {
      context.parent ! LogMessage("Launch Config: Completed")
      context.parent ! StepFinished(None)
      context.unbecome()
    }
    case Terminated(actorRef) => {
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
    }
  }
}

object LaunchConfigSupervisor {

  def props(credentials: AWSCredentials): Props = Props(new LaunchConfigSupervisor(credentials))
}
