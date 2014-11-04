package actors.workflow.aws

import actors.AmazonCredentials.CurrentCredentials
import actors.WorkflowStatus.{Log, LogMessage}
import actors.workflow.aws.steps.elb.ELBSupervisor
import actors.workflow.aws.steps.launchconfig.LaunchConfigSupervisor
import actors.{AmazonCredentials, ChadashSystem, DeploymentSupervisor, WorkflowStatus}
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.json.{JsNull, JsString, JsValue}
import utils.Constants

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

  var appVersion = 0
  var appName = ""
  var deployData: JsValue = JsNull
  var appConfig = ConfigFactory.empty()
  var credentials: AWSCredentials = null

  //STEPS
  var currentStep = 0
  var stepSequence: Seq[String] = Seq.empty[String]
  var steps = Seq.empty[ActorRef]
  var stepResultData = Map.empty[String, Option[JsValue]]

  override def receive: Receive = {

    case x: Deploy => {
      appConfig = x.appConfig
      appVersion = (x.data \ "version").as[Int]
      appName = (x.data \ "name").as[JsString].value
      deployData = x.data

      val workflowStatus = context.actorOf(WorkflowStatus.props(5), utils.Constants.statusActorName)
      context.watch(workflowStatus)

      workflowStatus ! LogMessage("Starting deployment workflow...")
      sender() ! StartingWorkflow
      become(workflowProcessing)
      self ! Start
    }
  }

  def workflowProcessing: Receive = {
    case x: Deploy => sender() ! DeployInProgress
    case Start => {
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
    }
    case x: CurrentCredentials => {
      context.unwatch(sender())
      credentials = x.credentials

      stepSequence = appConfig.getStringList("stepOrder").to[Seq]
      steps = stepSequence.foldLeft(Seq.empty[ActorRef])((x, i) => x :+ actorLoader(i))

      steps(currentStep) ! StartStep(appVersion, appName, stepResultData, appConfig, deployData)
    }
    case x: StepFinished => {
      //Record data
      stepResultData + (stepSequence(currentStep) -> x.stepData)

      currentStep = currentStep + 1
      steps.size == currentStep match {
        case true => parent ! DeployCompleted
        case false => steps(currentStep) ! StartStep(appVersion, appName, stepResultData, appConfig, deployData)
      }
    }
    case x: Log => {
      context.child(Constants.statusActorName) match {
        case Some(actor) => actor forward x
        case None => () //TODO: this should never happen, should we raise an exception maybe?
      }
    }
    case Terminated(actorRef) => {
      self ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      parent ! DeploymentSupervisor.DeployFailed
    }
    case StepFailed => {
      //Step Supervisors should be able to heal themselves if they are able. If we receive a step failed, the whole job fails and needs a human.
      self ! LogMessage(s"The following step has failed: ${stepSequence(currentStep)}}")
      parent ! DeploymentSupervisor.DeployFailed
    }
    case m: Any => log.debug("Unhandled message type received: " + m.toString)
  }

  def actorLoader(configName: String): ActorRef = {
    configName match {
      case "createLaunchConfig" => context.actorOf(LaunchConfigSupervisor.props(credentials), CreateLaunchConfig)
      case "createELB" => context.actorOf(ELBSupervisor.props(credentials), CreateElb)
      case _ => null
    }
  }
}

object AWSWorkflow {

  case class Deploy(appConfig: Config, data: JsValue)

  case class StartStep(appVersion: Int, appName: String, data: Map[String, Option[JsValue]], configData: Config, deployData: JsValue)

  case class StepFinished(stepData: Option[JsValue])

  case object StepFailed

  case object Start

  case object DeployInProgress

  case object DeployCompleted

  case object StartingWorkflow

}