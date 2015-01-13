package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, Instance}

import scala.collection.JavaConverters._

class ELBHealthyInstanceChecker(credentials: AWSCredentials) extends Actor with AWSRestartableActor with ActorLogging {

  import actors.workflow.tasks.ELBHealthyInstanceChecker._

  override def receive: Receive = {
    case msg: ELBIsInstanceListHealthy =>

      val elbInstances: Seq[Instance] = msg.instances.foldLeft(Seq.empty[Instance])((sum, i) => sum :+ new Instance(i))
      val instanceHealthRequest = new DescribeInstanceHealthRequest()
        .withInstances(elbInstances.asJava)
        .withLoadBalancerName(msg.elbName)

      val awsClient = new AmazonElasticLoadBalancingClient(credentials)
      val instanceStates = awsClient.describeInstanceHealth(instanceHealthRequest).getInstanceStates.asScala.toSeq
      val unhealthyInstances = instanceStates.filter(p => p.getState != "InService")

      unhealthyInstances.size match {
        case i if i > 0 =>
          context.sender() ! ELBInstanceListNotHealthy(i, msg.elbName)
        case i if i == 0 =>
          context.sender() ! ELBInstanceListAllHealthy
      }
  }
}

object ELBHealthyInstanceChecker {

  case class ELBIsInstanceListHealthy(elbName: String, instances: Seq[String])

  case class ELBInstanceListNotHealthy(unhealthyInstances: Int, elbName: String)

  case object ELBInstanceListAllHealthy

  def props(credentials: AWSCredentials): Props = Props(new ELBHealthyInstanceChecker(credentials))
}
