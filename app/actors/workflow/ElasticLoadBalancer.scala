package actors.workflow

import actors.workflow.ElasticLoadBalancer.{CreateELB, ELBCreated}
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model._

import scala.concurrent.duration._

class ElasticLoadBalancer(credentials: AWSCredentials) extends Actor with ActorLogging {
  override def receive: Receive = {
    case x: CreateELB => {

//      val listener = new Listener()
//        .withInstancePort()
//        .withInstanceProtocol()
//        .withLoadBalancerPort()
//        .withProtocol()
//        .withSSLCertificateId()
//
//      val elb = new CreateLoadBalancerRequest()
//        .withLoadBalancerName()
//        .withAvailabilityZones()
//        .withListeners(listener)
//        .withSecurityGroups()
//        .withSubnets()
//        .withTags()
//        .withScheme() //"internal" or don't set it.
//
//      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
//      val elbResult = awsClient.createLoadBalancer(elb)
//
//      //TODO: configure health check
//      val healthCheck = new HealthCheck()
//        .withTarget()
//        .withInterval()
//        .withTimeout()
//        .withHealthyThreshold()
//        .withUnhealthyThreshold()
//
//      val healthCheckRequest = new ConfigureHealthCheckRequest()
//        .withLoadBalancerName()
//        .withHealthCheck(healthCheck)
//
//      val elbAttributes = new LoadBalancerAttributes()
//        .withAccessLog()
//        .withConnectionDraining()
//        .withConnectionSettings()
//        .withCrossZoneLoadBalancing()
//
//      val modifyAttributesRequest = new ModifyLoadBalancerAttributesRequest()
//        .withLoadBalancerName()
//        .withLoadBalancerAttributes()
//
//      awsClient.configureHealthCheck(healthCheckRequest)

      context.parent ! ELBCreated(x.appVersion)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateELB => context.system.scheduler.scheduleOnce(10.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object ElasticLoadBalancer {

  case class CreateELB(appVersion: String)

  case class ELBCreated(appVersion: String)

  def props(creds: AWSCredentials): Props = Props(new ElasticLoadBalancer(creds))
}

