package actors

import actors.WorkflowStatus.{DeployStatusSubscribeRequest, GetStatus}
import actors.workflow.aws.AWSWorkflow
import actors.workflow.aws.AWSWorkflow.DeployCompleted
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsNumber, JsString, JsValue, Json}

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
      val data: JsValue = Json.obj(
        "version" -> JsString(deploy.appVersion),
        "name" -> JsString(deploy.stackName),
        "imageId" -> JsString(deploy.amiId)
      )

      context.child(actorName) match {
        case Some(x) => x forward deploy
        case None => {
          val workflowActor = context.actorOf(Props[AWSWorkflow], actorName)
          context.watch(workflowActor)
          workflowActor forward deploy
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


