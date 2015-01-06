package actors.workflow.steps.validateandfreeze

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws.AWSWorkflow.StartStep
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import actors.workflow.steps.validateandfreeze.FreezeASG.{ASGFrozen, FreezeASGWithName}
import actors.workflow.steps.validateandfreeze.GetASGName.{ASGForStack, GetASGNameForStackName}
import actors.workflow.steps.validateandfreeze.StackList.{FilteredStacks, ListNonDeletedStacksStartingWithName}
import actors.workflow.steps.validateandfreeze.ValidateAndFreezeSupervisor.MoreThanOneActiveStack
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class ValidateAndFreezeSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {
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
        case i if i > 1 => context.parent ! MoreThanOneActiveStack
        case 1 =>
          val asgFetcher = context.actorOf(GetASGName.props(credentials), "getASGName")
          context.watch(asgFetcher)
          asgFetcher ! GetASGNameForStackName(x.stackList(0))
        case 0 =>
          context.parent ! LogMessage("No current stack for given name found")
      }

    case x: ASGForStack =>
      context.unwatch(sender())
      context.stop(sender())

      val asgFreezer = context.actorOf(FreezeASG.props(credentials), "freezeASG")
      context.watch(asgFreezer)

      asgFreezer ! FreezeASGWithName(x.asgName)
      context.parent ! LogMessage(s"Requesting to freeze ${x.asgName}")

    case x: ASGFrozen =>
      context.parent ! LogMessage(s"ASG ${x.asgName} has been frozen for deployment")

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object ValidateAndFreezeSupervisor {

  case object MoreThanOneActiveStack

  def props(credentials: AWSCredentials): Props = Props(new ValidateAndFreezeSupervisor(credentials))
}