package actors.workflow.steps.stackloader

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import actors.workflow.tasks.StackLoader
import StackLoader.{LoadStack, StackLoaded}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials

class LoadStackSupervisor(credentials: AWSCredentials, bucketName: String) extends Actor with ActorLogging with AWSSupervisorStrategy {
  override def receive: Receive = {
    case step: StartStep =>

      val stackLoaderActor = context.actorOf(StackLoader.props(credentials, bucketName))
      context.watch(stackLoaderActor)

      stackLoaderActor ! LoadStack(step.env, step.stackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case StackLoaded(x) =>
      context.parent ! LogMessage("StackLoader: Completed")
      context.parent ! StepFinished(Some(x))
      context.unbecome()

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object LoadStackSupervisor {
  def props(credentials: AWSCredentials, bucketName: String): Props = Props(new LoadStackSupervisor(credentials, bucketName))
}