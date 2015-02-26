package controllers

import actors.DeploymentSupervisor.{NoWorkflow, WorkflowInProgress}
import actors.WorkflowLog.SubscribeToMe
import actors._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import models.{DeleteStack, Deployment}
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsError, _}
import play.api.mvc._
import utils.Authentication

import scala.concurrent.Future
import scala.concurrent.duration._
import com.lifeway.chadash.appversion.BuildInfo

class Application @Inject()(deploymentActor: DeploymentActor) extends Controller {

  val jvmVersion = java.lang.System.getProperty("java.version")
  val jvmVendor  = java.lang.System.getProperty("java.vendor")

  def deploy(stackPath: String, timeout: Int) = Action.async(BodyParsers.parse.json) { implicit request =>
    Authentication.checkAuth(stackPath) { userId =>
      Logger.info(s"User: $userId is requesting to deploy stack: $stackPath.")
      val res = request.body.validate[Deployment]
      res.fold(
        errors => Future(BadRequest(Json.obj("status" -> "Processing Error", "message" -> JsError.toFlatJson(errors)))),
        deployment => {
          implicit val to = Timeout(Duration(2, "seconds"))
          val f = for (
            res <- deploymentActor.actor ? DeploymentSupervisor.DeployRequest(stackPath, deployment.version, deployment.amiId, timeout)
          ) yield res

          f.map {
            case x @ WorkflowInProgress => Forbidden(s"$x")
            case x @ _ => Ok(s"$x")
          }
        }
      )
    }
  }

  def deleteStack(stackPath: String) = Action.async(BodyParsers.parse.json) { implicit request =>
    Authentication.checkAuth(stackPath) { userId =>
      Logger.warn(s"User: $userId is requesting to delete stack: $stackPath.")
      val res = request.body.validate[DeleteStack]
      res.fold(
        errors => Future(BadRequest(Json.obj("status" -> "Processing Error", "message" -> JsError.toFlatJson(errors)))),
        delete => {
          implicit val to = Timeout(Duration(2, "seconds"))
          val f = for (
            res <- deploymentActor.actor ? DeploymentSupervisor.DeleteStack(stackPath, delete.version)
          ) yield res

          f.map {
            case x @ WorkflowInProgress => Forbidden(s"$x")
            case x @ _ => Ok(s"$x")
          }
        }
      )
    }
  }

  def statusSocket(appName: String, version: String) = {
    WebSocket.tryAcceptWithActor[String, JsValue] { request =>
      implicit val to = Timeout(Duration(2, "seconds"))
      val f = for (
        res <- deploymentActor.actor ? WorkflowLog.DeployStatusSubscribeRequest(appName, version)
      ) yield res

      f.map {
        case NoWorkflow => Left(NotFound("workflow not found"))
        case x: SubscribeToMe => Right(out => WorkflowStatusWebSocket.props(out, x.ref))
      }
    }
  }

  def index = Action {
    Ok("Welcome to Chadash. The immutable Cloud Deployer!")
  }

  def healthCheck = Action {
    Ok("API is up, backend status unknown.")
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