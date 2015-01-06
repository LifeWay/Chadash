package actors.workflow.steps.validateandfreeze

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws.AWSWorkflow.StartStep
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import actors.workflow.steps.validateandfreeze.FreezeASG.{ASGFrozen, FreezeASGWithName}
import actors.workflow.steps.validateandfreeze.GetASGName.{ASGForStack, GetASGNameForStackName}
import actors.workflow.steps.validateandfreeze.StackList.{FilteredStacks, ListNonDeletedStacksStartingWithName}
import actors.workflow.steps.validateandfreeze.ValidateAndFreezeSupervisor.{NoPreviousStack, StackFrozen, MoreThanOneActiveStack}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class ValidateAndFreezeSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  var stackName: Option[String] = None

  override def receive: Receive = {
    case step: StartStep =>
      val stackList = context.actorOf(StackList.props(credentials), "stackList")
      context.watch(stackList)
      stackList ! ListNonDeletedStacksStartingWithName(step.stackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {

    case x: FilteredStacks =>
      context.unwatch(sender())
      context.stop(sender())

      x.stackList.length match {
        case i if i > 1 =>
          context.parent ! LogMessage(s"Error: More than one active version of this stack is running")
          context.parent ! MoreThanOneActiveStack
        case 1 =>
          context.parent ! LogMessage(s"One running stack found, querying for the ASG name")
          val asgFetcher = context.actorOf(GetASGName.props(credentials), "getASGName")
          context.watch(asgFetcher)
          val stack = x.stackList(0)
          stackName = Some(stack)
          asgFetcher ! GetASGNameForStackName(stack)
        case 0 =>
          context.parent ! LogMessage(s"No previous stack found, this is the first deployment of this stack.")
          context.parent ! NoPreviousStack
      }

    case x: ASGForStack =>
      context.unwatch(sender())
      context.stop(sender())

      val asgFreezer = context.actorOf(FreezeASG.props(credentials), "freezeASG")
      context.watch(asgFreezer)

      asgFreezer ! FreezeASGWithName(x.asgName)
      context.parent ! LogMessage(s"ASG found, Requesting to suspend scaling activities ${x.asgName}")

    case x: ASGFrozen =>
      context.unwatch(sender())
      context.stop(sender())

      context.parent ! LogMessage(s"ASG ${x.asgName} has been frozen for deployment")
      stackName match {
        case Some(stack) => context.parent ! StackFrozen(stack, x.asgName)
        case None => throw new Exception("stack name a None when this should not have been possible")
      }

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object ValidateAndFreezeSupervisor {

  case class StackFrozen(stackName: String, asgName: String)

  case object NoPreviousStack

  case object MoreThanOneActiveStack

  def props(credentials: AWSCredentials): Props = Props(new ValidateAndFreezeSupervisor(credentials))
}