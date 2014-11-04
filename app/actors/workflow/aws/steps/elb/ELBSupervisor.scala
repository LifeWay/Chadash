package actors.workflow.aws.steps.elb

import javax.naming.LimitExceededException

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSWorkflow.StartStep
import actors.workflow.aws.steps.elb.ElasticLoadBalancer.{CreateELB, ELBCreated, ELBListener}
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.model.AlreadyExistsException
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.typesafe.config.{Config, ConfigFactory}
import utils.ConfigHelpers._
import utils.Constants

import scala.collection.JavaConversions._
import scala.concurrent.duration._

class ELBSupervisor(var credentials: AWSCredentials) extends Actor with ActorLogging {

  var config: Config = ConfigFactory.empty()

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 5.minutes, loggingEnabled = false) {
    case ex: LimitExceededException => {
      context.child(Constants.statusActorName).get ! LogMessage(ex.toString)
      log.error(ex, "Limit has been exceeded")
      Stop
    }
    case ex: AlreadyExistsException => {
      context.child(Constants.statusActorName).get ! LogMessage(ex.toString)
      log.error(ex, "Already exists")
      Stop
    }
    case _: AmazonServiceException => Restart
    case _: AmazonClientException => Restart
    case ex: Exception => {
      context.child(Constants.statusActorName).get ! LogMessage(ex.toString)
      log.error(ex, "Catch-all Exception Handler.")
      Stop
    }
  }

  override def receive: Receive = {
    case x: StartStep => {
      config = x.configData.getConfig(s"steps.${aws.CreateElb}")

      val createELB = context.actorOf(ElasticLoadBalancer.props(credentials), "createLoadBalancer")
      context.watch(createELB)

      val listenerSeq: Seq[ELBListener] = config.getConfigList("listeners").foldLeft(Seq.empty[ELBListener])((sum, i) => {
        val newListener = ELBListener(
          instancePort = i.getInt("instancePort"),
          instanceProtocol = i.getString("instanceProtocol"),
          loadBalancerPort = i.getInt("loadBalancerPort"),
          loadBalancerProtocol = i.getString("loadBalancerProtocol"),
          sslCertificateId = i.getOptString("sslCertificateId")
        )
        sum :+ newListener
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
    case x:ELBCreated =>
      context.parent ! LogMessage(s"ELB created: ${x.dnsName}")

    //Child actors:
    //CreateELB (covers AZ's, listeners, name, scheme, security groups, subnets, tags
    //configureHealthCheck
    //ELB security policy
    //ELB attributes (cross-zone load balancing, connection draining, access logs, idle timeouts)


    //      //TODO: configure health check
    //      val healthCheck = new HealthCheck()
    //        .withTarget()
    //        .withInterval()
    //        .withTimeout()
    //        .withHealthyThreshold()
    //        .withUnhealthyThreshold()
    //
    //      val healthCheckRequest = new ConfigureHealthCheckRequest()
    //        .withLoadBalancerName()
    //        .withHealthCheck(healthCheck)
    //
    //      val elbAttributes = new LoadBalancerAttributes()
    //        .withAccessLog()
    //        .withConnectionDraining()
    //        .withConnectionSettings()
    //        .withCrossZoneLoadBalancing()
    //
    //      val modifyAttributesRequest = new ModifyLoadBalancerAttributesRequest()
    //        .withLoadBalancerName()
    //        .withLoadBalancerAttributes()
    //
    //      awsClient.configureHealthCheck(healthCheckRequest)

  }
}

object ELBSupervisor {


  def props(credentials: AWSCredentials): Props = Props(new ELBSupervisor(credentials))
}