package controllers

import actors.DeploymentSupervisor.{WorkflowInProgress, NoWorkflow}
import actors.WorkflowStatus.SubscribeToMe
import actors.workflow.aws.WorkflowStatusWebSocket
import actors.{ChadashSystem, DeploymentSupervisor, WorkflowStatus}
import akka.pattern.ask
import akka.util.Timeout
import models.Deployment
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsError, _}
import play.api.mvc._
import utils.Authentication

import scala.concurrent.Future
import scala.concurrent.duration._
import com.lifeway.chadash.appversion.BuildInfo

object Application extends Controller {

  val jvmVersion = java.lang.System.getProperty("java.version")
  val jvmVendor = java.lang.System.getProperty("java.vendor")

  def index = Action {
    Ok("Welcome to Chadash. The immutable Cloud Deployer!")
  }

  def deploy(stackName: String) = Action.async(BodyParsers.parse.json) { implicit request =>
    Authentication.checkAuth(stackName) { userId =>
      Logger.info(s"User: $userId is requesting to deploy stack: $stackName.")

      Future.successful(Ok("Word"))
      val res = request.body.validate[Deployment]
      res.fold(
        errors => Future(BadRequest(Json.obj("status" -> "Processing Error", "message" -> JsError.toFlatJson(errors)))),
        deployment => {
          implicit val to = Timeout(Duration(2, "seconds"))
          val f = for (
            res <- ChadashSystem.deploymentSupervisor ? DeploymentSupervisor.Deploy(stackName, deployment.version, deployment.amiId)
          ) yield res

          f.map {
            case x @ WorkflowInProgress => Forbidden(s"$x")
            case x @ _ => Ok(s"$x")
          }
        }
      )
    }
  }

  def statusSocket(appName: String) = {
    WebSocket.tryAcceptWithActor[String, String] { request =>
      implicit val to = Timeout(Duration(2, "seconds"))
      val f = for (
        res <- ChadashSystem.deploymentSupervisor ? WorkflowStatus.DeployStatusSubscribeRequest(appName)
      ) yield res

      f.map {
        case NoWorkflow => Left(NotFound("workflow not found"))
        case x: SubscribeToMe => Right(out => WorkflowStatusWebSocket.props(out, x.ref))
      }
    }
  }

  def buildInfo = Action {
    Ok(
      Json.obj(
        "buildInfo" -> Json.obj(
          "appName" -> BuildInfo.name,
          "version" -> BuildInfo.version,
          "gitCommit" -> BuildInfo.gitCommit,
          "buildTime" -> BuildInfo.buildTime
        ), "systemInfo" -> Json.obj(
          "jvm" -> jvmVersion,
          "java.vendor" -> jvmVendor
        )
      )
    )
  }
}