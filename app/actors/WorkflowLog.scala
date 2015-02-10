package actors

import actors.DeploymentSupervisor.DeployFailed
import actors.workflow.WorkflowManager.DeployCompleted
import actors.workflow.aws.WorkflowStatusWebSocket.MessageToClient
import akka.actor._

/**
 * This actor is intended to be created by your workflow supervisor. Your supervisor will manage this as one of its
 * children, and send any log data to it based on the actions you take inside of your supervisor.
 */
class WorkflowLog extends Actor with ActorLogging {

  import actors.WorkflowLog._

  var workflow: Option[ActorRef] = None
  var logs = Seq.empty[String]
  var subscribers = Seq.empty[ActorRef]

  override def receive: Receive = {
    case x: LogMessage =>
      logger(x.message)

    case x: DeployStatusSubscribeRequest =>
      sender() ! SubscribeToMe(self)

    case WatchMePlease =>
      workflow = Some(sender())
      context.watch(sender())

    case DeployStatusSubscribeConfirm =>
      subscribers = subscribers :+ sender()
      context.watch(sender())
      logs.map(sender() ! MessageToClient(_))

    case DeployCompleted =>
      subscribers.map(_ forward DeployCompleted)

    case DeployFailed =>
      subscribers.map(_ forward DeployFailed)

    case Terminated(actorRef) =>
      for(x <- workflow) yield if(x == actorRef) {
        subscribers.map(_ ! WorkflowFailed)
      } else {
        subscribers = subscribers.filter(p => !p.equals(actorRef))
      }
  }

  def logger(msg: String): Unit = {
    logs = logs :+ msg
    log.debug(msg)
    subscribers.map(_ ! MessageToClient(msg))
  }
}


object WorkflowLog {

  sealed trait Log {
    def message: String
  }

  case class LogMessage(message: String) extends Log

  case class SubscribeToMe(ref: ActorRef)

  case object WatchMePlease

  case class DeployStatusSubscribeRequest(stackPath: String, appVersion: String)

  case object WorkflowFailed

  case object DeployStatusSubscribeConfirm

  def props(): Props = Props(new WorkflowLog)
}
