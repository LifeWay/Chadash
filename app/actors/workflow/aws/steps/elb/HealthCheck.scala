package actors.workflow.aws.steps.elb

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancingClient, model}

import scala.concurrent.duration._

class HealthCheck(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.HealthCheck._

  override def receive: Receive = {
    case x: CreateELBHealthCheck =>
      val healthCheck = new model.HealthCheck()
        .withTarget(x.healthCheck.urlTarget)
        .withInterval(x.healthCheck.interval)
        .withHealthyThreshold(x.healthCheck.healthyThreshold)
        .withUnhealthyThreshold(x.healthCheck.unhealthyThreshold)
        .withTimeout(x.healthCheck.timeout)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      val result = awsClient.configureHealthCheck(new ConfigureHealthCheckRequest()
        .withHealthCheck(healthCheck)
        .withLoadBalancerName(x.loadBalancerName))

      context.parent ! HealthCheckConfigured

  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateELBHealthCheck => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object HealthCheck {

  case class HealthCheckConfig(urlTarget: String,
                               interval: Int,
                               timeout: Int,
                               healthyThreshold: Int,
                               unhealthyThreshold: Int)

  case class CreateELBHealthCheck(loadBalancerName: String,
                                  healthCheck: HealthCheckConfig
                                   )

  case object HealthCheckConfigured

  def props(creds: AWSCredentials): Props = Props(new HealthCheck(creds))
}