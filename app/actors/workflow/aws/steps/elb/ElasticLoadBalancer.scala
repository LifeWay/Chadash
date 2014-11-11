package actors.workflow.aws.steps.elb

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{CreateLoadBalancerRequest, Listener, Tag}

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class ElasticLoadBalancer(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.ElasticLoadBalancer._

  override def receive: Receive = {
    case x: CreateELB =>
      val listeners: Seq[Listener] = x.listeners.foldLeft(Seq.empty[Listener]) {
        (x, i) => {
          val listener = new Listener()
            .withInstancePort(i.instancePort)
            .withInstanceProtocol(i.instanceProtocol)
            .withLoadBalancerPort(i.loadBalancerPort)
            .withProtocol(i.loadBalancerProtocol)

          i.sslCertificateId match {
            case Some(y) => listener.setSSLCertificateId(y)
            case None => ()
          }
          x :+ listener
        }
      }

      val elb = new CreateLoadBalancerRequest()
        .withLoadBalancerName(x.loadBalancerName)
        .withListeners(listeners)
        .withSecurityGroups(x.securityGroups)
        .withSubnets(x.subnets)

      x.tags match {
        case Some(y) => {
          elb.setTags(y.seq.foldLeft(Seq.empty[Tag])((x, i) =>
            x :+ new Tag().withKey(i._1).withValue(i._2)
          ))
        }
        case None => ()
      }

      x.scheme match {
        case Some(y) => elb.setScheme(y)
        case None => ()
      }

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      val elbResult = awsClient.createLoadBalancer(elb)

      context.parent ! ELBCreated(elbResult.getDNSName)
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateELB => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object ElasticLoadBalancer {

  case class ELBListener(instancePort: Int,
                         instanceProtocol: String,
                         loadBalancerPort: Int,
                         loadBalancerProtocol: String,
                         sslCertificateId: Option[String])

  case class CreateELB(loadBalancerName: String,
                       securityGroups: Seq[String],
                       subnets: Seq[String],
                       listeners: Seq[ELBListener],
                       scheme: Option[String] = None,
                       tags: Option[Seq[(String, String)]] = None
                        )

  case class ELBCreated(dnsName: String)

  def props(creds: AWSCredentials): Props = Props(new ElasticLoadBalancer(creds))
}

