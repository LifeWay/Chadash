package actors.workflow.aws.steps.elb

import akka.actor.{Props, ActorLogging, Actor}

class ELBSupervisor extends Actor with ActorLogging {
  override def receive: Receive = {
    case m: Any => ()
    //TODO: he will fetch and prepare the config needed for requesting the child.
  }
}

object ELBSupervisor {

  def props(): Props = Props(new ELBSupervisor())
}