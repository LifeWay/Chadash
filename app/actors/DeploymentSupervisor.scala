package actors

import akka.actor.SupervisorStrategy.Stop
import akka.actor._

class DeploymentSupervisor extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
  }

  override def receive: Receive = {
    case deploy: Deploy => {
      val actorName = s"workflow-${deploy.appName}"
      context.child(actorName) match {
        case Some(x) => x forward deploy
        case None => {
          val workflowActor = context.actorOf(Props[WorkflowSupervisor], actorName)
          context.watch(workflowActor)
          workflowActor forward deploy
        }
      }
    }
    case status: DeployStatusQuery => {
      sender() ! "Gotta do some digging..."
    }
    case DeployFailed => {
      log.error("Deployment failed for this workflow:" + sender().toString())
      context.unwatch(sender())
      context.stop(sender())
    }
    case Terminated(actorRef) => {
      log.error(s"One of our workflows has died...the deployment has failed and needs a human ${actorRef.toString}")
    }
  }
}

object DeploymentSupervisor {

  case class Deploy(appName: String, appVersion: String, amiName: String, userData: Option[String])

  case class DeployStatusQuery(appName: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case object Started

  case object WorkflowInProgress

  case object DeployFailed

}


