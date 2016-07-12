package actors.workflow

import akka.actor.{Actor, ActorLogging}
import com.amazonaws.auth._
import com.typesafe.config.ConfigFactory
import utils.TypesafeConfigAWSCredentialsProvider

import scala.concurrent.duration._

/**
 * Provides an Akka way to get AWS credentials with refresh support. Internally.
 * this runs as an actor that attempts to reload the credentials from the provider
 * every 30 minutes. If it fails to load them, it will continue to use the
 * credentials if fetched from the last successful call.
 */
class AmazonCredentials extends Actor with ActorLogging {

  import actors.workflow.AmazonCredentials._

  import scala.concurrent.ExecutionContext.Implicits.global

  val tick = context.system.scheduler.schedule(1.minute, 30.minutes, self, Tick)

  override def postStop() = tick.cancel()

  var credentialProvider: Option[AWSCredentialsProvider] = None

  override def receive: Receive = {
    case Tick =>
      loadCreds()

    case Initialize =>
      loadCreds()

    case ForceReload =>
      loadCreds()

    case RequestCredentials =>
      sender() ! (credentialProvider match {
        case Some(x) => CurrentCredentials(x)
        case None => NoCredentials
      })

  }

  def loadCreds(): Unit = {
    try {
      log.debug("Attempting to load / refresh Amazon Credentials")
      val config = ConfigFactory.load()
      val credentialsProvider = config.getString("aws.credentialsProvider")

      val provider: AWSCredentialsProvider = credentialsProvider match {
        case "DefaultAWSCredentialsProviderChain" => new DefaultAWSCredentialsProviderChain()
        case "TypesafeConfigAWSCredentialsProvider" => new TypesafeConfigAWSCredentialsProvider(config)
        case "InstanceProfileCredentialsProvider" => new InstanceProfileCredentialsProvider()
        case "ClasspathPropertiesFileCredentialsProvider" => new ClasspathPropertiesFileCredentialsProvider()
        case "EnvironmentVariableCredentialsProvider" => new EnvironmentVariableCredentialsProvider()
        case "SystemPropertiesCredentialsProvider" => new SystemPropertiesCredentialsProvider()
      }

      credentialProvider = Some(provider)
    } catch {
      case ex: Exception => {
        log.debug("Error loading credentials...leaving them as-is. ", ex)
      }
    }
  }
}

object AmazonCredentials {
  case object Tick
  case object Initialize
  case object RequestCredentials
  case object ForceReload
  case object NoCredentials
  case class CurrentCredentials(credentials: AWSCredentialsProvider)
}
