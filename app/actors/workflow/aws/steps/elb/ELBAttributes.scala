package actors.workflow.aws.steps.elb

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model._

import scala.concurrent.duration._

class ELBAttributes(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.ELBAttributes._

  override def receive: Receive = {
    case x: SetELBAttributes => {

      val elbAttributes = new LoadBalancerAttributes()
        .withConnectionSettings(
          new ConnectionSettings()
            .withIdleTimeout(x.idleTimeout))

      x.crossZoneLB match {
        case Some(y) => elbAttributes.setCrossZoneLoadBalancing(
          new CrossZoneLoadBalancing()
            .withEnabled(y)
        )
        case None => elbAttributes.setCrossZoneLoadBalancing(
          new CrossZoneLoadBalancing()
            .withEnabled(false)
        )
      }

      x.connectionDraining match {
        case Some(y) => elbAttributes.setConnectionDraining(
          new ConnectionDraining()
            .withEnabled(true)
            .withTimeout(y))
        case None => elbAttributes.setConnectionDraining(
          new ConnectionDraining()
            .withEnabled(false))
      }
      x.accessLogs match {
        case Some(y) => elbAttributes.setAccessLog(
          new AccessLog()
            .withEmitInterval(y.emitInterval)
            .withEnabled(y.enabled)
            .withS3BucketName(y.bucketName)
            .withS3BucketPrefix(y.bucketPrefix))
        case None => ()
      }

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      awsClient.modifyLoadBalancerAttributes(
        new ModifyLoadBalancerAttributesRequest()
          .withLoadBalancerAttributes(elbAttributes)
          .withLoadBalancerName(x.elbName)
      )

      context.parent ! ELBAttributesModified
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: SetELBAttributes => context.system.scheduler.scheduleOnce(10.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object ELBAttributes {

  case class ELBConnectionDraining(enabled: Boolean = true,
                                   timeout: Int)

  case class ELBAccessLog(emitInterval: Int = 60,
                          enabled: Boolean = true,
                          bucketName: String,
                          bucketPrefix: String
                           )

  case class SetELBAttributes(elbName: String,
                              idleTimeout: Int,
                              crossZoneLB: Option[Boolean] = Some(false),
                              connectionDraining: Option[ELBConnectionDraining] = None,
                              accessLogs: Option[ELBAccessLog] = None
                               )

  case object ELBAttributesModified

  def props(creds: AWSCredentials): Props = Props(new ELBAttributes(creds))
}