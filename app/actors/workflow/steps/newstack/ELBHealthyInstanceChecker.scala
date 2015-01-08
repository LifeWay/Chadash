package actors.workflow.steps.newstack

import actors.WorkflowStatus.LogMessage
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, Instance}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ELBHealthyInstanceChecker(credentials: AWSCredentials, elbName: String, instances: Seq[String]) extends Actor with ActorLogging {

  import actors.workflow.steps.newstack.ELBHealthyInstanceChecker._

  override def preStart() = scheduleTick()

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}


  override def receive: Receive = {
    case Tick =>
      val elbInstances: Seq[Instance] = instances.foldLeft(Seq.empty[Instance])((sum, i) => sum :+ new Instance(i))
      val instanceHealthRequest = new DescribeInstanceHealthRequest()
        .withInstances(elbInstances.asJava)
        .withLoadBalancerName(elbName)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      val instanceStates = awsClient.describeInstanceHealth(instanceHealthRequest).getInstanceStates.asScala.toSeq
      val unhealthyInstances = instanceStates.filter(p => p.getState != "InService")

      unhealthyInstances.size match {
        case i if i > 0 =>
          context.parent ! LogMessage("Instances not yet healthy: " + i)
          scheduleTick()
        case i if i == 0 =>
          context.parent ! ELBInstancesHealthy(elbName)
      }
  }

  def scheduleTick() = context.system.scheduler.scheduleOnce(10.seconds, self, Tick)
}

object ELBHealthyInstanceChecker {

  case object Tick

  case class ELBInstancesHealthy(elbName: String)

  def props(credentials: AWSCredentials, elbName: String, instances: Seq[String]): Props = Props(new ELBHealthyInstanceChecker(credentials, elbName, instances))
}
