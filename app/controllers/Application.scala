package controllers

import actors.{ChadashSystem, DeploymentSupervisor}
import akka.pattern.ask
import akka.util.Timeout
import com.lifeway.chadash.appversion.BuildInfo
import models.Deployment
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsError, _}
import play.api.mvc._

import scala.concurrent.Future
import scala.concurrent.duration.Duration

object Application extends Controller {

  val jvmVersion = java.lang.System.getProperty("java.version")
  val jvmVendor = java.lang.System.getProperty("java.vendor")

  def index = Action {
    Ok("Welcome to Chadash. The immutable Cloud Deployer!")
  }

  def deploy(appName: String) = Action.async(BodyParsers.parse.json) { request =>
    val res = request.body.validate[Deployment]
    res.fold(
      errors => Future(BadRequest(Json.obj("status" -> "Processing Error", "message" -> JsError.toFlatJson(errors)))),
      deployment => {
        // TODO: check for authentication

        implicit val to = Timeout(Duration(10, "seconds"))
        val f = for (
          res <- ChadashSystem.deploymentSupervisor ? DeploymentSupervisor.Deploy(appName, deployment.version, deployment.amiId, deployment.userData)
        ) yield res

        f.map(x => Ok(s"$x"))
      }
    )
  }

  def status(appName: String) = Action {
    Ok("fetching status...")
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