package actors


import actors.WorkflowLog.{WorkflowCompleted, ClearLogAndAvoidDeath, DeployStatusSubscribeRequest}
import actors.workflow.WorkflowManager
import actors.workflow.WorkflowManager._
import actors.workflow.steps.DeleteStackSupervisor.DeleteExistingStack
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import utils.ActorFactory

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
class DeploymentSupervisor(actorFactory: ActorFactory) extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
  }

  override def receive: Receive = {
    case deployRequest: DeployRequest =>
      val deploy: Deploy = deployBuilder(deployRequest)
      context.child(deploy.stackName) match {
        case Some(x) =>
          sender ! WorkflowInProgress
        case None =>
          val deploymentLog = getLogActor(deploy)
          val workflowActor = actorFactory(WorkflowManager, context, deploy.stackName, deploymentLog, actorFactory)
          context.watch(workflowActor)
          workflowActor forward StartDeployWorkflow(deploy)
      }

    case msg: DeleteStack =>
      val stackName = stackNameBuilder(msg.stackPath, msg.appVersion)
      context.child(stackName) match {
        case Some(x) =>
          sender ! WorkflowInProgress
        case None => {
          val deploymentLog = getLogActor(msg.stackPath, msg.appVersion)
          val workflowActor = actorFactory(WorkflowManager, context, stackName, deploymentLog, actorFactory)
          context.watch(workflowActor)
          workflowActor forward StartDeleteWorkflow(stackName)
        }
      }

    case subscribe: DeployStatusSubscribeRequest =>
      context.child(logNameBuilder(subscribe.stackPath, subscribe.appVersion)) match {
        case Some(x) => x forward subscribe
        case None => sender() ! NoWorkflow
      }

    case WorkflowManager.WorkflowCompleted =>
      context.unwatch(sender())

    case msg: WorkflowManager.WorkflowFailed =>
      context.unwatch(sender())
      log.error(s"Deployment failed for this workflow: ${sender().path.name}")

    case Terminated(actorRef) =>
      log.error(s"One of our workflow supervisors has died unexpectedly...the deployment has failed and needs a human ${actorRef.toString()}")
  }

  def getLogActor(deploy: Deploy): ActorRef = getLogActor(deploy.stackPath, deploy.appVersion)

  def getLogActor(stackPath: String, appVersion: String): ActorRef = {
    context.child(logNameBuilder(stackPath, appVersion)) match {
      case Some(logActor) =>
        logActor ! ClearLogAndAvoidDeath
        logActor
      case None =>
        context.actorOf(WorkflowLog.props(), logNameBuilder(stackPath, appVersion))
    }
  }
}

object DeploymentSupervisor {

  case class DeployRequest(stackPath: String, appVersion: String, amiId: String)
  case class Deploy(stackPath: String, stackName: String, appVersion: String, amiId: String)
  case class DeployStatusQuery(stackPath: String)
  case class DeployWorkflow(workflowActor: ActorRef)
  case class DeleteStack(stackPath: String, appVersion: String)
  case object WorkflowInProgress
  case object NoWorkflow

  def props(actorFactory: ActorFactory): Props = Props(new DeploymentSupervisor(actorFactory))

  val awsStackNamePattern = "[^\\w-]".r
  def stackNameBuilder(stackPath: String, version: String): String = awsStackNamePattern.replaceAllIn(s"chadash-$stackPath-v$version", "-")

  def stackNameSansVersionBuilder(stackPath: String): String = awsStackNamePattern.replaceAllIn(s"chadash-$stackPath", "-")

  def deployBuilder(deployRequest: DeployRequest): Deploy = Deploy(deployRequest.stackPath, stackNameBuilder(deployRequest.stackPath, deployRequest.appVersion), deployRequest.appVersion, deployRequest.amiId)

  def logNameBuilder(stackPath: String, version: String): String = s"logs-${stackNameBuilder(stackPath, version)}"
}


