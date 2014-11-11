package actors

import actors.WorkflowStatus.{DeployStatusSubscribeRequest, GetStatus}
import actors.workflow.aws.AWSWorkflow
import actors.workflow.aws.AWSWorkflow.DeployCompleted
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsNumber, JsString, JsValue, Json}

class DeploymentSupervisor extends Actor with ActorLogging {

  import actors.DeploymentSupervisor._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
  }

  override def receive: Receive = {
    case deploy: Deploy =>
      val actorName = s"workflow-${deploy.appName}"
      val appConfig = ConfigFactory.load("deployment").getConfig(deploy.appName)
      val data: JsValue = Json.obj(
        "version" -> JsNumber(deploy.appVersion),
        "name" -> JsString(deploy.appName),
        "imageId" -> JsString(deploy.amiName)
      )

      val deployMessage = AWSWorkflow.Deploy(appConfig, data)

      context.child(actorName) match {
        case Some(x) => x forward deployMessage
        case None => {
          val workflowActor = context.actorOf(Props[AWSWorkflow], actorName)
          context.watch(workflowActor)
          workflowActor forward deployMessage
        }
      }

    case status: DeployStatusQuery =>
      val actorName = s"workflow-${status.appName}"
      context.child(actorName) match {
        case Some(x) => x ! GetStatus
        case None => sender() ! NoWorkflow
      }

    case subscribe: DeployStatusSubscribeRequest =>
      val actorName = s"workflow-${subscribe.appName}"
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

  case class Deploy(appName: String, appVersion: Int, amiName: String, userData: Option[String])

  case class DeployStatusQuery(appName: String)

  case class DeployWorkflow(workflowActor: ActorRef)

  case object Started

  case object WorkflowInProgress

  case object DeployFailed

  case object NoWorkflow

}


