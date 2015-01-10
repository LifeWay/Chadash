package actors

import actors.WorkflowStatus.{DeployStatusSubscribeRequest, GetStatus}
import actors.workflow.WorkflowManager
import actors.workflow.WorkflowManager.{DeployCompleted, StartDeploy}
import akka.actor.SupervisorStrategy.Stop
import akka.actor._

/**
 * The deployment supervisor is responsible for the mgmt of the actor hierarchies on a per stackname
 * basis. This supervisor will create a new AWSWorkflow supervisor per each stack deployment and monitor
 * that deployments progress through all of the steps.
 *
 * As long as deployment is running on a stack, you can query the status of the deployment, etc.
 *
 * In general, this is the "window" into the deployment from which the controllers send their commands and queries
 * from the HTTP requests.
 */
class DeploymentSupervisor extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
  }

  override def receive: Receive = {
    case deploy: Deploy =>
      val actorName = s"workflow-${deploy.env}-${deploy.stackName}"
      context.child(actorName) match {
        case Some(x) =>
          sender ! WorkflowInProgress
        case None => {
          val workflowActor = context.actorOf(WorkflowManager.props(deploy), actorName)
          context.watch(workflowActor)
          workflowActor forward StartDeploy
        }
      }

    case status: DeployStatusQuery =>
      val actorName = s"workflow-${status.env}-${status.stackName}"
      context.child(actorName) match {
        case Some(x) => x ! GetStatus
        case None => sender() ! NoWorkflow
      }

    case subscribe: DeployStatusSubscribeRequest =>
      val actorName = s"workflow-${subscribe.env}-${subscribe.stackName}"
      context.child(actorName) match {
        case Some(x) => x forward subscribe
        case None => sender() ! NoWorkflow
      }

    case DeployCompleted =>
      context.unwatch(sender())
      context.stop(sender())

    case DeployFailed =>
      log.error("Deployment failed for this workflow:" + sender().toString())
      context.unwatch(sender())
      context.stop(sender())

    case Terminated(actorRef) =>
      log.error(s"One of our workflows has died...the deployment has failed and needs a human ${actorRef.toString}")

  }
}

object DeploymentSupervisor {

  case class Deploy(env: String, stackName: String, appVersion: String, amiId: String)

  case class DeployStatusQuery(env: String, stackName: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case object Started

  case object WorkflowInProgress

  case object DeployFailed

  case object NoWorkflow

}


