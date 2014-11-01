package actors.workflow.aws.steps.launchconfig

import actors.workflow.aws.AWSWorkflow.StartStep
import akka.actor.{Actor, ActorLogging, Props}

class LaunchConfigSupervisor extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: StartStep => {
      //TODO: compile all the necessary information to start the step (configs, etc.)
      //TODO: create internal workflow for managing this process.
      //TODO: become working.
    }
  }
}

object LaunchConfigSupervisor {

  def props(): Props = Props(new LaunchConfigSupervisor())
}
