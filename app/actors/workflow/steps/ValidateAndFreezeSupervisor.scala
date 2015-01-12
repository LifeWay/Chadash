package actors.workflow.steps

import actors.WorkflowStatus.LogMessage
import actors.workflow.WorkflowManager.StepFailed
import actors.workflow.tasks.FreezeASG.{FreezeASGCommand, FreezeASGCompleted}
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse}
import actors.workflow.tasks.StackList.{FilteredStacks, ListNonDeletedStacksStartingWithName}
import actors.workflow.tasks.{StackInfo, FreezeASG, StackList}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class ValidateAndFreezeSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.ValidateAndFreezeSupervisor._

  var oldStackName: Option[String] = None

  override def receive: Receive = {
    case msg: VerifyAndFreezeOldStack =>
      val stackList = context.actorOf(StackList.props(credentials), "stackList")
      context.watch(stackList)
      stackList ! ListNonDeletedStacksStartingWithName(msg.stackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {

    case msg: FilteredStacks =>
      context.unwatch(sender())
      context.stop(sender())

      msg.stackList.length match {
        case i if i > 1 =>
          context.parent ! StepFailed(s"Error: More than one active version of this stack is running")

        case 0 =>
          context.parent ! LogMessage(s"No previous stack found, this is the first deployment of this stack.")
          context.parent ! NoOldStackExists

        case 1 =>
          context.parent ! LogMessage(s"One running stack found, querying for the ASG name")
          val asgFetcher = context.actorOf(StackInfo.props(credentials), "getASGName")
          context.watch(asgFetcher)
          val stack = msg.stackList(0)
          oldStackName = Some(stack)
          asgFetcher ! StackASGNameQuery(stack)
      }

    case msg: StackASGNameResponse =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG found, Requesting to suspend scaling activities ${msg.asgName}")

      val asgFreezer = context.actorOf(FreezeASG.props(credentials), "freezeASG")
      context.watch(asgFreezer)
      asgFreezer ! FreezeASGCommand(msg.asgName)

    case msg: FreezeASGCompleted =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"ASG ${msg.asgName} has been frozen for deployment")

      oldStackName match {
        case Some(stack) => context.parent ! VerifiedAndStackFrozen(stack, msg.asgName)
        case None => throw new Exception("stack name a None when this should not have been possible")
      }

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! WorkflowManager.StepFailed
  }
}

object ValidateAndFreezeSupervisor {

  case class VerifyAndFreezeOldStack(stackName: String)

  case object NoOldStackExists

  case class VerifiedAndStackFrozen(oldStackName: String, oldASGName: String)

  def props(credentials: AWSCredentials): Props = Props(new ValidateAndFreezeSupervisor(credentials))
}