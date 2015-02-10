package actors.workflow.aws

import actors.DeploymentSupervisor.DeployFailed
import actors.WorkflowLog.DeployStatusSubscribeConfirm
import actors.workflow.WorkflowManager.DeploySuccessful
import akka.actor._

class WorkflowStatusWebSocket(out: ActorRef, workflowStatus: ActorRef) extends Actor with ActorLogging {

  import actors.workflow.aws.WorkflowStatusWebSocket._

  workflowStatus ! DeployStatusSubscribeConfirm
  context.watch(workflowStatus)
  context.watch(out)

  override def receive: Receive = {
    case msg: String =>
      out ! "This is a one-way websocket. I do not accept messages!"

    case x: MessageToClient =>
      out ! (x.msg + "\n")

    case x: Terminated =>
     if(x.getActor == workflowStatus)
       self ! PoisonPill
  }
}

object WorkflowStatusWebSocket {

  case class MessageToClient(msg: String)

  def props(out: ActorRef, workflowStatus: ActorRef) = Props(new WorkflowStatusWebSocket(out, workflowStatus))
}
