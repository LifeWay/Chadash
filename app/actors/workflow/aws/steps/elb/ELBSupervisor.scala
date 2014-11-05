package actors.workflow.aws.steps.elb

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSSupervisorStrategy
import actors.workflow.aws.AWSWorkflow.StartStep
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

      if(config.hasPath("healthCheck")) optionalSteps :+ "healthCheck"
      //if(config.hasPath(""))

      val createELB = context.actorOf(ElasticLoadBalancer.props(credentials), "createLoadBalancer")
      context.watch(createELB)

      val listenerSeq: Seq[ELBListener] = config.getConfigList("listeners").foldLeft(Seq.empty[ELBListener])((sum, i) => {
        sum :+ ELBListener(
          instancePort = i.getInt("instancePort"),
          instanceProtocol = i.getString("instanceProtocol"),
          loadBalancerPort = i.getInt("loadBalancerPort"),
          loadBalancerProtocol = i.getString("loadBalancerProtocol"),
          sslCertificateId = i.getOptString("sslCertificateId")
        )
      })

      val optionalTags: Option[Seq[(String, String)]] = config.getOptConfigList("tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      createELB ! CreateELB(
        loadBalancerName = "someRandomName",
        securityGroups = config.getStringList("securityGroups"),
        subnets = config.getStringList("subnets"),
        listeners = listenerSeq,
        tags = optionalTags,
        scheme = config.getOptString("scheme")
      )
      context.become(stepInProcess)
    }
  }

  def stepInProcess: Receive = {
    case x: ELBCreated => {
      context.parent ! LogMessage(s"ELB created: ${x.dnsName}")

      val elbAttributes = context.actorOf(ELBAttributes.props(credentials), "modifyELBAttributes")

      val accessLog: Option[ELBAccessLog] = config.getOptConfig("accessLog") match {
        case Some(y) => Some(new ELBAccessLog(
          emitInterval = y.getInt("emitInterval"),
          enabled = y.getBoolean("enabled"),
          bucketName = y.getString("bucketName"),
          bucketPrefix = y.getString("bucketPrefix")
        ))
        case None => None
      }

      val connectionDraining: Option[ELBConnectionDraining] = config.getOptConfig("connectionDraining") match {
        case Some(y) => Some(new ELBConnectionDraining(
          enabled = y.getBoolean("enabled"),
          timeout = y.getInt("timeout")
        ))
        case None => None
      }

      elbAttributes ! SetELBAttributes(
        elbName = "someRandomName",
        idleTimeout = config.getInt("idleTimeout"),
        crossZoneLB = config.getOptBoolean("crossZoneLoadBalancing"),
        connectionDraining = connectionDraining
      )
    }
    case ELBAttributesModified => {
      //TODO: all the required ELB items are done, do we have a health check AND / OR security policy?

      val healthCheck = context.actorOf(HealthCheck.props(credentials), "addHealthCheck")

      val healthCheckConfig = config.getConfig("healthCheck")

      val hc = HealthCheckConfig(
        urlTarget = healthCheckConfig.getString("target"),
        interval = healthCheckConfig.getInt("interval"),
        timeout = healthCheckConfig.getInt("timeout"),
        unhealthyThreshold = healthCheckConfig.getInt("unhealthyThreshold"),
        healthyThreshold = healthCheckConfig.getInt("healthyThreshold")
      )

      healthCheck ! CreateELBHealthCheck(
        loadBalancerName = "someRandomName",
        healthCheck = hc
      )
    }

    case HealthCheckConfigured => {

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