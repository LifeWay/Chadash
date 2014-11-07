package actors.workflow.aws.steps.elb

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials

import scala.concurrent.duration._

class ELBPolicies(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.ELBPolicies._

  override def receive: Receive = {
    case _ => log.debug("message received")
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: CreateELBPolicies => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object ELBPolicies {

  case class ELBPolicyAttribute(name: String, value: String)

  case class ELBPolicy(policyName: String, policyType: String, policyAttributes: Seq[ELBPolicyAttribute])

  case class CreateELBPolicies(loadBalancerName: String, policies: Seq[ELBPolicy])

  case object ELBPoliciesConfigured

  def props(creds: AWSCredentials): Props = Props(new ELBPolicies(creds))
}
