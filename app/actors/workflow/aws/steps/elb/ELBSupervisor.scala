package actors.workflow.aws.steps.elb

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSSupervisorStrategy
import actors.workflow.aws.AWSWorkflow.{StepFinished, StartStep}
import actors.workflow.aws.steps.elb.ELBAttributes.{ELBAccessLog, ELBAttributesModified, ELBConnectionDraining, SetELBAttributes}
import actors.workflow.aws.steps.elb.ElasticLoadBalancer.{CreateELB, ELBCreated, ELBListener}
import actors.workflow.aws.steps.elb.HealthCheck.{HealthCheckConfigured, CreateELBHealthCheck, HealthCheckConfig}
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.model.AlreadyExistsException
import com.amazonaws.services.elasticloadbalancing.model._
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.typesafe.config.{Config, ConfigFactory}
import utils.ConfigHelpers._
import utils.Constants

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class ELBSupervisor(var credentials: AWSCredentials) extends Actor with AWSSupervisorStrategy {

  var config: Config = ConfigFactory.empty()
  var optionalSteps = Seq.empty[String]

  override def receive: Receive = {
    case x: StartStep => {
      config = x.configData.getConfig(s"steps.${aws.CreateElb}")

      if(config.hasPath("HealthCheck")) optionalSteps :+ "HealthCheck"

      val createELB = context.actorOf(ElasticLoadBalancer.props(credentials), "createLoadBalancer")
      context.watch(createELB)

      val listenerSeq: Seq[ELBListener] = config.getConfigList("ListenerDescriptions").foldLeft(Seq.empty[ELBListener])((sum, i) => {
        val listenerConfig = i.getConfig("Listener")
        sum :+ ELBListener(
          instancePort = listenerConfig.getInt("InstancePort"),
          instanceProtocol = listenerConfig.getString("InstanceProtocol"),
          loadBalancerPort = listenerConfig.getInt("LoadBalancerPort"),
          loadBalancerProtocol = listenerConfig.getString("Protocol"),
          sslCertificateId = listenerConfig.getOptString("SSLCertificateId")
        )
      })

      val optionalTags: Option[Seq[(String, String)]] = config.getOptConfigList("Tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      context.parent ! LogMessage(s"ELB: Attempting to create...")
      createELB ! CreateELB(
        loadBalancerName = "someRandomName",
        securityGroups = config.getStringList("SecurityGroups"),
        subnets = config.getStringList("Subnets"),
        listeners = listenerSeq,
        tags = optionalTags,
        scheme = config.getOptString("Scheme")
      )
      context.become(stepInProcess)
    }
  }

  def stepInProcess: Receive = {
    case x: ELBCreated => {
      context.parent ! LogMessage(s"ELB: Created: ${x.dnsName}")

      val elbAttributes = context.actorOf(ELBAttributes.props(credentials), "modifyELBAttributes")

      val accessLog: Option[ELBAccessLog] = config.getOptConfig("AccessLog") match {
        case Some(y) => Some(new ELBAccessLog(
          emitInterval = y.getInt("EmitInterval"),
          enabled = y.getBoolean("Enabled"),
          bucketName = y.getString("BucketName"),
          bucketPrefix = y.getString("BucketPrefix")
        ))
        case None => None
      }

      val connectionDraining: Option[ELBConnectionDraining] = config.getOptConfig("ConnectionDraining") match {
        case Some(y) => Some(new ELBConnectionDraining(
          enabled = y.getBoolean("Enabled"),
          timeout = y.getInt("Timeout")
        ))
        case None => None
      }

      context.parent ! LogMessage(s"ELB: Attempting to set configuration attributes")
      elbAttributes ! SetELBAttributes(
        elbName = "someRandomName",
        idleTimeout = config.getConfig("ConnectionSettings").getInt("IdleTimeout"),
        crossZoneLB = config.getOptBoolean("CrossZoneLoadBalancing"),
        connectionDraining = connectionDraining,
        accessLogs = accessLog
      )
    }
    case ELBAttributesModified => {
      //TODO: all the required ELB items are done, do we have a health check AND / OR security policy?
      context.parent ! LogMessage(s"ELB: Configuration attributes set")

      val healthCheck = context.actorOf(HealthCheck.props(credentials), "addHealthCheck")

      val healthCheckConfig = config.getConfig("HealthCheck")

      val hc = HealthCheckConfig(
        urlTarget = healthCheckConfig.getString("Target"),
        interval = healthCheckConfig.getInt("Interval"),
        timeout = healthCheckConfig.getInt("Timeout"),
        unhealthyThreshold = healthCheckConfig.getInt("UnhealthyThreshold"),
        healthyThreshold = healthCheckConfig.getInt("HealthyThreshold")
      )

      context.parent ! LogMessage(s"ELB: Attempting to create health check")

      healthCheck ! CreateELBHealthCheck(
        loadBalancerName = "someRandomName",
        healthCheck = hc
      )
    }

    case HealthCheckConfigured => {

      context.parent ! LogMessage(s"ELB: Health check created")


      context.parent ! LogMessage("ELB: All steps completed")
      context.parent ! StepFinished(None)
      context.unbecome()
    }


    //Child actors:
    // CreateELB (covers AZ's, listeners, name, scheme, security groups, subnets, tags
    // ELB attributes (cross-zone load balancing, connection draining, access logs, idle timeouts)
    // configureHealthCheck (optional)
    // ELB security policy (optional)
  }

}

object ELBSupervisor {


  def props(credentials: AWSCredentials): Props = Props(new ELBSupervisor(credentials))
}