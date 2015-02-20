package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.LoadStackSupervisor.{LoadStackData, LoadStackStates}
import actors.workflow.tasks.StackLoader
import actors.workflow.tasks.StackLoader.{LoadStack, StackLoaded}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import play.api.libs.json.JsValue
import utils.{PropFactory, ActorFactory}

class LoadStackSupervisor(credentials: AWSCredentials,
                          actorFactory: ActorFactory) extends FSM[LoadStackStates, LoadStackData] with ActorLogging
                                                              with AWSSupervisorStrategy {

  import actors.workflow.steps.LoadStackSupervisor._

  startWith(AwaitingLoadStackCommand, Uninitialized)

  when(AwaitingLoadStackCommand) {
    case Event(msg: LoadStackCommand, _) =>
      val stackLoaderActor = actorFactory(StackLoader, context, "loadStackFile", credentials, msg.bucketName)
      context.watch(stackLoaderActor)
      stackLoaderActor ! LoadStack(msg.stackPath)
      goto(AwaitingStackData)
  }

  when(AwaitingStackData) {
    case Event(StackLoaded(data), _) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LoadStackResponse(data)
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to load the stack file, see server log.")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
  }

  initialize()
}

object LoadStackSupervisor extends PropFactory {
  //Interaction Messages
  sealed trait LoadStackMessage
  case class LoadStackCommand(bucketName: String, stackPath: String)
  case class LoadStackResponse(stackData: JsValue)

  //FSM: States
  sealed trait LoadStackStates
  case object AwaitingLoadStackCommand extends LoadStackStates
  case object AwaitingStackData extends LoadStackStates

  //FSM: Data
  sealed trait LoadStackData
  case object Uninitialized extends LoadStackData

  override def props(args: Any*): Props = Props(classOf[LoadStackSupervisor], args: _*)
}
