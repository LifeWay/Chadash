package actors.workflow.steps

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

class ValidateAndFreezeSupervisor(credentials: AWSCredentials) extends FSM[ValidateAndFreezeStates, ValidateAndFreezeData] with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.ValidateAndFreezeSupervisor._

  startWith(AwaitingValidateAndFreezeCommand, Uninitialized)

  when(AwaitingValidateAndFreezeCommand) {
    case Event(msg: ValidateAndFreezeStackCommand, Uninitialized) =>
      val stackList = context.actorOf(StackList.props(credentials), "stackList")
      context.watch(stackList)
      stackList ! ListNonDeletedStacksStartingWithName(msg.stackName)
      goto(AwaitingFilteredStackResponse)
  }

  when(AwaitingFilteredStackResponse) {
    case Event(msg: FilteredStacks, Uninitialized) =>
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
          context.parent ! LogMessage("One running stack found, querying for the ASG name")
          val asgFetcher = context.actorOf(StackInfo.props(credentials), "getASGName")
          context.watch(asgFetcher)
          val stack = msg.stackList(0)
          asgFetcher ! StackASGNameQuery(stack)
          goto(AwaitingStackASGNameResponse) using ExistingStack(stack)
      }
  }

  when(AwaitingStackASGNameResponse) {
    case Event(msg: StackASGNameResponse, ExistingStack(stackName)) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG found, Requesting to suspend scaling activities for ASG: ${msg.asgName}")

      val asgFreezer = context.actorOf(FreezeASG.props(credentials), "freezeASG")
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
      context.parent ! WorkflowManager.StepFailed("Failed to delete a stack")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object ValidateAndFreezeSupervisor {
  //Interaction Messages
  sealed trait ValidateAndFreezeMessage
  case class ValidateAndFreezeStackCommand(stackName: String) extends ValidateAndFreezeMessage
  case object NoExistingStacksExist extends ValidateAndFreezeMessage
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
  case class ExistingStack(stackName: String) extends ValidateAndFreezeData
  case class ExistingStackAndASG(stackName: String, asgName: String) extends ValidateAndFreezeData

  def props(credentials: AWSCredentials): Props = Props(new ValidateAndFreezeSupervisor(credentials))
}
