package actors


import actors.WorkflowLog.DeployStatusSubscribeRequest
import actors.workflow.WorkflowManager
import actors.workflow.WorkflowManager.{DeployCompleted, StackDeleteCompleted, StartDeploy}
import actors.workflow.steps.DeleteStackSupervisor.DeleteExistingStack
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
      val actorName = stackNameBuilder(deploy)
      context.child(actorName) match {
        case Some(x) =>
          sender ! WorkflowInProgress
        case None =>
          val deploymentLog = context.actorOf(WorkflowLog.props(), logNameBuilder(deploy))
          val workflowActor = context.actorOf(WorkflowManager.props(deploymentLog), actorName)
          context.watch(workflowActor)
          workflowActor forward StartDeploy(deploy)
      }

    case msg: DeleteStack =>
      val stackName = stackNameBuilder(msg.stackPath, msg.appVersion)
      context.child(stackName) match {
        case Some(x) =>
          sender ! WorkflowInProgress
        case None => {
          val deploymentLog = context.actorOf(WorkflowLog.props(), logNameBuilder(msg.stackPath, msg.appVersion))
          val workflowActor = context.actorOf(WorkflowManager.props(deploymentLog), stackName)
          context.watch(workflowActor)
          workflowActor forward DeleteExistingStack(stackName)
        }
      }

    case subscribe: DeployStatusSubscribeRequest =>
      val actorName = stackNameBuilder(subscribe.stackPath, subscribe.appVersion)
      context.child(actorName) match {
        case Some(x) => x forward subscribe
        case None => sender() ! NoWorkflow
      }

    case DeployCompleted =>
      context.unwatch(sender())
      context.stop(sender())

    case StackDeleteCompleted =>
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

  case class Deploy(stackPath: String, appVersion: String, amiId: String)

  case class DeployStatusQuery(stackPath: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case class DeleteStack(stackPath: String, appVersion: String)

  case object Started

  case object WorkflowInProgress

  case object DeployFailed

  case object NoWorkflow
  
  val awsStackNamePattern = "[^\\w-]".r

  def stackNameBuilder(stackPath: String, version: String): String = awsStackNamePattern.replaceAllIn(s"chadash-$stackPath-v$version", "-")

  def stackNameBuilder(deploy: Deploy): String = stackNameBuilder(deploy.stackPath, deploy.appVersion)

  def logNameBuilder(stackPath: String, version: String): String = s"logs-${stackNameBuilder(stackPath, version)}"

  def logNameBuilder(deploy: Deploy): String = logNameBuilder(deploy.stackPath, deploy.appVersion)
}


