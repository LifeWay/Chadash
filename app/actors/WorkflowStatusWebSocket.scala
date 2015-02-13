package actors

import actors.WorkflowLog._
import akka.actor._


object WorkflowStatusWebSocket {
  def props(out: ActorRef, workflowStatus: ActorRef) = Props(new WorkflowStatusWebSocket(out, workflowStatus))
}

class WorkflowStatusWebSocket(out: ActorRef, workflowLog: ActorRef) extends Actor with ActorLogging {

  workflowLog ! DeployStatusSubscribeConfirm
  context.watch(workflowLog)

  override def receive: Receive = {
    case msg: String =>
      out ! "This is a one-way websocket. I do not accept messages!"

    case x: Log =>
      x match {
        case LogMessage(logMessage) => out ! logMessage + "\n"
        case terminationMessage@(WorkflowFailed | WorkflowSucceeded) =>
          out ! terminationMessage.message
          self ! PoisonPill
      }

    case Terminated(actor) =>
      out ! "LoggerDied: If this is not expected, this is an error state."
      context.stop(self)
  }
}
