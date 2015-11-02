package controllers

import actors.DeploymentSupervisor.{NoWorkflow, WorkflowInProgress}
import actors.WorkflowLog.SubscribeToMe
import actors.WorkflowStatusEventStream.{FeedEnumerator, StartFeed}
import actors._
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import models.{DeleteStack, Deployment}
import play.api.Logger
import play.api.Play.current
import play.api.libs.EventSource
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsError, _}
import play.api.mvc._
import utils.Authentication
import com.lifeway.chadash.appversion.BuildInfo

import scala.concurrent.Future
import scala.concurrent.duration._

class Application @Inject()(deploymentActor: DeploymentActor) extends Controller {

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

  def statusSocket(appName: String, versionString: String) = {
    WebSocket.tryAcceptWithActor[String, JsValue] { request =>
      implicit val to = Timeout(Duration(2, "seconds"))
      val version = DeploymentSupervisor.buildVersion(versionString)
      val f = for (
        res <- deploymentActor.actor ? WorkflowLog.DeployStatusSubscribeRequest(appName, version)
      ) yield res

      f.map {
        case NoWorkflow => Left(NotFound("workflow not found"))
        case x: SubscribeToMe => Right(out => WorkflowStatusWebSocket.props(out, x.ref))
      }
    }
  }

  def statusEvents(appName: String, versionString: String) = Action.async {
    implicit val to = Timeout(2.seconds)
    //It's very important that each request gets its own WorkflowStatusEventStream actor - no sharing the enumerator!
    val version = DeploymentSupervisor.buildVersion(versionString)
    val f = for {
      logSubscribeToMe <- (deploymentActor.actor ? WorkflowLog.DeployStatusSubscribeRequest(appName, version)).mapTo[SubscribeToMe]
      eventStreamRef <- Future[ActorRef](ChadashSystem.system.actorOf(WorkflowStatusEventStream.props(logSubscribeToMe.ref)))
      eventStreamEnumerator <- (eventStreamRef ? WorkflowStatusEventStream.FeedRequest).mapTo[FeedEnumerator]
    } yield (eventStreamRef, eventStreamEnumerator)

    f.map { x =>
      val (eventStreamRef, enumerator) = x
      eventStreamRef ! StartFeed
      Ok.feed(enumerator.enumerator &> EventSource()).as(EVENT_STREAM)
    }.recover {
      case _ => NotFound("workflow not found")
    }
  }

  def index = Action {
    Ok("Welcome to Chadash. The immutable Cloud Deployer!")
  }

  def healthCheck = Action {
    Ok("API is up, backend status unknown.")
  }

  def buildInfo = Action {
    import controllers.Application._

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
object Application {
  val jvmVersion = java.lang.System.getProperty("java.version")
  val jvmVendor  = java.lang.System.getProperty("java.vendor")
}