package utils

import actors.UserCredentialsLoader.UserConfig
import actors.{ChadashSystem, UserCredentialsLoader}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config
import org.apache.commons.codec.binary.Base64.decodeBase64
import play.api.libs.json.{JsNumber, JsObject, JsString}
import play.api.mvc.Results._
import play.api.mvc._
import utils.ConfigHelpers.RichConfig

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Authentication {
  def checkAuth(stackName: String)
               (callback: (String) => Future[Result], notAuthResponse: Result = buildNotAuthorizedResponse())
               (implicit request: Request[Any]): Future[Result] = {
    val basicAuthUser = getUser(request)
    basicAuthUser match {
      case Some(user) => checkUserRoute(callback, notAuthResponse, user._1, user._2, stackName)
      case None => Future.successful(notAuthResponse)
    }
  }

  private def checkUserRoute(callback: (String) => Future[Result],
                             notAuthResponse: Result = buildNotAuthorizedResponse(), username: String, pw: String,
                             stack: String): Future[Result] = {
    implicit val to = Timeout(2.seconds)
    val optionalAccess = for {
      config <- (ChadashSystem.userCredentials ? UserCredentialsLoader.GetConfig).mapTo[UserConfig]
      userConfig <- Future.successful(config.config.getOptConfig(username))
    } yield if(userConfig.isDefined) userAuth(userConfig.get, username, pw) && stackAuth(userConfig.get, stack) else false

    optionalAccess.flatMap { hasAccess =>
      if (hasAccess) callback(username) else Future.successful(notAuthResponse)
    }
  }

  private def stackAuth(userPerms: Config, stackName: String): Boolean = {
    if (userPerms.hasPath("stacks")) {
      val list = userPerms.getStringList("stacks").asScala
      list.contains(stackName) || stackCheck(list.toList, stackName)
    } else {
      false
    }
  }

  private def userAuth(userPerms: Config, user: String, pw: String): Boolean = {
    if (userPerms.hasPath("password"))
      userPerms.getString("password") == pw
    else
      false
  }

  private def stackCheck(strings: List[String], stackName: String): Boolean = {
    val wildcardSeq = strings.par.collect { case s: String if s.contains("*") => s}
    wildcardSeq.exists { x =>
      val testRegex = x.replaceAll("\\*", ".*").r
      stackName match {
        case testRegex(_*) => true
        case _ => false
      }
    }
  }

  /**
   * Credit goes to Natalino Busa for getting the authorization header.
   *
   * twitter.com/natalinobusa
   * linkedin.com/in/natalinobusa
   * www.natalinobusa.com
   */
  private def getUser(request: RequestHeader): Option[(String, String)] = {
    request.headers.get("Authorization").flatMap { authorization =>
      authorization.split(" ").drop(1).headOption.flatMap { encoded =>
        new String(decodeBase64(encoded.getBytes)).split(":").toList match {
          case c :: s :: Nil => Some(c, s)
          case _ => None
        }
      }
    }
  }

  private def buildNotAuthorizedResponse(): Result = {
    Unauthorized(JsObject(Seq(
      "status" -> JsNumber(401),
      "api-message" -> JsString("Not Authorized")
    )))
  }
}
