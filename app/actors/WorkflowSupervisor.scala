package actors

import actors.DeploymentSupervisor.Deploy
import akka.actor.{Actor, ActorLogging}

class WorkflowSupervisor extends Actor with ActorLogging {

  var deployment:Deploy = null

  override def receive: Receive = {
    case deploy: Deploy => {
      deployment = deploy

      log.debug("Ok, lets do this deploy!")
    }
  }
}
