package actors.workflow

import actors.AmazonCredentials.CurrentCredentials
import actors.DeploymentSupervisor.Deploy
import actors.WorkflowStatus.{DeployStatusSubscribeRequest, Log, LogMessage}
import actors.workflow.steps.LoadStackSupervisor.{LoadStackQuery, LoadStackResponse}
import actors.workflow.steps.NewStackSupervisor.{FirstStackLaunch, FirstStackLaunchCompleted, StackUpgradeLaunch, StackUpgradeLaunchCompleted}
import actors.workflow.steps.TearDownSupervisor.{TearDownFinished, StartTearDown}
import actors.workflow.steps.ValidateAndFreezeSupervisor.{NoOldStackExists, VerifiedAndStackFrozen, VerifyAndFreezeOldStack}
import actors.workflow.steps.{TearDownSupervisor, LoadStackSupervisor, NewStackSupervisor, ValidateAndFreezeSupervisor}
import actors.{AmazonCredentials, ChadashSystem, DeploymentSupervisor, WorkflowStatus}
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json

/**
 * One WorkflowManager per in-process stackname / env combo.
 * Responsible for chaining together supervisors that manage tasks to get the deployment done.
 *
 * This actor should not call any actors directly that may crash due to amazon calls. A supervisor should
 * be between them that can handle the restarts for those calls.
 *
 */
class WorkflowManager(deploy: Deploy) extends Actor with ActorLogging {

  import actors.workflow.WorkflowManager._

  val statusActorName = "WorkFlowStatus"
  val appConfig = ConfigFactory.load()
  val stackBucket = appConfig.getString("chadash.stack-bucket")

  var workflowStepData = Map.empty[String, String]
  var awsCreds: AWSCredentials = null

  /**
   * Start up the status actor before we start processing anything.
   */
  override def preStart(): Unit = {
    val workflowStatus = context.actorOf(WorkflowStatus.props(5), statusActorName)
    context.watch(workflowStatus)
  }

  override def receive: Receive = {
    case StartDeploy =>
      sender() ! DeployStarted
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
      context.become(inProcess())
  }

  def inProcess(): Receive = {
    case msg: CurrentCredentials =>
      context.unwatch(sender())
      awsCreds = msg.credentials

      val loadStackSupervisor = context.actorOf(LoadStackSupervisor.props(awsCreds), "loadStackSupervisor")
      context.watch(loadStackSupervisor)
      loadStackSupervisor ! LoadStackQuery(stackBucket, deploy.stackName)

    case msg: LoadStackResponse =>
      workflowStepData = workflowStepData + ("stackFileContents" -> msg.stackData.toString())
      logMessage("Stack JSON data loaded. Querying for existing stack")
      val validateAndFreezeSupervisor = context.actorOf(ValidateAndFreezeSupervisor.props(awsCreds), "validateAndFreezeSupervisor")
      context.watch(validateAndFreezeSupervisor)
      validateAndFreezeSupervisor ! VerifyAndFreezeOldStack(deploy.stackName)

    case NoOldStackExists =>
      context.unwatch(sender())
      context.stop(sender())
      logMessage("No existing stacks found. First-time stack launch")

      val stackLauncher = context.actorOf(NewStackSupervisor.props(awsCreds))
      context.watch(stackLauncher)
      workflowStepData.get("stackFileContents") match {
        case Some(stackFile) => stackLauncher ! FirstStackLaunch(deploy.stackName, deploy.amiId, deploy.appVersion, Json.parse(stackFile))
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }

    case msg: VerifiedAndStackFrozen =>
      context.unwatch(sender())
      context.stop(sender())

      workflowStepData = workflowStepData + ("oldStackName" -> msg.oldStackName)
      val stackLauncher = context.actorOf(NewStackSupervisor.props(awsCreds))
      context.watch(stackLauncher)
      workflowStepData.get("stackFileContents") match {
        case Some(stackFile) => stackLauncher ! StackUpgradeLaunch(deploy.stackName, deploy.amiId, deploy.appVersion, Json.parse(stackFile), msg.oldStackName, msg.oldASGName)
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }

    case msg: FirstStackLaunchCompleted =>
      context.unwatch(sender())
      context.stop(sender())
      logMessage(s"The first version of this stack has been successfully deployed. Stack Name: ${msg.newStackName}")
      context.parent ! DeployCompleted

    case msg: StackUpgradeLaunchCompleted =>
      context.unwatch(sender())
      context.stop(sender())
      logMessage(s"The next version of the stack has been successfully deployed.")

      val tearDownSupervisor = context.actorOf(TearDownSupervisor.props(awsCreds), "tearDownSupervisor")
      context.watch(tearDownSupervisor)

      workflowStepData.get("oldStackName") match {
        case Some(oldStackName) => tearDownSupervisor ! StartTearDown(oldStackName, msg.newAsgName)
        case None => throw new Exception("No old stack name found when attempting to tear down old stack")
      }

    case TearDownFinished =>
      context.unwatch(sender())

      logMessage("The old stack has been deleted and the new stack's ASG has been unfrozen.")
      logMessage("Deploy complete")
      context.parent ! DeployCompleted

    case msg: DeployStatusSubscribeRequest =>
      context.child(statusActorName).get forward msg

    case msg: Log =>
      logMessage(msg.message)

    case msg: StepFailed =>
      logMessage(s"The following child has failed: ${context.sender()} for the following reason: ${msg.reason}")
      context.parent ! DeploymentSupervisor.DeployFailed

    case Terminated(actorRef) =>
      logMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! DeploymentSupervisor.DeployFailed

    case msg: Any =>
      log.debug("Unhandled message type received: " + msg.toString)
  }

  def logMessage(message: String) = {
    context.child(statusActorName) match {
      case Some(actor) => actor ! LogMessage(message)
      case None => log.error(s"Unable to find logging status actor to send log message to. This is an error. Message that would have been delivered: ${message}")
    }
  }
}

object WorkflowManager {

  case class StepFailed(reason: String)

  case object StartDeploy

  case object DeployStarted

  case object DeployCompleted

  def props(deploy: Deploy): Props = Props(new WorkflowManager(deploy: Deploy))

}