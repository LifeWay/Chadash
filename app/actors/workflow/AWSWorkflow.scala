package actors.workflow.aws

import actors.AmazonCredentials.CurrentCredentials
import actors.DeploymentSupervisor.Deploy
import actors.WorkflowStatus.{Log, LogMessage}
import actors.workflow.steps.stackloader.LoadStackSupervisor
import actors.workflow.steps.validateandfreeze.ValidateAndFreezeSupervisor
import actors.{AmazonCredentials, ChadashSystem, DeploymentSupervisor, WorkflowStatus}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.ConfigFactory
import play.api.libs.json.JsValue
import utils.Constants

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

  var deploy: Deploy = null
  var credentials: AWSCredentials = null
  var stackBucket: String = null

  //STEPS
  val stepSequence: Seq[String] = Seq("loadStackFile", "validateAndFreeze")
  var currentStep = 0
  var steps = Seq.empty[ActorRef]
  var stepResultData = Map.empty[String, Option[JsValue]]

  override def receive: Receive = {

    case deployMsg: Deploy =>
      deploy = deployMsg
      val appConfig = ConfigFactory.load()
      stackBucket = appConfig.getString("chadash.stack-bucket")

      val workflowStatus = context.actorOf(WorkflowStatus.props(5), utils.Constants.statusActorName)
      context.watch(workflowStatus)

      workflowStatus ! LogMessage("Starting deployment workflow...")
      sender() ! StartingWorkflow
      become(workflowProcessing)
      self ! Start
  }


  def workflowProcessing: Receive = {
    case x: Deploy =>
      sender() ! DeployInProgress

    case Start =>
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials

    case x: CurrentCredentials =>
      context.unwatch(sender())
      credentials = x.credentials
      steps = stepSequence.foldLeft(Seq.empty[ActorRef])((x, i) => x :+ actorLoader(i, stackBucket))

      steps(currentStep) ! StartStep(deploy.env, deploy.appVersion, deploy.stackName, stepResultData)

    case x: StepFinished =>
      stepResultData + (stepSequence(currentStep) -> x.stepData)

      currentStep = currentStep + 1
      steps.size == currentStep match {
        case true => parent ! DeployCompleted
        case false => steps(currentStep) ! StartStep(deploy.env, deploy.appVersion, deploy.stackName, stepResultData)
      }

    case x: Log =>
      context.child(Constants.statusActorName) match {
        case Some(actor) => actor forward x
        case None => () //TODO: this should never happen, should we raise an exception maybe?
      }

    case Terminated(actorRef) =>
      self ! LogMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      parent ! DeploymentSupervisor.DeployFailed

    case StepFailed =>
      //Step Supervisors should be able to heal themselves if they are able. If we receive a step failed, the whole job fails and needs a human.
      self ! LogMessage(s"The following step has failed: ${stepSequence(currentStep)}}")
      parent ! DeploymentSupervisor.DeployFailed

    case m: Any =>
      log.debug("Unhandled message type received: " + m.toString)
  }

  def actorLoader(configName: String, stackBucket: String): ActorRef = {
    configName match {
      case "loadStackFile" => context.actorOf(LoadStackSupervisor.props(credentials, stackBucket), "loadStackFile")
      case "validateAndFreeze" => context.actorOf(ValidateAndFreezeSupervisor.props(credentials), "validateAndFreeze")
      case _ => null
    }
  }
}

object AWSWorkflow {

  case class StartStep(env: String, appVersion: String, stackName: String, data: Map[String, Option[JsValue]])

  case class StepFinished(stepData: Option[JsValue])

  case object StepFailed

  case object Start

  case object DeployInProgress

  case object DeployCompleted

  case object StartingWorkflow

}