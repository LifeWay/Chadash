package actors

import actors.DeploymentSupervisor.{Deploy, Started, WorkflowInProgress}
import actors.workflow.AutoScalingGroup.{ASGCreated, CreateASG}
import actors.workflow.ElasticLoadBalancer.{CreateELB, ELBCreated}
import actors.workflow.LaunchConfiguration.{CreateLaunchConfig, LaunchConfigCreated}
import actors.workflow.Route53Switch.{RequestRoute53Switch, Route53SwitchCompleted}
import actors.workflow.WarmUp.{WaitForWarmUp, WarmUpCompleted}
import actors.workflow._
import akka.actor.SupervisorStrategy._
import akka.actor._

import scala.concurrent.duration._

/**
 * The workflow supervisor follows the Error-Kernel Pattern. We need this actor to maintain state of the workflow, so
 * we delegate all of the externalized calls that could throw errors out to other systems and allow this supervisor
 * the capability to decide what to do when those things error.
 *
 */
class WorkflowSupervisor extends Actor with ActorLogging {

  import actors.WorkflowSupervisor._
  import context._

  var deployment: Deploy = null
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 5 minutes)(defaultDecider)

  override def receive: Receive = {
    case deploy: Deploy => {
      deployment = deploy
      log.debug("Starting deployment workflow...")
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
      val launchConfig = context.actorOf(Props[LaunchConfiguration], "launchConfig")
      context.watch(launchConfig)
      launchConfig ! CreateLaunchConfig(deployment.appName)
    }
    case x: LaunchConfigCreated => {
      log.debug("Launch configuration created...")
      stopChild(sender())

      val elb = context.actorOf(Props[ElasticLoadBalancer], "createELB")
      context.watch(elb)
      elb ! CreateELB(deployment.appName)
    }
    case x: ELBCreated => {
      log.debug("ELB created...")
      stopChild(sender())

      val asg = context.actorOf(Props[AutoScalingGroup], "createASG")
      context.watch(asg)
      asg ! CreateASG(deployment.appName)
    }
    case x: ASGCreated => {
      log.debug("ASG created...")
      stopChild(sender())

      val warmup = context.actorOf(Props[WarmUp], "warmup")
      context.watch(warmup)
      warmup ! WaitForWarmUp(deployment.appName)
    }
    case x: WarmUpCompleted => {
      log.debug("ELB warmup completed...")
      stopChild(sender())

      val route53switch = context.actorOf(Props[Route53Switch], "route53switch")
      context.watch(route53switch)
      route53switch ! RequestRoute53Switch(deployment.appName)
    }
    case x: Route53SwitchCompleted => {
      log.debug("Route 53 switch has completed. We are finished!")
      stopChild(sender())

      become(receive)
    }
    case Terminated(actorRef) => {
      log.debug(s"One of our children has died...the deployment has failed and needs a human ${actorRef.toString}")
      context.parent ! DeploymentSupervisor.DeployFailed
    }
    case m: Any => log.debug(s"unknown message type received. ${m.toString}")
  }

  def stopChild(ref: ActorRef) = {
    context.unwatch(ref)
    context.stop(ref)
  }
}

object WorkflowSupervisor {

  case object StartWorkflow

}
