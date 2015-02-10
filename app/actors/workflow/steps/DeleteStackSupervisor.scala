package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeletedResponse}
import actors.workflow.tasks.StackDeleteCompleteMonitor.StackDeleteCompleted
import actors.workflow.tasks.StackInfo.{StackIdQuery, StackIdResponse}
import actors.workflow.tasks.{DeleteStack, StackDeleteCompleteMonitor, StackInfo}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class DeleteStackSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.DeleteStackSupervisor._

  var stackName = ""
  var stackId = ""

  override def receive: Receive = {
    case msg: DeleteExistingStack =>

      stackName = msg.stackName
      val stackInfo = context.actorOf(StackInfo.props(credentials), "stackInfo")
      context.watch(stackInfo)
      stackInfo ! StackIdQuery(msg.stackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case msg: StackIdResponse =>
      stackId = msg.stackId

      context.parent ! LogMessage(s"Deleting stack: $stackName")
      val deleteStack = context.actorOf(DeleteStack.props(credentials), "stackDeleter")
      context.watch(deleteStack)
      deleteStack ! DeleteStackCommand(stackName)
      context.become(stepInProcess)

    case msg: StackDeletedResponse =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Stack has been requested to be deleted. Monitoring delete progress")
      val stackDeleteMonitor = context.actorOf(StackDeleteCompleteMonitor.props(credentials, stackId, stackName))
      context.watch(stackDeleteMonitor)

    case msg: StackDeleteCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Stack has reached DELETE_COMPLETE status.")
      context.parent ! DeleteExistingStackFinished

    case msg: Log =>
      context.parent forward msg

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! WorkflowManager.StepFailed("Failed to tear down the old stack and unfreeze the new one. See server log for details.")
  }
}

object DeleteStackSupervisor {

  case class DeleteExistingStack(stackName: String)

  case object DeleteExistingStackFinished

  def props(credentials: AWSCredentials): Props = Props(new DeleteStackSupervisor(credentials))
}
