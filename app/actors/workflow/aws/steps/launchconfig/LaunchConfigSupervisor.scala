package actors.workflow.aws.steps.launchconfig

import akka.actor.{Actor, ActorLogging}

class LaunchConfigSupervisor extends Actor with ActorLogging {
  override def receive: Receive = {
    case m: Any => ()
      //TODO: he will fetch and prepare the config needed for requesting the child.
  }
}
