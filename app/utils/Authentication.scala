package utils

import org.apache.commons.codec.binary.Base64.decodeBase64
import play.api.Play.current
import play.api.libs.json.{JsNumber, JsObject, JsString}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.{Configuration, Logger, Play}

import scala.concurrent.Future

object Authentication {
  def checkAuth(env: String, stackName: String)(callback: (String) => Future[Result], notAuthResponse: Result = buildNotAuthorizedResponse())(implicit request: Request[Any]): Future[Result] = {
    val basicAuthUser = getUser(request)
    basicAuthUser match {
      case Some(user) => checkUserRoute(callback, notAuthResponse, user._1, user._2, env, stackName)
      case None => Future.successful(notAuthResponse)
    }
  }

  def checkUserRoute(callback: (String) => Future[Result], notAuthResponse: Result = buildNotAuthorizedResponse(), user: String, pw: String, env: String, stack: String): Future[Result] = {
    val authConfig = Play.configuration.getConfig("auth")
    authConfig match {
      case Some(authConfig) =>
        authConfig.getConfig(user) match {
          case Some(userPerms) =>
            userAuth(userPerms, user, pw) match {
              case true =>
                val adminUser = adminStatus(userPerms)
                adminUser match {
                  case true => callback(user)
                  case false =>
                    stackAuth(userPerms, env, stack) match {
                      case true => callback(user)
                      case false => Future.successful(notAuthResponse)
                    }
                }
              case false => Future.successful(notAuthResponse)
            }
          case None => Future.successful(notAuthResponse)
        }

      case None =>
        Logger.error("Missing auth block in configuration.")
        Future.successful(notAuthResponse)
    }
  }

  def stackAuth(userPerms: Configuration, env: String, stackName: String): Boolean = {
    userPerms.getStringList(env) match {
      case Some(list) => list.contains("*") || list.contains(stackName)
      case None => false
    }
  }

  def adminStatus(userPerms: Configuration): Boolean = {
    userPerms.getBoolean("isAdmin") match {
      case Some(admin) => admin
      case None => false
    }
  }

  def userAuth(userPerms: Configuration, user: String, pw: String): Boolean = {
    userPerms.getString("password") match {
      case Some(configPw) => configPw == pw
      case None => false
    }
  }

  /**
   * Credit goes to Natalino Busa for getting the authorization header.
   *
   * twitter.com/natalinobusa
   * linkedin.com/in/natalinobusa
   * www.natalinobusa.com
   */
  def getUser(request: RequestHeader): Option[(String, String)] = {
    request.headers.get("Authorization").flatMap { authorization =>
      authorization.split(" ").drop(1).headOption.flatMap { encoded =>
        new String(decodeBase64(encoded.getBytes)).split(":").toList match {
          case c :: s :: Nil => Some(c, s)
          case _ => None
        }
      }
    }
  }

  def buildNotAuthorizedResponse(): Result = {
    Unauthorized(JsObject(Seq(
      "status" -> JsNumber(401),
      "api-message" -> JsString("Not Authorized")
    )))
  }
}
