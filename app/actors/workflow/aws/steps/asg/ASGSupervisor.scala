package actors.workflow.aws.steps.asg

import actors.WorkflowStatus.LogMessage
import actors.workflow.aws
import actors.workflow.aws.AWSWorkflow.{StartStep, StepFinished}
import actors.workflow.aws.steps.asg.AutoScalingGroup.{ASGCreated, CreateASG}
import actors.workflow.aws.{AWSSupervisorStrategy, AWSWorkflow}
import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.{Config, ConfigFactory}
import utils.ConfigHelpers._

import scala.collection.JavaConverters._

class ASGSupervisor(credentials: AWSCredentials, labelName: String) extends Actor with ActorLogging with AWSSupervisorStrategy {

  var config: Config = ConfigFactory.empty()

  override def receive: Receive = {
    case x: StartStep =>
      val config = x.configData.getConfig(s"steps.${aws.CreateElbASG}")
      val subnets = x.configData.getStringList("Subnets").asScala
      log.debug(s"Subnets: ${subnets.toString()}")

      val asgActor = context.actorOf(AutoScalingGroup.props(credentials), "asg")
      context.watch(asgActor)

      val globalTags: Option[Seq[(String, String)]] = x.configData.getOptConfigList("Tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      val optionalTags: Option[Seq[(String, String)]] = config.getOptConfigList("Tags") match {
        case Some(y) => Some(y.foldLeft(Seq.empty[(String, String)])((sum, i) => sum :+(i.getString("name"), i.getString("value"))))
        case None => None
      }

      val mergedTags: Seq[(String, String)] = (globalTags, optionalTags) match {
        case (Some(y), Some(y1)) => y ++ y1 ++ Seq(("Name", labelName))
        case (Some(y), None) => y ++ Seq(("Name", labelName))
        case (None, Some(y1)) => y1 ++ Seq(("Name", labelName))
        case _ => Seq(("Name", labelName))
      }

      asgActor ! CreateASG(
        labelName = labelName,
        desiredCapacity = config.getInt("DesiredCapacity"),
        minSize = config.getInt("MinSize"),
        maxSize = config.getInt("MaxSize"),
        healthCheckGracePeriod = config.getInt("HealthCheckGracePeriod"),
        healthCheckType = "ELB",
        vpcSubnets = subnets,
        tags = Some(mergedTags)
      )
      context.become(stepInProcess)
  }

  def stepInProcess: Receive = {
    case ASGCreated =>
      context.parent ! LogMessage("ASG: Completed")
      context.parent ! StepFinished(None)
      context.unbecome()

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! AWSWorkflow.StepFailed
  }
}

object ASGSupervisor {

  def props(credentials: AWSCredentials, labelName: String): Props = Props(new ASGSupervisor(credentials, labelName))
}
