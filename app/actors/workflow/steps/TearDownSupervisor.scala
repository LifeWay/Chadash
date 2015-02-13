package actors.workflow.steps

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeletedResponse}
import actors.workflow.tasks.StackDeleteCompleteMonitor.StackDeleteCompleted
import actors.workflow.tasks.StackInfo.{StackIdQuery, StackIdResponse}
import actors.workflow.tasks.UnfreezeASG.{UnfreezeASGCommand, UnfreezeASGCompleted}
import actors.workflow.tasks.{DeleteStack, StackDeleteCompleteMonitor, StackInfo, UnfreezeASG}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class TearDownSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.TearDownSupervisor._

  var newAsgName = ""
  var oldStackName = ""
  var oldStackId = ""

  override def receive: Receive = {
    case msg: StartTearDown =>
      newAsgName = msg.newStackASG
      oldStackName = msg.oldStackName

      val stackInfo = context.actorOf(StackInfo.props(credentials), "stackInfo")
      context.watch(stackInfo)
      stackInfo ! StackIdQuery(msg.oldStackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case msg: StackIdResponse =>
      oldStackId = msg.stackId

      context.parent ! LogMessage(s"Deleting old stack: ${oldStackName}")
      val deleteStack = context.actorOf(DeleteStack.props(credentials), "stackDeleter")
      context.watch(deleteStack)
      deleteStack ! DeleteStackCommand(oldStackName)
      context.become(stepInProcess)

    case msg: StackDeletedResponse =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old stack has been requested to be deleted. Monitoring delete progress")
      val stackDeleteMonitor = context.actorOf(StackDeleteCompleteMonitor.props(credentials, oldStackId, oldStackName))
      context.watch(stackDeleteMonitor)

    case msg: StackDeleteCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"Old stack has reached DELETE_COMPLETE status. Resuming all scaling activities on new stack")
      val asgResume = context.actorOf(UnfreezeASG.props(credentials), "asgResume")
      context.watch(asgResume)
      asgResume ! UnfreezeASGCommand(newAsgName)

    case msg: UnfreezeASGCompleted =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"New ASG scaling activities have been resumed: ${msg.asgName}")
      context.parent ! TearDownFinished

    case msg: LogMessage =>
      context.parent forward (msg)

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! WorkflowManager.StepFailed("Failed to tear down the old stack and unfreeze the new one. See server log for details.")
  }
}

object TearDownSupervisor {

  case class StartTearDown(oldStackName: String, newStackASG: String)

  case object TearDownFinished

  def props(credentials: AWSCredentials): Props = Props(new TearDownSupervisor(credentials))
}