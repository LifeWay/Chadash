package actors.workflow.aws

import actors.WorkflowStatus.DeployStatusSubscribeConfirm
import akka.actor._

class WorkflowStatusWebSocket(out: ActorRef, workflowStatus: ActorRef) extends Actor with ActorLogging {

  import actors.workflow.aws.WorkflowStatusWebSocket._

  workflowStatus ! DeployStatusSubscribeConfirm
  context.watch(workflowStatus)

  override def receive: Receive = {
    case msg: String =>
      out ! "This is a one-way websocket. I do not accept messages!"

    case x: MessageToClient =>
      out ! (x.msg + "\n")

    case x: Terminated =>
      x.getActor == workflowStatus
      self ! PoisonPill
  }
}

object WorkflowStatusWebSocket {

  case class MessageToClient(msg: String)

  def props(out: ActorRef, workflowStatus: ActorRef) = Props(new WorkflowStatusWebSocket(out, workflowStatus))
}
