package actors.workflow.steps.validateandfreeze

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws.AWSWorkflow.StartStep
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class ValidateAndFreezeSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {
  override def receive: Receive = {
    case step: StartStep =>


      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object ValidateAndFreezeSupervisor {
  def props(credentials: AWSCredentials): Props = Props(new ValidateAndFreezeSupervisor(credentials))
}