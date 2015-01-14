package utils

import org.apache.commons.codec.binary.Base64.decodeBase64
import play.api.Play.current
import play.api.libs.json.{JsNumber, JsObject, JsString}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.{Configuration, Play}

import scala.concurrent.Future

object Authentication {
  def checkAuth(stackName: String)(callback: (String) => Future[Result], notAuthResponse: Result = buildNotAuthorizedResponse())(implicit request: Request[Any]): Future[Result] = {
    val basicAuthUser = getUser(request)
    basicAuthUser match {
      case Some(user) => checkUserRoute(callback, notAuthResponse, user._1, user._2, stackName)
      case None => Future.successful(notAuthResponse)
    }
  }

  def checkUserRoute(callback: (String) => Future[Result], notAuthResponse: Result = buildNotAuthorizedResponse(), username: String, pw: String, stack: String): Future[Result] = {
    val optionalAccess = for {
      config <- Play.configuration.getConfig("auth")
      userConfig <- config.getConfig(username)
    } yield if (userAuth(userConfig, username, pw) && stackAuth(userConfig, stack)) callback(username) else Future.successful(notAuthResponse)
    optionalAccess.getOrElse(Future.successful(notAuthResponse))
  }

  def stackAuth(userPerms: Configuration, stackName: String): Boolean = {
    userPerms.getStringList("stacks") match {
      case Some(list) => list.contains("*") || list.contains(stackName)
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
