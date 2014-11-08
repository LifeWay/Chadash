package actors.workflow.aws.steps.elb

import actors.workflow.aws.steps.elb.ELBPolicy.PolicyCreated
import akka.actor._
import com.amazonaws.auth.AWSCredentials

class ELBPoliciesSupervisor(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.ELBPoliciesSupervisor._

  var totalPolicies = 0
  var completedPolicies = 0

  override def receive: Receive = {

    //TODO: handle start .. and then read in all the policy config data, become working after that.

    case x: CreateELBPolicies =>
      totalPolicies = x.policies.size
      x.policies.map { y =>
        val policy = context.actorOf(ELBPolicy.props(credentials, x.loadBalancerName), y.policyName)
        context.watch(policy)
        policy ! y
      }
    case PolicyCreated =>
      context.unwatch(sender())
      completedPolicies = completedPolicies + 1
      if (completedPolicies == totalPolicies)
        context.parent ! ELBPoliciesConfigured
    case Terminated(actorRef) =>
      self ! PoisonPill
  }
}

object ELBPoliciesSupervisor {

  case class ELBPolicyAttribute(name: String, value: String)

  case class ELBPolicyDef(policyName: String, policyType: String, policyAttributes: Seq[ELBPolicyAttribute])

  case class CreateELBPolicies(loadBalancerName: String, policies: Seq[ELBPolicyDef])

  case object ELBPoliciesFailed

  case object ELBPoliciesConfigured

  def props(creds: AWSCredentials): Props = Props(new ELBPoliciesSupervisor(creds))
}
