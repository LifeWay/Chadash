package actors

import java.io.File

import actors.UserCredentialsLoader.{DeployConfigData, DeployConfigStates}
import akka.actor.{ActorLogging, FSM, Props}
import com.typesafe.config.{Config, ConfigFactory}
import utils.PropFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Responsible for loading in the user config for who has access to deploy what.
 *
 * This config is separated out from the app's config because user config may be changed by an external
 * configuration mgmt system (i.e. puppet / chef). Further - we need that user config to be reloaded into
 * Chadash on a semi-frequent basis, but we can't restart chadash to load that data in as it could
 * cause issues for an existing deployment. This actor will both load the config data on a repeating basis
 * while also providing a way to get the current config via a message.
 *
 */
class UserCredentialsLoader extends FSM[DeployConfigStates, DeployConfigData] with ActorLogging {

  import actors.UserCredentialsLoader._

  startWith(ConfigNotLoaded, NoData)

  override def preStart() = {
    context.system.scheduler.schedule(1.second, 2.minutes, self, ConfigReload)
  }

  when(ConfigNotLoaded) {
    case Event(ConfigReload, _) =>
      goto(ConfigLoaded) using ConfigData(loadConfig())
    case Event(GetConfig, _) =>
      val userConfig = loadConfig()
      sender() ! UserConfig(userConfig)
      goto(ConfigLoaded) using ConfigData(userConfig)
  }

  when(ConfigLoaded) {
    case Event(ConfigReload, _) =>
      stay using ConfigData(loadConfig())
    case Event(GetConfig, ConfigData(config)) =>
      sender() ! UserConfig(config)
      stay()
  }

  initialize()

  def loadConfig(): Config = {
    val appConfig = ConfigFactory.load()
    val resourceAuthConfig = appConfig.getConfig("auth")
    if (appConfig.hasPath("authconfig-path")) {
      ConfigFactory.parseFile(new File(appConfig.getString("authconfig-path"))).withFallback(resourceAuthConfig)
    } else {
      resourceAuthConfig
    }
  }
}

object UserCredentialsLoader extends PropFactory {
  sealed trait DeployConfigMessage
  case object GetConfig extends DeployConfigMessage
  case class UserConfig(config: Config) extends DeployConfigMessage
  case object ConfigReload extends DeployConfigMessage

  sealed trait DeployConfigStates
  case object ConfigNotLoaded extends DeployConfigStates
  case object ConfigLoaded extends DeployConfigStates

  sealed trait DeployConfigData
  case object NoData extends DeployConfigData
  case class ConfigData(config: Config) extends DeployConfigData

  override def props(args: Any*): Props = Props(classOf[UserCredentialsLoader], args: _*)
}
