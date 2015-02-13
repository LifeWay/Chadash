package actors.workflow.steps

import actors.WorkflowLog.LogMessage
import actors.workflow.steps.HealthyInstanceSupervisor.{HealthStatusMet, MonitorASGForELBHealth}
import actors.workflow.tasks.ASGSize.{ASGDesiredSizeQuery, ASGDesiredSizeResult, ASGDesiredSizeSet, ASGSetDesiredSizeCommand}
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import actors.workflow.tasks.StackCreateCompleteMonitor.StackCreateCompleted
import actors.workflow.tasks.StackCreator.{StackCreateCommand, StackCreateRequestCompleted}
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse}
import actors.workflow.tasks._
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.JsValue

class NewStackSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.NewStackSupervisor._

  var oldStackAsg: Option[String] = None
  var oldStackName: Option[String] = None
  var newAsgName: Option[String] = None
  var newStackName: Option[String] = None

  var elbsToCheck: Seq[String] = Seq.empty[String]
  var healthyELBS: Seq[String] = Seq.empty[String]

  override def receive: Receive = {
    case msg: FirstStackLaunch =>
      val stackCreator = context.actorOf(StackCreator.props(credentials), "stackLauncher")
      context.watch(stackCreator)
      stackCreator ! StackCreateCommand(msg.newStackName, msg.imageId, msg.version, msg.stackContent)
      context.become(stepInProcess)

    case msg: StackUpgradeLaunch =>
      val stackCreator = context.actorOf(StackCreator.props(credentials), "stackLauncher")
      context.watch(stackCreator)
      stackCreator ! StackCreateCommand(msg.newStackName, msg.imageId, msg.version, msg.stackContent)

      oldStackAsg = Some(msg.oldStackASG)
      oldStackName = Some(msg.oldStackName)

      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case msg: StackCreateRequestCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New stack has been created. Stack Name: ${msg.stackName}")
      context.parent ! LogMessage("Waiting for new stack to finish launching")

      newStackName = Some(msg.stackName)

      val stackCreateMonitor = context.actorOf(StackCreateCompleteMonitor.props(credentials, msg.stackName))
      context.watch(stackCreateMonitor)

    case msg: StackCreateCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New stack has reached CREATE_COMPLETE status. ${msg.stackName}")
      oldStackName match {
        case Some(oldStack) =>
          val asgFetcher = context.actorOf(StackInfo.props(credentials), "getASGName")
          context.watch(asgFetcher)
          asgFetcher ! StackASGNameQuery(msg.stackName)

        case None =>
          context.parent ! FirstStackLaunchCompleted(msg.stackName)
      }

    case msg: StackASGNameResponse =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG for new stack found ${msg.asgName}, Freezing alarm and scheduled based autoscaling on the new ASG")
      newAsgName = Some(msg.asgName)

      val freezeNewASG = context.actorOf(FreezeASG.props(credentials))
      freezeNewASG ! FreezeASGCommand(msg.asgName)

    case msg: FreezeASGCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage("New ASG Frozen, Querying old stack size")

      oldStackAsg match {
        case Some(asgName) =>
          val oldAsgSizeQuery = context.actorOf(ASGSize.props(credentials), "oldASGSize")
          context.watch(oldAsgSizeQuery)
          oldAsgSizeQuery ! ASGDesiredSizeQuery(asgName)
        case None => throw new Exception("Old ASG Name was not set, this should not have been possible...")
      }

    case msg: ASGDesiredSizeResult =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old ASG desired instances: ${msg.size}, setting new ASG to ${msg.size} desired instances")

      newAsgName match {
        case Some(newAsg) =>
          val resizeASG = context.actorOf(ASGSize.props(credentials), "asgResize")
          context.watch(resizeASG)
          resizeASG ! ASGSetDesiredSizeCommand(newAsg, msg.size)

        case None => throw new Exception("New ASG Name was set, this should not have been possible...")
      }

    case msg: ASGDesiredSizeSet =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG Desired size has been set, querying ASG for ELB list and attached instance IDs")

      val asgELBDetailQuery = context.actorOf(HealthyInstanceSupervisor.props(credentials, msg.size, msg.asgName), "healthyInstanceSupervisor")
      context.watch(asgELBDetailQuery)
      asgELBDetailQuery ! MonitorASGForELBHealth

    case HealthStatusMet =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"New ASG up and reporting healthy in the ELB(s)")
      context.parent ! StackUpgradeLaunchCompleted(newAsgName.get)

    case msg: LogMessage =>
      context.parent forward (msg)

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! WorkflowManager.StepFailed("Failed to launch the new stack. See server log for details.")
  }
}

object NewStackSupervisor {

  case class FirstStackLaunch(newStackName: String, imageId: String, version: String, stackContent: JsValue)

  case class FirstStackLaunchCompleted(newStackName: String)

  case class StackUpgradeLaunch(newStackName: String, imageId: String, version: String, stackContent: JsValue, oldStackName: String, oldStackASG: String)

  case class StackUpgradeLaunchCompleted(newAsgName: String)

  def props(credentials: AWSCredentials): Props = Props(new NewStackSupervisor(credentials))
}
