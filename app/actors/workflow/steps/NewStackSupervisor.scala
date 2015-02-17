package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.HealthyInstanceSupervisor.{CheckHealth, HealthStatusMet}
import actors.workflow.steps.NewStackSupervisor.{NewStackData, NewStackState}
import actors.workflow.tasks.ASGSize.{ASGDesiredSizeQuery, ASGDesiredSizeResult, ASGDesiredSizeSet, ASGSetDesiredSizeCommand}
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import actors.workflow.tasks.StackCreateCompleteMonitor.StackCreateCompleted
import actors.workflow.tasks.StackCreator.{StackCreateCommand, StackCreateRequestCompleted}
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse}
import actors.workflow.tasks._
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.JsValue

class NewStackSupervisor(credentials: AWSCredentials) extends FSM[NewStackState, NewStackData] with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.NewStackSupervisor._

  startWith(AwaitingNewStackLaunchCommand, Uninitialized)

  when(AwaitingNewStackLaunchCommand) {
    case Event(msg: NewStackFirstLaunchCommand, Uninitialized) =>
      val stackCreator = context.actorOf(StackCreator.props(credentials), "stackLauncher")
      context.watch(stackCreator)
      stackCreator ! StackCreateCommand(msg.newStackName, msg.imageId, msg.version, msg.stackContent)
      goto(AwaitingStackCreatedResponse) using FirstTimeStack(msg.newStackName)

    case Event(msg: NewStackUpgradeLaunchCommand, Uninitialized) =>
      val stackCreator = context.actorOf(StackCreator.props(credentials), "stackLauncher")
      context.watch(stackCreator)
      stackCreator ! StackCreateCommand(msg.newStackName, msg.imageId, msg.version, msg.stackContent)
      goto(AwaitingStackCreatedResponse) using UpgradeOldStackData(msg.oldStackASG, msg.oldStackName, msg.newStackName)
  }

  when(AwaitingStackCreatedResponse) {
    case Event(StackCreateRequestCompleted, data: FirstStepData) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New stack has been created. Stack Name: ${data.newStackName}")
      context.parent ! LogMessage("Waiting for new stack to finish launching")

      val stackCreateMonitor = context.actorOf(StackCreateCompleteMonitor.props(credentials, data.newStackName))
      context.watch(stackCreateMonitor)

      data match {
        case stackData: UpgradeOldStackData => goto(AwaitingStackCreateCompleted) using UpgradeOldStackData(stackData.oldStackASG, stackData.oldStackName, stackData.newStackName)
        case _ => goto(AwaitingStackCreateCompleted)
      }
  }

  when(AwaitingStackCreateCompleted) {
    case Event(msg: StackCreateCompleted, data: NewStackData) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"New stack has reached CREATE_COMPLETE status. ${msg.stackName}")

      data match {
        case stackData: UpgradeOldStackData =>
          val asgFetcher = context.actorOf(StackInfo.props(credentials), "getASGName")
          context.watch(asgFetcher)
          asgFetcher ! StackASGNameQuery(msg.stackName)
          goto(AwaitingStackASGName)
        case _ =>
          context.parent ! FirstStackLaunchCompleted(msg.stackName)
          stop()
      }
  }

  when(AwaitingStackASGName) {
    case Event(msg: StackASGNameResponse, data: UpgradeOldStackData) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG for new stack found ${msg.asgName}, Freezing alarm and scheduled based autoscaling on the new ASG")

      val freezeNewASG = context.actorOf(FreezeASG.props(credentials))
      context.watch(freezeNewASG)
      freezeNewASG ! FreezeASGCommand(msg.asgName)
      goto(AwaitingFreezeASGResponse) using UpgradeOldStackDataWithASG(data.oldStackASG, data.oldStackName, data.newStackName, msg.asgName)
  }

  when(AwaitingFreezeASGResponse) {
    case Event(msg: FreezeASGCompleted, UpgradeOldStackDataWithASG(oldAsgName, _, _, _)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage("New ASG Frozen, Querying old stack size")

      val oldAsgSizeQuery = context.actorOf(ASGSize.props(credentials), "oldASGSize")
      context.watch(oldAsgSizeQuery)
      oldAsgSizeQuery ! ASGDesiredSizeQuery(oldAsgName)
      goto(AwaitingASGDesiredSizeResult)
  }

  when(AwaitingASGDesiredSizeResult) {
    case Event(msg: ASGDesiredSizeResult, UpgradeOldStackDataWithASG(_, _, _, newAsgName)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"Old ASG desired instances: ${msg.size}, setting new ASG to ${msg.size} desired instances")

      val resizeASG = context.actorOf(ASGSize.props(credentials), "asgResize")
      context.watch(resizeASG)
      resizeASG ! ASGSetDesiredSizeCommand(newAsgName, msg.size)
      goto(AwaitingASGDesiredSizeSetResponse)
  }

  when(AwaitingASGDesiredSizeSetResponse) {
    case Event(msg: ASGDesiredSizeSet, UpgradeOldStackDataWithASG(_, _, _, _)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG Desired size has been set, querying ASG for ELB list and attached instance IDs")

      val asgELBDetailQuery = context.actorOf(HealthyInstanceSupervisor.props(credentials, msg.size, msg.asgName), "healthyInstanceSupervisor")
      context.watch(asgELBDetailQuery)
      asgELBDetailQuery ! CheckHealth
      goto(AwaitingHealthyNewASG)
  }

  when(AwaitingHealthyNewASG) {
    case Event(HealthStatusMet, UpgradeOldStackDataWithASG(_, _, _, newAsgName)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"New ASG up and reporting healthy in the ELB(s)")
      context.parent ! StackUpgradeLaunchCompleted(newAsgName)
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to delete a stack")
      stop()

    case Event(msg: Any, data: NewStackData) =>
      log.debug(s"Unhandled message: ${msg.toString} Data: ${data.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object NewStackSupervisor {
  //Interaction Messages
  sealed trait NewStackMessage
  case class NewStackFirstLaunchCommand(newStackName: String, imageId: String, version: String, stackContent: JsValue) extends NewStackMessage
  case class NewStackUpgradeLaunchCommand(newStackName: String, imageId: String, version: String, stackContent: JsValue, oldStackName: String, oldStackASG: String) extends NewStackMessage
  case class FirstStackLaunchCompleted(newStackName: String) extends NewStackMessage
  case class StackUpgradeLaunchCompleted(newAsgName: String) extends NewStackMessage

  //FSM: States
  sealed trait NewStackState
  case object AwaitingNewStackLaunchCommand extends NewStackState
  case object AwaitingStackCreatedResponse extends NewStackState
  case object AwaitingStackCreateCompleted extends NewStackState
  case object AwaitingStackASGName extends NewStackState
  case object AwaitingFreezeASGResponse extends NewStackState
  case object AwaitingASGDesiredSizeResult extends NewStackState
  case object AwaitingASGDesiredSizeSetResponse extends NewStackState
  case object AwaitingHealthyNewASG extends NewStackState

  //FSM: Data
  sealed trait NewStackData
  sealed trait FirstStepData extends NewStackData {
    def newStackName: String
  }
  case object Uninitialized extends NewStackData
  case class FirstTimeStack(newStackName: String) extends NewStackData with FirstStepData
  case class UpgradeOldStackData(oldStackASG: String, oldStackName: String, newStackName: String) extends NewStackData with FirstStepData
  case class UpgradeOldStackDataWithASG(oldStackASG: String, oldStackName: String, newStackName: String, newStackASG: String) extends NewStackData

  def props(credentials: AWSCredentials): Props = Props(new NewStackSupervisor(credentials))
}
