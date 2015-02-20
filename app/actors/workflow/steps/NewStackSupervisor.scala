package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.HealthyInstanceSupervisor.{CheckHealth, HealthStatusMet}
import actors.workflow.steps.NewStackSupervisor.{NewStackData, NewStackState}
import actors.workflow.tasks.ASGSize._
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import actors.workflow.tasks.StackCreateCompleteMonitor.StackCreateCompleted
import actors.workflow.tasks.StackCreator.{StackCreateCommand, StackCreateRequestCompleted}
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse}
import actors.workflow.tasks._
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.JsValue
import utils.{ActorFactory, PropFactory}

class NewStackSupervisor(credentials: AWSCredentials,
                         actorFactory: ActorFactory) extends FSM[NewStackState, NewStackData] with ActorLogging
                                                             with AWSSupervisorStrategy {

  import actors.workflow.steps.NewStackSupervisor._

  startWith(AwaitingNewStackLaunchCommand, Uninitialized)

  when(AwaitingNewStackLaunchCommand) {
    case Event(msg: NewStackFirstLaunchCommand, Uninitialized) =>
      val stackCreator = actorFactory(StackCreator, context, "stackLauncher", credentials)
      context.watch(stackCreator)
      stackCreator ! StackCreateCommand(msg.newStackName, msg.imageId, msg.version, msg.stackContent)
      goto(AwaitingStackCreatedResponse) using FirstTimeStack(msg.newStackName)

    case Event(msg: NewStackUpgradeLaunchCommand, Uninitialized) =>
      val stackCreator = actorFactory(StackCreator, context, "stackLauncher", credentials)
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

      val stackCreateMonitor = actorFactory(StackCreateCompleteMonitor, context, "createCompleteMonitor", credentials, data.newStackName)
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
          val asgFetcher = actorFactory(StackInfo, context, "getASGName", credentials)
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

      val freezeNewASG = actorFactory(FreezeASG, context, "freezeASG", credentials)
      context.watch(freezeNewASG)
      freezeNewASG ! FreezeASGCommand(msg.asgName)
      goto(AwaitingFreezeASGResponse) using UpgradeOldStackDataWithASG(data.oldStackASG, data.oldStackName, data.newStackName, msg.asgName)
  }

  when(AwaitingFreezeASGResponse) {
    case Event(msg: FreezeASGCompleted, UpgradeOldStackDataWithASG(oldAsgName, _, _, _)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage("New ASG Frozen, Querying old stack size")

      val oldAsgSizeQuery = actorFactory(ASGSize, context, "oldASGSize", credentials)
      context.watch(oldAsgSizeQuery)
      oldAsgSizeQuery ! ASGDesiredSizeQuery(oldAsgName)
      goto(AwaitingASGDesiredSizeResult)
  }

  when(AwaitingASGDesiredSizeResult) {
    case Event(ASGDesiredSizeResult(size), UpgradeOldStackDataWithASG(oldStackASG, oldStackName, newStackName, newAsgName)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"Old ASG desired instances: $size, setting new ASG to $size desired instances")

      val resizeASG = actorFactory(ASGSize, context, "asgResize", credentials)
      context.watch(resizeASG)
      resizeASG ! ASGSetDesiredSizeCommand(newAsgName, size)
      goto(AwaitingASGDesiredSizeSetResponse) using UpgradeOldStackDataWithASGAndSize(oldStackASG, oldStackName, newStackName, newAsgName, size)
  }

  when(AwaitingASGDesiredSizeSetResponse) {
    case Event(ASGSetDesiredSizeRequested, UpgradeOldStackDataWithASGAndSize(_, _, _, newAsgName, size)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG Desired size has been set, querying ASG for ELB list and attached instance IDs")

      val asgELBDetailQuery = actorFactory(HealthyInstanceSupervisor, context, "healthyInstanceSupervisor", credentials, size, newAsgName, actorFactory)
      context.watch(asgELBDetailQuery)
      asgELBDetailQuery ! CheckHealth
      goto(AwaitingHealthyNewASG)
  }

  when(AwaitingHealthyNewASG) {
    case Event(HealthStatusMet, UpgradeOldStackDataWithASGAndSize(_, _, _, newAsgName, _)) =>
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
      context.parent ! WorkflowManager.StepFailed("Failed to launch new stack")
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

object NewStackSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait NewStackMessage
  case class NewStackFirstLaunchCommand(newStackName: String, imageId: String, version: String,
                                        stackContent: JsValue) extends NewStackMessage
  case class NewStackUpgradeLaunchCommand(newStackName: String, imageId: String, version: String, stackContent: JsValue,
                                          oldStackName: String, oldStackASG: String) extends NewStackMessage
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
  case class UpgradeOldStackData(oldStackASG: String, oldStackName: String, newStackName: String) extends NewStackData
                                                                                                          with FirstStepData
  case class UpgradeOldStackDataWithASG(oldStackASG: String, oldStackName: String, newStackName: String,
                                        newStackASG: String) extends NewStackData
  case class UpgradeOldStackDataWithASGAndSize(oldStackASG: String, oldStackName: String, newStackName: String,
                                               newStackASG: String, oldASGSize: Int) extends NewStackData

  override def props(args: Any*): Props = Props(classOf[NewStackSupervisor], args: _*)
}
