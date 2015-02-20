package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, Instance}
import utils.{AmazonElasticLoadBalancingService, PropFactory}

import scala.collection.JavaConverters._

class ELBHealthyInstanceChecker(credentials: AWSCredentials) extends AWSRestartableActor
                                                                     with AmazonElasticLoadBalancingService {

  import actors.workflow.tasks.ELBHealthyInstanceChecker._

  override def receive: Receive = {
    case msg: ELBIsInstanceListHealthy =>

      val elbInstances: Seq[Instance] = msg.instances.foldLeft(Seq.empty[Instance])((sum, i) => sum :+ new Instance(i))
      val instanceHealthRequest = new DescribeInstanceHealthRequest()
                                  .withInstances(elbInstances.asJava)
                                  .withLoadBalancerName(msg.elbName)

      val awsClient = elasticLoadBalancingClient(credentials)
      val instanceStates = awsClient.describeInstanceHealth(instanceHealthRequest).getInstanceStates.asScala.toSeq
      val unhealthyInstances = instanceStates.filter(p => p.getState != "InService")

      unhealthyInstances.size match {
        case i if i > 0 =>
          context.parent ! ELBInstanceListNotHealthy(i, msg.elbName)
        case i if i == 0 =>
          context.parent ! ELBInstanceListAllHealthy(msg.elbName)
      }
  }
}

object ELBHealthyInstanceChecker extends PropFactory {
  case class ELBIsInstanceListHealthy(elbName: String, instances: Seq[String])
  case class ELBInstanceListNotHealthy(unhealthyInstances: Int, elbName: String)
  case class ELBInstanceListAllHealthy(elbName: String)

  override def props(args: Any*): Props = Props(classOf[ELBHealthyInstanceChecker], args: _*)
}
