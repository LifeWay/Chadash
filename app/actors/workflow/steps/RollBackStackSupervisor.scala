package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.RollBackStackSupervisor.{RollBackData, RollBackState}
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeleteRequested}
import actors.workflow.tasks.StackDeleteCompleteMonitor.StackDeleteCompleted
import actors.workflow.tasks.StackInfo.StackIdQuery
import actors.workflow.tasks.UnfreezeASG.{UnfreezeASGCommand, UnfreezeASGCompleted}
import actors.workflow.tasks.{DeleteStack, StackDeleteCompleteMonitor, StackInfo, UnfreezeASG}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{ActorLogging, FSM, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import utils.{ActorFactory, PropFactory}

class RollBackStackSupervisor(credentials: AWSCredentials,
                              actorFactory: ActorFactory) extends FSM[RollBackState, RollBackData] with ActorLogging
                                                                  with AWSSupervisorStrategy {

  import actors.workflow.steps.RollBackStackSupervisor._

  startWith(AwaitingRollBackCommand, Uninitialized)

  when(AwaitingRollBackCommand) {
    case Event(msg: RollBackDeleteStackAndUnfreeze, Uninitialized) =>
      val stackInfo = actorFactory(StackInfo, context, "stackInfo", credentials)
      context.watch(stackInfo)
      stackInfo ! StackIdQuery(msg.stackName)
      goto(AwaitingStackIdResponse) using RollBackDataProps(msg.stackName, msg.asgName)
  }

  when(AwaitingStackIdResponse) {
    case Event(msg: StackInfo.StackIdResponse, data: RollBackDataProps) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Deleting stack: ${data.stackName}")
      val deleteStack = actorFactory(DeleteStack, context, "stackDeleter", credentials)
      context.watch(deleteStack)
      deleteStack ! DeleteStackCommand(data.stackName)
      goto(AwaitingStackDeletedResponse) using RollBackDataPropsWithStackId(data.stackName, data.asgName, msg.stackId)

    //This is slightly risky -- if an exception is thrown for any reason other than the stack doesn't exist, we may leave the stack hanging around.
    case Event(Terminated(actorRef), data: RollBackDataProps) =>
      context.parent ! LogMessage(s"The stackInfo actor died -- we are assuming because of an exception because the stack didn't exist. Proceeding to unfreeze existing ASG")
      unfreeze(data.asgName)
      goto(AwaitingASGUnfreezeResponse)
  }

  when(AwaitingStackDeletedResponse) {
    case Event(StackDeleteRequested, data: RollBackDataPropsWithStackId) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Stack has been requested to be deleted. Monitoring delete progress")
      val monitor = actorFactory(StackDeleteCompleteMonitor, context, "stackDeleteMonitor", credentials, data.stackId, data.stackName)
      context.watch(monitor)
      goto(AwaitingStackDeleteCompleted)

    //This is slightly risky -- if an exception is thrown for any reason other than the stack doesn't exist, we may leave the stack hanging around.
    case Event(Terminated(actorRef), data: RollBackDataPropsWithStackId) =>
      context.parent ! LogMessage(s"The stack delete monitor actor died. Need a human to review. -- Proceeding to unfreeze existing ASG")
      unfreeze(data.asgName)
      goto(AwaitingASGUnfreezeResponse)
  }

  when(AwaitingStackDeleteCompleted) {
    case Event(msg: StackDeleteCompleted, data: RollBackDataPropsWithStackId) =>
      context.unwatch(sender())
      context.parent ! LogMessage(s"Stack has reached DELETE_COMPLETE status.")
      unfreeze(data.asgName)
      goto(AwaitingASGUnfreezeResponse)
  }

  when(AwaitingASGUnfreezeResponse) {
    case Event(msg: UnfreezeASGCompleted, _) =>
      context.parent ! LogMessage(s"Old ASG scaling activities have been resumed: ${msg.asgName}")
      context.parent ! RollBackCompleted
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to rollback")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  def unfreeze(asg: String) = {
    val asgResume = actorFactory(UnfreezeASG, context, "asgResume", credentials)
    context.watch(asgResume)
    asgResume ! UnfreezeASGCommand(asg)
    goto(AwaitingASGUnfreezeResponse)
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object RollBackStackSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait RollBackMessage
  case class RollBackDeleteStackAndUnfreeze(stackName: String, asgName: String) extends RollBackMessage
  case object RollBackCompleted extends RollBackMessage
  case object RollBackFailed extends RollBackMessage

  //FSM: States
  sealed trait RollBackState
  case object AwaitingRollBackCommand extends RollBackState
  case object AwaitingStackIdResponse extends RollBackState
  case object AwaitingStackDeletedResponse extends RollBackState
  case object AwaitingStackDeleteCompleted extends RollBackState
  case object AwaitingASGUnfreezeResponse extends RollBackState

  //FSM: Data
  sealed trait RollBackData
  case object Uninitialized extends RollBackData
  case class RollBackDataProps(stackName: String, asgName: String) extends RollBackData
  case class RollBackDataPropsWithStackId(stackName: String, asgName: String, stackId: String) extends RollBackData

  override def props(args: Any*): Props = Props(classOf[RollBackStackSupervisor], args: _*)
}
