package actors.workflow.aws

import actors.AmazonCredentials.CurrentCredentials
import actors.DeploymentSupervisor.Deploy
import actors.WorkflowStatus.{Log, LogMessage}
import actors.workflow.steps.{ValidateAndFreezeSupervisor, LoadStackSupervisor, NewStackSupervisor}
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
class WorkflowManager extends Actor with ActorLogging {

  import actors.workflow.aws.WorkflowManager._
  import context._

  var deploy: Deploy = null
  var credentials: AWSCredentials = null
  var stackBucket: String = null

  //STEPS
  val stepSequence: Seq[String] = Seq("loadStackFile", "validateAndFreeze", "newStack")
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
    case Start =>
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials

    case x: CurrentCredentials =>
      context.unwatch(sender())
      credentials = x.credentials
      steps = stepSequence.foldLeft(Seq.empty[ActorRef])((x, i) => x :+ actorLoader(i, stackBucket))

      steps(currentStep) ! StartStep(deploy.env, deploy.appVersion, deploy.amiId, deploy.stackName, stepResultData)

    case x: StepFinished =>
      stepResultData = stepResultData + (stepSequence(currentStep) -> x.stepData)
      logMessage(s"Step Completed: ${stepSequence(currentStep)}")

      currentStep = currentStep + 1
      steps.size == currentStep match {
        case true => parent ! DeployCompleted
        case false =>
          logMessage(s"Starting Step: ${stepSequence(currentStep)}")
          steps(currentStep) ! StartStep(deploy.env, deploy.appVersion, deploy.amiId, deploy.stackName, stepResultData)
      }

    case x: Log =>
      logMessage(x.message)

    case Terminated(actorRef) =>
      logMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      parent ! DeploymentSupervisor.DeployFailed

    case msg: StepFailed =>
      //Step Supervisors should be able to heal themselves if they are able. If we receive a step failed, the whole job fails and needs a human.
      logMessage(s"The following step has failed: ${stepSequence(currentStep)}} for the following reason: ${msg.reason}")
      parent ! DeploymentSupervisor.DeployFailed

    case m: Any =>
      log.debug("Unhandled message type received: " + m.toString)
  }

  def logMessage(message: String) =  {
    context.child(Constants.statusActorName) match {
      case Some(actor) => actor ! LogMessage(message)
      case None => log.error(s"Unable to find logging status actor to send log message to. This is an error. Message that would have been delivered: ${message}")
    }
  }

  def actorLoader(configName: String, stackBucket: String): ActorRef = {
    configName match {
      case "loadStackFile" => context.actorOf(LoadStackSupervisor.props(credentials, stackBucket), "loadStackFile")
      case "validateAndFreeze" => context.actorOf(ValidateAndFreezeSupervisor.props(credentials), "validateAndFreeze")
      case "newStack" => context.actorOf(NewStackSupervisor.props(credentials), "newStack")
    }
  }
}

object WorkflowManager {

  case class StartStep(env: String, appVersion: String, stackAmi: String, stackName: String, data: Map[String, Option[JsValue]])

  case class StepFinished(stepData: Option[JsValue])

  case class StepFailed(reason: String)

  case object Start

  case object DeployCompleted

  case object StartingWorkflow

}