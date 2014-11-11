package actors.workflow.aws.steps.elb

import actors.workflow.aws.AWSSupervisorStrategy
import actors.workflow.aws.steps.elb.AttachPolicies.{AttachBackEndPolicies, AttachFrontEndPolicies, PoliciesAttached}
import actors.workflow.aws.steps.elb.ELBPolicy.PolicyCreated
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.{Config, ConfigFactory}
import utils.ConfigHelpers._

import scala.collection.JavaConverters._

class ELBPoliciesSupervisor(credentials: AWSCredentials, loadBalancerName: String) extends Actor with AWSSupervisorStrategy {

  import actors.workflow.aws.steps.elb.ELBPoliciesSupervisor._

  var totalPolicies = 0
  var completedPolicies = 0
  var totalListenerMappings = 0
  var completedListenerMappings = 0
  var listenerPolicies: Config = ConfigFactory.empty()

  override def receive: Receive = {
    case x: SetupELBPolicies =>
      listenerPolicies = x.policyListeners

      val policyAttributes = x.policyConfigs.foldLeft(Seq.empty[ELBPolicyDef])(
        (sum, i) => {
          val policyAttributeDescriptions = i.getConfigList("PolicyAttributeDescriptions")
          val policyAttributeList = policyAttributeDescriptions.asScala.foldLeft(Seq.empty[ELBPolicyAttribute])(
            (sum2, i2) => sum2 :+ ELBPolicyAttribute(i2.getString("AttributeName"), i2.getString("AttributeValue"))
          )
          sum :+ ELBPolicyDef(i.getString("PolicyName"), i.getString("PolicyTypeName"), policyAttributeList)
        }
      )

      context.become(working)
      self ! CreateELBPolicies(policyAttributes)
  }

  def working: Receive = {
    case x: CreateELBPolicies =>
      totalPolicies = x.policies.size
      x.policies.map { policyDef =>
        val policyActor = context.actorOf(ELBPolicy.props(credentials, loadBalancerName), policyDef.policyName)
        context.watch(policyActor)
        policyActor ! policyDef
      }

    case PolicyCreated =>
      context.unwatch(sender())
      context.stop(sender())
      completedPolicies = completedPolicies + 1

      if (completedPolicies == totalPolicies) {
        listenerPolicies.getOptConfigList("FrontEnd") match {
          case Some(y) =>
            y.map {
              x =>
                totalListenerMappings = totalListenerMappings + 1

                val attachPoliciesActor = context.actorOf(AttachPolicies.props(credentials, loadBalancerName))
                context.watch(attachPoliciesActor)
                attachPoliciesActor ! AttachFrontEndPolicies(x.getInt("Port"), x.getStringList("PolicyNames").asScala)
            }
          case None => ()
        }

        listenerPolicies.getOptConfigList("BackEnd") match {
          case Some(y) =>
            y.map {
              x =>
                totalListenerMappings = totalListenerMappings + 1

                val attachPoliciesActor = context.actorOf(AttachPolicies.props(credentials, loadBalancerName))
                context.watch(attachPoliciesActor)
                attachPoliciesActor ! AttachBackEndPolicies(x.getInt("Port"), x.getStringList("PolicyNames").asScala)
            }
          case None => ()
        }
      }

    case PoliciesAttached =>
      context.unwatch(sender())
      context.stop(sender())
      completedListenerMappings = completedListenerMappings + 1

      if (completedListenerMappings == totalListenerMappings) {
        context.unbecome()
        context.parent ! ELBPoliciesConfigured
      }

    case Terminated(actorRef) =>
      self ! PoisonPill
  }
}

object ELBPoliciesSupervisor {

  case class ELBPolicyAttribute(name: String, value: String)

  case class ELBPolicyDef(policyName: String, policyType: String, policyAttributes: Seq[ELBPolicyAttribute])

  case class CreateELBPolicies(policies: Seq[ELBPolicyDef])

  case class SetupELBPolicies(policyConfigs: Seq[Config], policyListeners: Config)

  case object ELBPoliciesFailed

  case object ELBPoliciesConfigured

  def props(creds: AWSCredentials, loadBalancerName: String): Props = Props(new ELBPoliciesSupervisor(creds, loadBalancerName))
}
