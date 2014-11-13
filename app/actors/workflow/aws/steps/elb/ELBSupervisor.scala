package actors.workflow.aws.steps.elb

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.steps.elb.ELBAttributes.{ELBAccessLog, ELBAttributesModified, ELBConnectionDraining, SetELBAttributes}
import actors.workflow.aws.steps.elb.ELBPoliciesSupervisor._
import actors.workflow.aws.steps.elb.ELBSupervisor.ELBStepFailed
import actors.workflow.aws.steps.elb.ElasticLoadBalancer.{CreateELB, ELBCreated, ELBListener}
import actors.workflow.aws.steps.elb.HealthCheck.{CreateELBHealthCheck, HealthCheckConfig, HealthCheckConfigured}
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import akka.actor.{Actor, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.{Config, ConfigFactory}
import utils.ConfigHelpers._

import scala.collection.JavaConverters._

class ELBSupervisor(credentials: AWSCredentials, elbName: String) extends Actor with AWSSupervisorStrategy {

  var config: Config = ConfigFactory.empty()
  var steps = Seq.empty[String]
  var stepsCompleted = 0

  override def receive: Receive = {
    case x: StartStep =>

      config = x.configData.getConfig(s"steps.${aws.CreateElb}")

      steps = steps :+ "modifyELBAttributes"
      if (config.hasPath("HealthCheck")) steps = steps :+ "HealthCheck"
      if (config.hasPath("Policies") && config.hasPath("ListenerPolicies")) steps = steps :+ "Policies"

      val createELB = context.actorOf(ElasticLoadBalancer.props(credentials), "createLoadBalancer")
      context.watch(createELB)

      val listenerSeq: Seq[ELBListener] = config.getConfigList("ListenerDescriptions").asScala.foldLeft(Seq.empty[ELBListener])((sum, i) => {
        sum :+ ELBListener(
          instancePort = i.getInt("InstancePort"),
          instanceProtocol = i.getString("InstanceProtocol"),
          loadBalancerPort = i.getInt("LoadBalancerPort"),
          loadBalancerProtocol = i.getString("Protocol"),
          sslCertificateId = i.getOptString("SSLCertificateId")
        )
      })

      val globalTags: Option[Seq[(String, String)]] = x.configData.getOptConfigList("Tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      val optionalTags: Option[Seq[(String, String)]] = config.getOptConfigList("Tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      val mergedTags: Option[Seq[(String, String)]] = (globalTags, optionalTags) match {
        case (Some(y), Some(y1)) => Some(y ++ y1)
        case (Some(y), None) => Some(y)
        case (None, Some(y1)) => Some(y1)
        case _ => None
      }

      context.parent ! LogMessage(s"ELB: Attempting to create...")
      createELB ! CreateELB(
        loadBalancerName = elbName,
        securityGroups = config.getStringList("SecurityGroups").asScala,
        subnets = x.configData.getStringList("Subnets").asScala,
        listeners = listenerSeq,
        tags = mergedTags,
        scheme = config.getOptString("Scheme")
      )
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case x: ELBCreated =>
      context.parent ! LogMessage(s"ELB: Created: ${x.dnsName}")

      //Fan out the parallel configuration steps
      steps.seq.map {
        case "modifyELBAttributes" =>
          val elbAttributes = context.actorOf(ELBAttributes.props(credentials), "modifyELBAttributes")
          context.watch(elbAttributes)

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
            elbName = elbName,
            idleTimeout = config.getConfig("ConnectionSettings").getInt("IdleTimeout"),
            crossZoneLB = config.getOptBoolean("CrossZoneLoadBalancing"),
            connectionDraining = connectionDraining,
            accessLogs = accessLog
          )

        case "HealthCheck" =>
          val healthCheck = context.actorOf(HealthCheck.props(credentials), "addHealthCheck")
          context.watch(healthCheck)

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
            loadBalancerName = elbName,
            healthCheck = hc
          )

        case "Policies" =>
          val elbPolicySupervisor = context.actorOf(ELBPoliciesSupervisor.props(credentials, elbName))
          context.watch(elbPolicySupervisor)

          val policyConfigs = config.getConfigList("Policies")
          val listenerPolicies = config.getConfig("ListenerPolicies")

          context.parent ! LogMessage(s"ELB: Attempting to create and attach listener policies")
          elbPolicySupervisor ! SetupELBPolicies(policyConfigs.asScala, listenerPolicies)

        case m: Any => log.warning(s"Unknown type ${m.toString}")
    }

    case ELBAttributesModified =>
      context.parent ! LogMessage(s"ELB: Configuration attributes set")
      updateAndCheckIfFinished()

    case HealthCheckConfigured =>
      context.parent ! LogMessage(s"ELB: Health check created")
      updateAndCheckIfFinished()

    case ELBPoliciesConfigured =>
      context.parent ! LogMessage(s"ELB: Policies Created & Attached")
      updateAndCheckIfFinished()

    case ELBStepFailed =>
      context.parent ! AWSWorkflow.StepFailed

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed

  }

  def updateAndCheckIfFinished(): Unit = {
    stepsCompleted = stepsCompleted + 1
    if (stepsCompleted == steps.length) {
      context.parent ! LogMessage("ELB: All steps completed")
      context.parent ! StepFinished(None)
      context.unbecome()
    }
  }

}

object ELBSupervisor {

  case object ELBStepFailed

  def props(credentials: AWSCredentials, elbName: String): Props = Props(new ELBSupervisor(credentials, elbName))
}