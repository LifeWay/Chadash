package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

class DeploymentSupervisor extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  var deployments = Map.empty[String, DeployWorkflow]

  override def receive: Receive = {
    case deploy: Deploy => {
      deployments.contains(deploy.appName) match {
        case true => {
          sender() ! AllreadyStarted
        }
        case false => {
          val actr = ChadashSystem.system.actorOf(Props[WorkflowSupervisor], s"workflow-${deploy.appName}")

          actr ! deploy
          deployments += deploy.appName -> DeployWorkflow(actr)
          sender() ! Started
        }
      }
    }
    case status: DeployStatusQuery => {
      sender() ! "Gotta do some digging..."
    }
  }
}

object DeploymentSupervisor {

  case class Deploy(appName: String, amiName: String, userData: Option[String])

  case class DeployStatusQuery(appName: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case object Started

  case object AllreadyStarted
}


