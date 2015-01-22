package actors

import actors.workflow.aws.WorkflowStatusWebSocket.MessageToClient
import akka.actor._

/**
 * This actor is intended to be created by your workflow supervisor. Your supervisor will manage this as one of its
 * children, and send any log data to it based on the actions you take inside of your supervisor.
 *
 * @param totalSteps
 */
class WorkflowStatus(val totalSteps: Int) extends Actor with ActorLogging {

  import actors.WorkflowStatus._

  var stepsCompleted: Int = 0
  var logs = Seq.empty[String]
  var subscribers = Seq.empty[ActorRef]

  override def receive: Receive = {
    case x: LogMessage => logger(x.message)
    case x: ItemFinished =>
      logger(x.message)
      stepsCompleted = stepsCompleted + 1

    case GetStatus =>
      sender() ! Status(stepsCompleted / totalSteps, logs)

    case x: DeployStatusSubscribeRequest =>
      sender() ! SubscribeToMe(self)

    case DeployStatusSubscribeConfirm =>
      subscribers = subscribers :+ sender()
      context.watch(sender())
      logs.map(x => sender() ! MessageToClient(x))

    case Terminated(actorRef) =>
      context.unwatch(actorRef)
      subscribers = subscribers.filter(p => !p.equals(actorRef))
  }

  def logger(msg: String): Unit = {
    logs = logs :+ msg
    log.debug(msg)
    subscribers.map(_ ! MessageToClient(msg))
  }
}


object WorkflowStatus {

  sealed trait Log {
    def message: String
  }

  case class LogMessage(message: String) extends Log

  case class ItemFinished(message: String) extends Log

  case object GetStatus

  case class Status(percentComplete: Float, logMessages: Seq[String])

  case class SubscribeToMe(ref: ActorRef)

  case class DeployStatusSubscribeRequest(stackPath: String)

  case object DeployStatusSubscribeConfirm

  def props(totalSteps: Int): Props = Props(new WorkflowStatus(totalSteps))
}
