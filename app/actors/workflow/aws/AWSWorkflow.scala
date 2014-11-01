package actors.workflow.aws

import actors.workflow.aws.steps.elb.ELBSupervisor
import actors.workflow.aws.steps.launchconfig.LaunchConfigSupervisor
import akka.actor.{Actor, ActorLogging, ActorRef}
import com.typesafe.config.Config
import play.api.libs.json.{JsNull, JsValue}

import scala.collection.JavaConversions._

/**
 * One AWS workflow actor will be created per deployment request. This actor's job is to read in the configuration for
 * the flow. Upon reading in the configuration the following steps shall occur:
 *
 * 1. Create ActorRefs for each of the defined steps
 * 2. Setup a Sequence ordered of the ActorRefs to the order defined in the configuration.
 * 3. Start the sequence.
 * 4. Upon each step completing, any return data from that step must be stored by this actor.
 * 5. Further steps may have a configuration that requests that return data from a given step as defined in the config
 *
 */
class AWSWorkflow extends Actor with ActorLogging {

  import actors.workflow.aws.AWSWorkflow._
  import context._

  var currentStep = 0
  var steps = Seq.empty[ActorRef]
  var stepResultData = Map.empty[String, JsValue]

  override def receive: Receive = {

    case x: Deploy => {
      val stepOrder: Seq[String] = x.appConfig.getStringList("stepOrder").to[Seq]
      stepOrder.foldLeft(Seq.empty[ActorRef])((x, i) => x :+ actorLoader(i))
      sender() ! StartingWorkflow
      become(workflowProcessing)

      self ! Start
    }
  }

  def workflowProcessing: Receive = {
    case Deploy => sender() ! DeployInProgress
    case Start => {
      steps(currentStep) ! StartStep(stepResultData, JsNull)
    }
    case StepCompleted => {
      currentStep = currentStep + 1

      steps.size == currentStep match {
        case true => parent ! DeployCompleted
        case false => steps(currentStep) ! StartStep(stepResultData, JsNull)
      }
    }
  }

  def actorLoader(configName: String): ActorRef = {
    configName match {
      case "createLaunchConfig" => context.actorOf(LaunchConfigSupervisor.props(), "createLaunchConfig")
      case "createELB" => context.actorOf(ELBSupervisor.props(), "createELB")
    }
  }
}

object AWSWorkflow {

  case class Deploy(appConfig: Config, data: JsValue)

  case class StartStep(data: Map[String, JsValue], appData: JsValue)

  case object Start

  case object DeployInProgress

  case object DeployCompleted

  case object StepCompleted

  case object StartingWorkflow

}