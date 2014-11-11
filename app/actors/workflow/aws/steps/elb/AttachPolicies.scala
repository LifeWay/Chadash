package actors.workflow.aws.steps.elb

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{SetLoadBalancerPoliciesForBackendServerRequest, SetLoadBalancerPoliciesOfListenerRequest}

import scala.collection.JavaConverters._

class AttachPolicies(credentials: AWSCredentials, loadBalancerName: String) extends Actor with ActorLogging {

  import actors.workflow.aws.steps.elb.AttachPolicies._

  override def receive: Receive = {
    case x: AttachFrontEndPolicies =>
      val policyReq = new SetLoadBalancerPoliciesOfListenerRequest()
        .withLoadBalancerName(loadBalancerName)
        .withLoadBalancerPort(x.port)
        .withPolicyNames(x.policies.asJava)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      awsClient.setLoadBalancerPoliciesOfListener(policyReq)

      sender() ! PoliciesAttached

    case x: AttachBackEndPolicies =>
      val policyReq = new SetLoadBalancerPoliciesForBackendServerRequest()
        .withLoadBalancerName(loadBalancerName)
        .withInstancePort(x.port)
        .withPolicyNames(x.policies.asJava)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      awsClient.setLoadBalancerPoliciesForBackendServer(policyReq)

      sender() ! PoliciesAttached
  }
}

object AttachPolicies {

  case class AttachBackEndPolicies(port: Int, policies: Seq[String])

  case class AttachFrontEndPolicies(port: Int, policies: Seq[String])

  case object PoliciesAttached

  def props(creds: AWSCredentials, loadBalancerName: String): Props = Props(new AttachPolicies(creds, loadBalancerName))
}
