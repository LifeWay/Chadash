package actors.workflow.aws.steps.elb

import actors.workflow.aws.AWSSupervisorStrategy
import actors.workflow.aws.steps.elb.ELBPoliciesSupervisor.ELBPolicyDef
import actors.workflow.aws.steps.elb.ELBPolicy.PolicyCreated
import akka.actor.{Actor, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{CreateLoadBalancerPolicyRequest, PolicyAttribute}

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class ELBPolicy(credentials: AWSCredentials, loadBalancerName: String) extends Actor with AWSSupervisorStrategy {

  override def receive: Receive = {
    case x: ELBPolicyDef =>

      val policyAttributes = x.policyAttributes.foldLeft(Seq.empty[PolicyAttribute])((sum, i) => sum :+ new PolicyAttribute(i.name, i.value))

      val loadBalancerPolicyRequest = new CreateLoadBalancerPolicyRequest()
        .withLoadBalancerName(loadBalancerName)
        .withPolicyName(x.policyName)
        .withPolicyTypeName(x.policyType)
        .withPolicyAttributes(policyAttributes)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      awsClient.createLoadBalancerPolicy(loadBalancerPolicyRequest)

      context.parent ! PolicyCreated
  }


  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: ELBPolicyDef => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }

}

object ELBPolicy {

  case object PolicyCreated

  def props(creds: AWSCredentials, loadBalancerName: String): Props = Props(new ELBPolicy(creds, loadBalancerName))
}
