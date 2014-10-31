package actors

import actors.workflow.aws.WorkflowStatusWebSocket.MessageToClient
import akka.actor.{Actor, ActorLogging, ActorRef, Props}

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
    case x: LogMessage => logger(x.msg)
    case x: ItemFinished => {
      logger(x.msg)
      stepsCompleted = stepsCompleted + 1
    }
    case GetStatus => {
      sender() ! Status(stepsCompleted / totalSteps, logs)
    }
    case x: DeployStatusSubscribeRequest => {
      sender() ! SubscribeToMe(self)
    }
    case DeployStatusSubscribeConfirm => {
      subscribers = subscribers :+ sender()
      //Catch the subscriber up to current state
      logs.map(x => sender() ! MessageToClient(x))
    }
  }

  def logger(msg: String): Unit = {
    logs = logs :+ msg
    log.debug(msg)
    subscribers.map(_ ! MessageToClient(msg))
  }
}

object WorkflowStatus {

  case class LogMessage(msg: String)

  case class ItemFinished(msg: String)

  case object GetStatus

  case class Status(percentComplete: Float, logMessages: Seq[String])

  case class SubscribeToMe(ref: ActorRef)

  case class DeployStatusSubscribeRequest(appName: String)

  case object DeployStatusSubscribeConfirm

  def props(totalSteps: Int): Props = Props(new WorkflowStatus(totalSteps))
}
