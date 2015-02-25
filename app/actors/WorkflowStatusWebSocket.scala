package actors

import actors.WorkflowLog._
import akka.actor._
import play.api.libs.json.Json

class WorkflowStatusWebSocket(out: ActorRef, workflowLog: ActorRef) extends Actor with ActorLogging {

  import actors.WorkflowStatusWebSocket._

  workflowLog ! DeployStatusSubscribeConfirm
  context.watch(workflowLog)

  override def receive: Receive = {
    case msg: String =>
      logSender("This is a one-way websocket. I do not accept messages!", Fail)

    case x: Log =>
      x match {
        case LogMessage(logMessage) => logSender(logMessage, Log)
        case msg @ WorkflowFailed =>
          logSender(msg.message, Fail)
          self ! PoisonPill
        case msg @ WorkflowCompleted =>
          logSender(msg.message, Success)
          self ! PoisonPill
      }

    case Terminated(actor) =>
      logSender("LoggerDied: If this is not expected, this is an error state.", Fail)
      context.stop(self)
  }

  def logSender(msg: String, msgType: MsgType) = {
    out ! Json.obj("type" -> msgType.name, "message" -> msg)
  }
}

object WorkflowStatusWebSocket {

  sealed trait MsgType {
    val name: String
  }
  case object Success extends MsgType {
    val name = "Success"
  }
  case object Fail extends MsgType {
    val name = "Fail"
  }
  case object Log extends MsgType {
    val name = "Log"
  }


  def props(out: ActorRef, workflowStatus: ActorRef) = Props(new WorkflowStatusWebSocket(out, workflowStatus))
}
