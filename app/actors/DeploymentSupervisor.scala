package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

class DeploymentSupervisor extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  override def receive: Receive = {
    case deploy: Deploy => {
      val actorName = s"workflow-${deploy.appName}"
      context.child(actorName) match {
        case Some(x) => x forward deploy
        case None => context.actorOf(Props[WorkflowSupervisor], actorName) forward deploy
      }
    }
    case status: DeployStatusQuery => {
      sender() ! "Gotta do some digging..."
    }
    case DeployFailed => {
      log.error("Deployment failed for this workflow:" + sender().toString())
    }
  }
}

object DeploymentSupervisor {

  case class Deploy(appName: String, amiName: String, userData: Option[String])

  case class DeployStatusQuery(appName: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case object Started

  case object WorkflowInProgress

  case object DeployFailed

}


