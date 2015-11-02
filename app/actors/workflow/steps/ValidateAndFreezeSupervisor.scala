package actors.workflow.steps

import actors.DeploymentSupervisor
import actors.DeploymentSupervisor.Version
import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.WorkflowManager.StepFailed
import actors.workflow.steps.ValidateAndFreezeSupervisor.{ValidateAndFreezeData, ValidateAndFreezeStates}
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse}
import actors.workflow.tasks.StackList.{FilteredStacks, ListNonDeletedStacksStartingWithName}
import actors.workflow.tasks.{FreezeASG, StackInfo, StackList}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import utils.{ActorFactory, PropFactory}

class ValidateAndFreezeSupervisor(credentials: AWSCredentials,
                                  actorFactory: ActorFactory) extends FSM[ValidateAndFreezeStates, ValidateAndFreezeData]
                                                                      with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.ValidateAndFreezeSupervisor._

  startWith(AwaitingValidateAndFreezeCommand, Uninitialized)

  when(AwaitingValidateAndFreezeCommand) {
    case Event(msg: ValidateAndFreezeStackCommand, Uninitialized) =>
      val stackList = actorFactory(StackList, context, "stackList", credentials)
      context.watch(stackList)
      val stackName = DeploymentSupervisor.stackNameSansVersionBuilder(msg.stackPath)
      val stackNameWithVersion = DeploymentSupervisor.stackNameBuilder(msg.stackPath, msg.newVersion)
      stackList ! ListNonDeletedStacksStartingWithName(stackName)
      goto(AwaitingFilteredStackResponse) using NewStackVersion(stackNameWithVersion)
  }

  when(AwaitingFilteredStackResponse) {
    case Event(msg: FilteredStacks, NewStackVersion(newStackName)) =>
      context.unwatch(sender())
      context.stop(sender())

      msg.stackList.length match {
        case i if i > 1 =>
          context.parent ! StepFailed("Error: More than one active version of this stack is running")
          stop()

        case 0 =>
          context.parent ! NoExistingStacksExist
          stop()

        case 1 =>
          val stack = msg.stackList.head
          if(stack == newStackName) {
            context.parent ! LogMessage("Stack version already exists - nothing to deploy")
            context.parent ! StackVersionAlreadyExists
            stop()
          } else {
            context.parent ! LogMessage("One running stack found, querying for the ASG name")
            val asgFetcher = actorFactory(StackInfo, context, "getASGName", credentials)
            context.watch(asgFetcher)

            asgFetcher ! StackASGNameQuery(stack)
            goto(AwaitingStackASGNameResponse) using ExistingStack(stack)
          }
      }
  }

  when(AwaitingStackASGNameResponse) {
    case Event(msg: StackASGNameResponse, ExistingStack(stackName)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG found, Requesting to suspend scaling activities for ASG: ${msg.asgName}")

      val asgFreezer = actorFactory(FreezeASG, context, "freezeASG", credentials)
      context.watch(asgFreezer)
      asgFreezer ! FreezeASGCommand(msg.asgName)
      goto(AwaitingFreezeASGCompleted) using ExistingStackAndASG(stackName, msg.asgName)
  }

  when(AwaitingFreezeASGCompleted) {
    case Event(msg: FreezeASGCompleted, ExistingStackAndASG(stackName, asgName)) =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG: ${msg.asgName} alarm and scheduled based autoscaling has been frozen for deployment")
      context.parent ! VerifiedAndStackFrozen(stackName, asgName)
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to validate stack and freeze old ASG")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object ValidateAndFreezeSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait ValidateAndFreezeMessage
  case class ValidateAndFreezeStackCommand(stackPath: String, newVersion: Version) extends ValidateAndFreezeMessage
  case object NoExistingStacksExist extends ValidateAndFreezeMessage
  case object StackVersionAlreadyExists extends ValidateAndFreezeMessage
  case class VerifiedAndStackFrozen(oldStackName: String, oldASGName: String) extends ValidateAndFreezeMessage

  //FSM: States
  sealed trait ValidateAndFreezeStates
  case object AwaitingValidateAndFreezeCommand extends ValidateAndFreezeStates
  case object AwaitingFilteredStackResponse extends ValidateAndFreezeStates
  case object AwaitingStackASGNameResponse extends ValidateAndFreezeStates
  case object AwaitingFreezeASGCompleted extends ValidateAndFreezeStates

  //FSM: Data
  sealed trait ValidateAndFreezeData
  case object Uninitialized extends ValidateAndFreezeData
  case class NewStackVersion(newStackNameWithVersion: String) extends ValidateAndFreezeData
  case class ExistingStack(stackName: String) extends ValidateAndFreezeData
  case class ExistingStackAndASG(stackName: String, asgName: String) extends ValidateAndFreezeData

  override def props(args: Any*): Props = Props(classOf[ValidateAndFreezeSupervisor], args: _*)
}
