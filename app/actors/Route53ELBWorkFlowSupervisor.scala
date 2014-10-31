package actors

import javax.naming.LimitExceededException

import actors.AmazonCredentials.{CurrentCredentials, NoCredentials}
import actors.DeploymentSupervisor.{Deploy, Started, WorkflowInProgress}
import actors.WorkflowStatus.{GetStatus, ItemFinished, LogMessage}
import actors.workflow.AutoScalingGroup.{ASGCreated, CreateASG}
import actors.workflow.ElasticLoadBalancer.{CreateELB, ELBCreated}
import actors.workflow.LaunchConfiguration.{CreateLaunchConfig, LaunchConfigCreated}
import actors.workflow.Route53Switch.{RequestRoute53Switch, Route53SwitchCompleted}
import actors.workflow.WarmUp.{WaitForWarmUp, WarmUpCompleted}
import actors.workflow._
import akka.actor.SupervisorStrategy._
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.model.AlreadyExistsException
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.typesafe.config.{Config, ConfigFactory}
import utils.Constants

import scala.collection.JavaConversions._
import scala.concurrent.duration._

/**
 * The workflow supervisor follows the Error-Kernel Pattern. We need this actor to maintain state of the workflow, so
 * we delegate all of the externalized calls that could throw errors out to other systems and allow this supervisor
 * the capability to decide what to do when those things error.
 *
 */
class Route53ELBWorkFlowSupervisor extends Actor with ActorLogging {

  import actors.Route53ELBWorkFlowSupervisor._
  import context._

  var deployment: Deploy = null
  var credentials: AWSCredentials = null
  var appConfig: Config = null

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 5.minutes) {
    case _: LimitExceededException => Stop
    case _: AlreadyExistsException => Stop
    case _: AmazonServiceException => Restart
    case _: AmazonClientException => Restart
    case _: Exception => Stop
  }

  override def receive: Receive = {
    case deploy: Deploy => {
      deployment = deploy
      val workflowStatus = context.actorOf(WorkflowStatus.props(5), Constants.statusActorName)
      context.watch(workflowStatus)
      workflowStatus ! LogMessage("Starting deployment workflow...")
      sender ! Started
      self ! StartWorkflow
      become(inProcess)
    }
  }

  def inProcess: Receive = {
    case deploy: Deploy => {
      sender ! WorkflowInProgress
    }
    case StartWorkflow => {
      appConfig = ConfigFactory.load().getConfig(s"deployment-configs.${deployment.appName}")

      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
    }
    case x: CurrentCredentials => {
      context.unwatch(sender())
      credentials = x.credentials

      val launchConfigActor = context.actorOf(LaunchConfiguration.props(credentials), "launchConfig")
      context.watch(launchConfigActor)
      context.child(Constants.statusActorName).get ! LogMessage("Launch Config: Create")

      launchConfigActor ! CreateLaunchConfig(
        labelName = s"${deployment.appName}-v${deployment.appVersion}",
        detailedMonitoring = appConfig.getBoolean("detailedMonitoring"),
        publicIpAddress = appConfig.getBoolean("publicIpAddress"),
        amiImageId = deployment.amiName,
        instanceType = appConfig.getString("instanceType"),
        keyName = appConfig.getString("keyName"),
        securityGroups = appConfig.getStringList("securityGroups").toList.toSeq,
        userData = deployment.userData
        //TODO: a few more optional params to pull from conf...
      )
    }
    case LaunchConfigCreated => {
      context.child(Constants.statusActorName).get ! ItemFinished("Launch Config: Completed")
      stopChild(sender())

      val elb = context.actorOf(Props[ElasticLoadBalancer], "createELB")
      context.watch(elb)
      elb ! CreateELB(deployment.appName)
      context.child(Constants.statusActorName).get ! LogMessage("ELB: Create")
    }
    case x: ELBCreated => {
      context.child(Constants.statusActorName).get ! ItemFinished("ELB: Completed")
      log.debug("")
      stopChild(sender())

      val asg = context.actorOf(Props[AutoScalingGroup], "createASG")
      context.watch(asg)
      asg ! CreateASG(deployment.appName)
      context.child(Constants.statusActorName).get ! LogMessage("ASG: Create")
    }
    case x: ASGCreated => {
      context.child(Constants.statusActorName).get ! ItemFinished("ASG: Completed")
      stopChild(sender())

      val warmup = context.actorOf(Props[WarmUp], "warmup")
      context.watch(warmup)
      warmup ! WaitForWarmUp(deployment.appName)
      context.child(Constants.statusActorName).get ! LogMessage("WarnUp: Start Task")
    }
    case x: WarmUpCompleted => {
      context.child(Constants.statusActorName).get ! ItemFinished("WarnUp: Completed")
      stopChild(sender())

      val route53switch = context.actorOf(Props[Route53Switch], "route53switch")
      context.watch(route53switch)
      route53switch ! RequestRoute53Switch(deployment.appName)
      context.child(Constants.statusActorName).get ! LogMessage("Route53: Start Task")
    }
    case x: Route53SwitchCompleted => {
      context.child(Constants.statusActorName).get ! ItemFinished("Route53: Completed")
      stopChild(sender())

      become(receive)
    }
    case GetStatus => context.child(Constants.statusActorName).get forward GetStatus
    // EXCEPTIONAL CASES:
    case NoCredentials => {
      context.unwatch(sender())
      log.error("Unable to get AWS Credentials")
      context.parent ! DeploymentSupervisor.DeployFailed
    }
    case Terminated(actorRef) => {
      log.debug(s"One of our children has died...the deployment has failed and needs a human. Details:  ${actorRef.toString}")
      context.parent ! DeploymentSupervisor.DeployFailed
    }
    case m: Any => log.debug(s"unknown message type received. ${m.toString}")
  }

  def stopChild(ref: ActorRef) = {
    context.unwatch(ref)
    context.stop(ref)
  }
}

object Route53ELBWorkFlowSupervisor {

  case object StartWorkflow

}
