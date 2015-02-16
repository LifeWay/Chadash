package actors.workflow

import actors.AmazonCredentials.CurrentCredentials
import actors.DeploymentSupervisor.Deploy
import actors.workflow.steps.DeleteStackSupervisor.{DeleteExistingStackFinished, DeleteExistingStack}
import actors.WorkflowLog._
import actors.workflow.steps.LoadStackSupervisor.{LoadStackCommand, LoadStackResponse}
import actors.workflow.steps.NewStackSupervisor.{FirstStackLaunch, FirstStackLaunchCompleted, StackUpgradeLaunch, StackUpgradeLaunchCompleted}
import actors.workflow.steps.TearDownSupervisor.{TearDownFinished, StartTearDown}
import actors.workflow.steps.ValidateAndFreezeSupervisor.{NoOldStackExists, VerifiedAndStackFrozen, VerifyAndFreezeOldStack}
import actors.workflow.steps._
import actors._
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
class WorkflowManager(logActor: ActorRef) extends Actor with ActorLogging {

  import actors.workflow.WorkflowManager._

  val appConfig = ConfigFactory.load()
  val stackBucket = appConfig.getString("chadash.stack-bucket")

  var workflowStepData = Map.empty[String, String]
  var awsCreds: AWSCredentials = null
  var deploy: Deploy = null
  var existingStrack: String = null

  /**
   * Tell the logging actor about me before we get going.
   */
  override def preStart(): Unit = {
    logActor ! WatchMePlease
  }

  override def receive: Receive = {
    case msg: StartDeploy =>
      deploy = msg.deploy
      sender() ! DeployStarted
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
      context.become(inProcess())

    case msg: DeleteExistingStack =>
      existingStrack = msg.stackName
      sender() ! StackDeleteStarted
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
      context.become(deleteInProcess())
  }

  def deleteInProcess(): Receive = {
    case msg: CurrentCredentials =>
      context.unwatch(sender())
      awsCreds = msg.credentials

      val deleteStackSupervisor = context.actorOf(DeleteStackSupervisor.props(awsCreds), "deleteStackSupervisor")
      context.watch(deleteStackSupervisor)
      deleteStackSupervisor ! DeleteExistingStack(existingStrack)

    case DeleteExistingStackFinished =>
      context.unwatch(sender())

      logMessage("The stack has been deleted")
      logMessage("Delete complete")
      context.parent ! StackDeleteCompleted
      logActor ! WorkflowSucceeded

    case msg: LogMessage =>
      logMessage(msg.message)

    case msg: StepFailed =>
      logMessage(s"The following child has failed: ${context.sender().path.name} for the following reason: ${msg.reason}")
      context.unwatch(sender())
      context.parent ! DeploymentSupervisor.DeployFailed
      logActor ! WorkflowFailed

    case Terminated(actorRef) =>
      logMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      context.parent ! DeploymentSupervisor.DeployFailed
      logActor ! WorkflowFailed

    case msg: Any =>
      log.debug("Unhandled message type received: " + msg.toString)
  }

  def inProcess(): Receive = {
    case msg: CurrentCredentials =>
      context.unwatch(sender())
      awsCreds = msg.credentials

      val loadStackSupervisor = context.actorOf(LoadStackSupervisor.props(awsCreds), "loadStackSupervisor")
      context.watch(loadStackSupervisor)
      loadStackSupervisor ! LoadStackCommand(stackBucket, deploy.stackPath)

    case msg: LoadStackResponse =>
      context.unwatch(sender())
      logMessage("Stack JSON data loaded. Querying for existing stack")
      workflowStepData = workflowStepData + ("stackFileContents" -> msg.stackData.toString())
      val validateAndFreezeSupervisor = context.actorOf(ValidateAndFreezeSupervisor.props(awsCreds), "validateAndFreezeSupervisor")
      context.watch(validateAndFreezeSupervisor)
      val stackName = deploy.stackPath.replaceAll("/", "-")
      validateAndFreezeSupervisor ! VerifyAndFreezeOldStack(stackName)

    case NoOldStackExists =>
      context.unwatch(sender())
      context.stop(sender())
      logMessage("No existing stacks found. First-time stack launch")

      val stackLauncher = context.actorOf(NewStackSupervisor.props(awsCreds))
      context.watch(stackLauncher)
      workflowStepData.get("stackFileContents") match {
        case Some(stackFile) =>
          val stackName = deploy.stackPath.replaceAll("/", "-")
          stackLauncher ! FirstStackLaunch(stackName, deploy.amiId, deploy.appVersion, Json.parse(stackFile))
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }

    case msg: VerifiedAndStackFrozen =>
      context.unwatch(sender())
      context.stop(sender())

      workflowStepData = workflowStepData + ("oldStackName" -> msg.oldStackName)
      val stackLauncher = context.actorOf(NewStackSupervisor.props(awsCreds))
      context.watch(stackLauncher)
      workflowStepData.get("stackFileContents") match {
        case Some(stackFile) =>
          val stackName = deploy.stackPath.replaceAll("/", "-")
          stackLauncher ! StackUpgradeLaunch(stackName, deploy.amiId, deploy.appVersion, Json.parse(stackFile), msg.oldStackName, msg.oldASGName)
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }

    case msg: FirstStackLaunchCompleted =>
      context.unwatch(sender())
      context.stop(sender())
      logMessage(s"The first version of this stack has been successfully deployed. Stack Name: ${msg.newStackName}")
      context.parent ! DeployCompleted
      logActor ! WorkflowSucceeded

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
      logActor ! WorkflowSucceeded

    case msg: LogMessage =>
      logMessage(msg.message)

    case msg: StepFailed =>
      logMessage(s"The following child has failed: ${context.sender()} for the following reason: ${msg.reason}")
      failed()

    case Terminated(actorRef) =>
      logMessage(s"Child actor has died unexpectedly. Need a human! Details: ${actorRef.toString()}")
      failed()

    case msg: Any =>
      log.debug("Unhandled message type received: " + msg.toString)
  }

  def failed() = {
    context.parent ! DeploymentSupervisor.DeployFailed(logActor)
    logActor ! WorkflowFailed
  }

  def logMessage(message: String) = logActor ! LogMessage(message)
}

object WorkflowManager {

  case class StepFailed(reason: String)

  case class StartDeploy(deploy: Deploy)

  case object DeployStarted

  case object DeploySuccessful

  case object DeployFailed

  case object DeployCompleted

  case object StackDeleteStarted

  case object StackDeleteCompleted

  def props(logActor: ActorRef): Props = Props(new WorkflowManager(logActor))

}