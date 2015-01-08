package actors.workflow.steps.newstack

import actors.WorkflowStatus.{Log, LogMessage}
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import actors.workflow.steps.newstack.ASGInstancesAndLoadBalancers.{ASGAndELBResult, ASGAndELBQuery}
import actors.workflow.steps.newstack.ASGSize.{ASGSizeQuery, ASGSizeResult}
import actors.workflow.steps.newstack.ELBHealthyInstanceChecker.ELBInstancesHealthy
import actors.workflow.steps.newstack.StackLaunchedMonitor.StackLaunchCompleted
import actors.workflow.steps.newstack.StackLauncher.{LaunchStack, StackLaunched}
import actors.workflow.steps.newstack.UpdateNewASGSize.{ASGDesiredSizeMet, ASGToDesiredSize}
import actors.workflow.steps.validateandfreeze.FreezeASG.{ASGFrozen, FreezeASGWithName}
import actors.workflow.steps.validateandfreeze.GetASGName.{ASGForStack, GetASGNameForStackName}
import actors.workflow.steps.validateandfreeze.{FreezeASG, GetASGName}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.{JsString, JsValue}

class NewStackSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  var oldStackData: Option[JsValue] = None
  var elbsToCheck: Seq[String] = Seq.empty[String]
  var healthyELBS: Seq[String] = Seq.empty[String]
  var newAsgName = ""

  override def receive: Receive = {
    case step: StartStep =>
      step.data("loadStackFile") match {
        case Some(x) =>
          val stackLauncher = context.actorOf(StackLauncher.props(credentials), "stackLauncher")
          context.watch(stackLauncher)
          stackLauncher ! LaunchStack(step.stackName, step.stackAmi, step.appVersion, x)

          step.data("validateAndFreeze") match {
            case Some(oldStack) => oldStackData = Some(oldStack)
            case None => oldStackData = None
          }
          context.become(stepInProcess)
        case None => throw new Exception("the stack must be provided from the stackLauncher during the workflow")
      }
  }

  def stepInProcess: Receive = {
    case msg: StackLaunched =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New stack has been created. Stack Name: ${msg.stackName} Stack ID: ${msg.stackId}")
      context.parent ! LogMessage("Waiting for new stack to finish launching")
      val stackLaunchMonitor = context.actorOf(StackLaunchedMonitor.props(credentials, msg.stackName))
      context.watch(stackLaunchMonitor)

    case msg: StackLaunchCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New stack has reached CREATE_COMPLETE status. ${msg.stackName}")
      oldStackData match {
        case Some(oldStack) =>
          val asgFetcher = context.actorOf(GetASGName.props(credentials), "getASGName")
          context.watch(asgFetcher)
          asgFetcher ! GetASGNameForStackName(msg.stackName)

        case None =>
          context.parent ! StepFinished(None)
      }

    case msg: ASGForStack =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG for new stack found ${msg.asgName}")
      context.parent ! LogMessage(s"Freeze alarm and scheduled based autoscaling on the new ASG")

      newAsgName = msg.asgName

      val freezeNewASG = context.actorOf(FreezeASG.props(credentials))
      freezeNewASG ! FreezeASGWithName(msg.asgName)

    case msg: ASGFrozen =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage("New ASG Frozen")
      context.parent ! LogMessage(s"Querying old stack size")

      val oldAsgSizeQuery = context.actorOf(ASGSize.props(credentials), "oldASGSize")
      context.watch(oldAsgSizeQuery)
      val oldAsgName = (oldStackData.get \ "old-asg").asInstanceOf[JsString].value
      oldAsgSizeQuery ! ASGSizeQuery(oldAsgName)

    case msg: ASGSizeResult =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old ASG desired instances: ${msg.size}")
      context.parent ! LogMessage(s"Setting new ASG to ${msg.size} desired instances")

      val resizeASG = context.actorOf(UpdateNewASGSize.props(credentials, msg.size, newAsgName))
      context.watch(resizeASG)
      resizeASG ! ASGToDesiredSize

    case msg: ASGDesiredSizeMet =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG Desired size has been met. ASG: ${msg.asgName} Size: ${msg.size}")
      context.parent ! LogMessage(s"Querying ASG for ELB list and attached instance IDs")

      val asgELBDetailQuery = context.actorOf(ASGInstancesAndLoadBalancers.props(credentials))
      context.watch(asgELBDetailQuery)
      asgELBDetailQuery ! ASGAndELBQuery(msg.asgName)

    case msg: ASGAndELBResult =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG's ELB and instance list received, monitoring for all instances to become healthy in the ELB")

      elbsToCheck = msg.elbNames
      //FAN OUT FOR EACH ELB.
      msg.elbNames.map { elb =>
        val elbInstanceHealth = context.actorOf(ELBHealthyInstanceChecker.props(credentials, elb, msg.instanceIds))
        context.watch(elbInstanceHealth)
      }

    case msg: ELBInstancesHealthy =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ELB Instance became healthy ${msg.elbName}")
      healthyELBS = healthyELBS :+ msg.elbName

      //once all ELB's report healthy, then continue.
      if(healthyELBS.length == elbsToCheck.length)
        context.parent ! StepFinished(None)

    case msg: Log =>
      context.parent forward(msg)

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object NewStackSupervisor {
  def props(credentials: AWSCredentials): Props = Props(new NewStackSupervisor(credentials))
}
