package actors.workflow.steps

import actors.WorkflowStatus.LogMessage
import actors.workflow.tasks.StackLoader
import actors.workflow.tasks.StackLoader.{LoadStack, StackLoaded}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.JsValue

class LoadStackSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.LoadStackSupervisor._

  override def receive: Receive = {
    case msg: LoadStackQuery =>

      val stackLoaderActor = context.actorOf(StackLoader.props(credentials, msg.bucketName))
      context.watch(stackLoaderActor)

      stackLoaderActor ! LoadStack(msg.stackName)
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case StackLoaded(x) =>
      context.parent ! LoadStackResponse(x)
      context.unbecome()

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! WorkflowManager.StepFailed("Failed to load the stack file, see server log.")
  }
}

object LoadStackSupervisor {

  case class LoadStackQuery(bucketName: String, stackName: String)

  case class LoadStackResponse(stackData: JsValue)

  def props(credentials: AWSCredentials): Props = Props(new LoadStackSupervisor(credentials))
}