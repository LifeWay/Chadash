package actors.workflow

import actors.workflow.AmazonCredentials.CurrentCredentials
import actors.DeploymentSupervisor.Deploy
import actors.WorkflowLog._
import actors._
import actors.workflow.WorkflowManager.{WorkflowData, WorkflowState}
import actors.workflow.steps.DeleteStackSupervisor.{DeleteExistingStack, DeleteExistingStackFinished}
import actors.workflow.steps.LoadStackSupervisor.{LoadStackCommand, LoadStackResponse}
import actors.workflow.steps.NewStackSupervisor._
import actors.workflow.steps.RollBackStackSupervisor.{RollBackCompleted, RollBackDeleteStackAndUnfreeze}
import actors.workflow.steps.TearDownSupervisor.{TearDownCommand, TearDownFinished}
import actors.workflow.steps.ValidateAndFreezeSupervisor._
import actors.workflow.steps._
import akka.actor._
import com.amazonaws.auth.AWSCredentials
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json
import utils.{ActorFactory, PropFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkflowManager(logActor: ActorRef, actorFactory: ActorFactory) extends FSM[WorkflowState, WorkflowData]
                                                                              with ActorLogging {

  import actors.workflow.WorkflowManager._

  val appConfig                        = ConfigFactory.load()
  val stackBucket                      = appConfig.getString("chadash.stack-bucket")
  var cancellable: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()
    logActor ! WatchThisWorkflow
  }

  startWith(AwaitingWorkflowStartCommand, Uninitialized)

  when(AwaitingWorkflowStartCommand) {
    case Event(StartDeployWorkflow(data), Uninitialized) =>
      cancellable = Some(context.system.scheduler.scheduleOnce(data.timeout.minutes, self, WorkflowTimeout))
      sender() ! WorkflowStarted
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
      goto(AwaitingAWSCredentials) using DeployData(data)
    case Event(StartDeleteWorkflow(stackName), Uninitialized) =>
      sender() ! WorkflowStarted
      context.watch(ChadashSystem.credentials)
      ChadashSystem.credentials ! AmazonCredentials.RequestCredentials
      goto(AwaitingAWSCredentials) using DeleteData(stackName)
  }

  when(AwaitingAWSCredentials) {
    case Event(CurrentCredentials(creds), DeployData(data)) =>
      context.unwatch(sender())

      val loadStackSupervisor = actorFactory(LoadStackSupervisor, context, "loadStackSupervisor", creds, actorFactory)
      context.watch(loadStackSupervisor)
      loadStackSupervisor ! LoadStackCommand(stackBucket, data.stackPath)
      goto(AwaitingLoadStackResponse) using DeployDataWithCreds(data, creds)

    case Event(CurrentCredentials(creds), DeleteData(stackName)) =>
      context.unwatch(sender())
      val deleteStackSupervisor = actorFactory(DeleteStackSupervisor, context, "deleteStackSupervisor", creds, actorFactory)
      context.watch(deleteStackSupervisor)
      deleteStackSupervisor ! DeleteExistingStack(stackName)
      goto(AwaitingDeleteStackResponse)
  }

  when(AwaitingLoadStackResponse) {
    case Event(LoadStackResponse(stackJson), DeployDataWithCreds(data, creds)) =>
      context.unwatch(sender())
      logActor ! LogMessage("Stack JSON data loaded. Querying for existing stack")

      val stepData: Map[String, String] = Map("stackFileContents" -> stackJson.toString())
      val validateAndFreezeSupervisor = actorFactory(ValidateAndFreezeSupervisor, context, "validateAndFreezeSupervisor", creds, actorFactory)
      context.watch(validateAndFreezeSupervisor)
      validateAndFreezeSupervisor ! ValidateAndFreezeStackCommand(data.stackPath, data.version)
      goto(AwaitingStackVerifier) using DeployDataWithCredsWithSteps(data, creds, stepData)
  }

  when(AwaitingDeleteStackResponse) {
    case Event(DeleteExistingStackFinished, DeleteData(_)) =>
      context.unwatch(sender())
      logActor ! LogMessage("The stack has been deleted")
      logActor ! LogMessage("Delete complete")
      logActor ! WorkflowLog.WorkflowCompleted
      context.parent ! WorkflowCompleted
      stop()
  }

  when(AwaitingStackVerifier) {
    case Event(NoExistingStacksExist, DeployDataWithCredsWithSteps(data, creds, stepData)) =>
      context.unwatch(sender())
      logActor ! LogMessage("No existing stacks found. First-time stack launch")

      val stackLauncher = actorFactory(NewStackSupervisor, context, "newStackSupervisor", creds, actorFactory)
      context.watch(stackLauncher)
      stepData.get("stackFileContents") match {
        case Some(stackFile) =>
          stackLauncher ! NewStackFirstLaunchCommand(data.stackName, data.amiId, data.version, Json.parse(stackFile))
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }
      goto(AwaitingStackLaunched)

    case Event(VerifiedAndStackFrozen(oldStackName, oldASGName), DeployDataWithCredsWithSteps(data, creds, stepData)) =>
      context.unwatch(sender())

      val newStepData = stepData + ("oldStackName" -> oldStackName) + ("oldAsgName" -> oldASGName)
      val stackLauncher = actorFactory(NewStackSupervisor, context, "newStackSupervisor", creds, actorFactory)
      context.watch(stackLauncher)
      stepData.get("stackFileContents") match {
        case Some(stackFile) =>
          stackLauncher ! NewStackUpgradeLaunchCommand(data.stackName, data.amiId, data.version, Json.parse(stackFile), oldStackName, oldASGName)
        case None => throw new Exception("No stack contents found when attempting to deploy")
      }
      goto(AwaitingStackLaunched) using DeployDataWithCredsWithSteps(data, creds, newStepData)

    case Event(StackVersionAlreadyExists, DeployDataWithCredsWithSteps(data, creds, stepData)) =>
      context.unwatch(sender())
      logActor ! LogMessage("Workflow is being stopped - you are trying to redeploy an existing stack version")
      failed()
      stop()
  }

  when(AwaitingStackLaunched) {
    case Event(FirstStackLaunchCompleted(newStackName), DeployDataWithCredsWithSteps(_, _, _)) =>
      context.unwatch(sender())
      logActor ! LogMessage(s"The first version of this stack has been successfully deployed. Stack Name: $newStackName")
      logActor ! WorkflowLog.WorkflowCompleted
      context.parent ! WorkflowCompleted
      stop()

    case Event(StackUpgradeLaunchCompleted(newAsgName), DeployDataWithCredsWithSteps(data, creds, stepData)) =>
      context.unwatch(sender())
      logActor ! LogMessage("The next version of the stack has been successfully deployed.")
      val tearDownSupervisor = actorFactory(TearDownSupervisor, context, "tearDownSupervisor", creds, actorFactory)
      context.watch(tearDownSupervisor)
      stepData.get("oldStackName") match {
        case Some(oldStackName) => tearDownSupervisor ! TearDownCommand(oldStackName, newAsgName)
        case None => throw new Exception("No old stack name found when attempting to tear down old stack")
      }
      goto(AwaitingOldStackTearDown) using DeployDataWithCredsWithSteps(data, creds, stepData)

    case Event(msg: StepFailed, state: DeployDataWithCredsWithSteps) =>
      context.unwatch(sender())
      logActor ! LogMessage(s"The following step has failed: ${context.sender()} for the following reason: ${msg.reason}")
      tearDownFailHandler(state)

    case Event(WorkflowTimeout, state: DeployDataWithCredsWithSteps) =>
      logActor ! LogMessage(s"The workflow has taken longer than allowed to transition. Stopping the transition and throwing up.")
      tearDownFailHandler(state)

    case Event(Terminated(actorRef), state: DeployDataWithCredsWithSteps) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      tearDownFailHandler(state)
  }

  when(AwaitingOldStackTearDown) {
    case Event(TearDownFinished, DeployDataWithCredsWithSteps(_, _, _)) =>
      context.unwatch(sender())
      logActor ! LogMessage("The old stack has been deleted and the new stack's ASG has been unfrozen.")
      logActor ! WorkflowLog.WorkflowCompleted
      context.parent ! WorkflowCompleted
      stop()
    case Event(WorkflowTimeout, state: DeployDataWithCredsWithSteps) =>
      logActor ! LogMessage(s"The workflow has taken longer than allowed to transition, but we are in the final state right now, just waiting on AWS actions. Ignoring.")
      stay()
  }

  when(AwaitingRollBackCompleteResponse) {
    case Event(RollBackCompleted, _) =>
      context.unwatch(sender())
      logActor ! LogMessage("The rollback has completed")
      failed()
    case Event(DeleteExistingStackFinished, _) =>
      context.unwatch(sender())
      logActor ! LogMessage("The stack has been deleted")
      failed()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      logActor forward msg
      stay()

    case Event(msg: StepFailed, _) =>
      context.unwatch(sender())
      logActor ! LogMessage(s"The following step has failed: ${context.sender()} for the following reason: ${msg.reason}")
      failed()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      failed()

    case Event(msg: Any, data: WorkflowData) =>
      log.debug(s"Unhandled message: ${msg.toString} Data: ${data.toString}")
      failed()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
      for (x <- cancellable) yield x.cancel()
    case StopEvent(_, state, data) =>
      log.debug("FSM stopping....")
      for (x <- cancellable) yield x.cancel()
  }

  def tearDownFailHandler(state: DeployDataWithCredsWithSteps): State = {
    //Kill the children so we can handle the rollback workflow without any other AWS transitions attempting to proceed
    context.children foreach { child =>
      context.unwatch(child)
      context.stop(child)
    }

    state.stepData.get("oldAsgName") match {
      case Some(oldAsg) =>
        val rollBackSupervisor = actorFactory(RollBackStackSupervisor, context, "rollBackSupervisor", state.creds, actorFactory)
        context.watch(rollBackSupervisor)
        rollBackSupervisor ! RollBackDeleteStackAndUnfreeze(state.deploy.stackName, oldAsg)
        goto(AwaitingRollBackCompleteResponse)
      case None =>
        logActor ! "Halting first deployment by killing the stack"
        val deleteStackSupervisor = actorFactory(DeleteStackSupervisor, context, "deleteStackSupervisor", state.creds, actorFactory)
        context.watch(deleteStackSupervisor)
        deleteStackSupervisor ! DeleteExistingStack(state.deploy.stackName)
        goto(AwaitingRollBackCompleteResponse)
    }
  }

  def failed() = {
    logActor ! WorkflowLog.WorkflowFailed
    context.parent ! WorkflowFailed(logActor)
    stop()
  }

  initialize()
}

object WorkflowManager extends PropFactory {
  //Interaction Messages
  sealed trait WorkflowMessage
  case class StartDeployWorkflow(deploy: Deploy) extends WorkflowMessage
  case class StartDeleteWorkflow(stackName: String) extends WorkflowMessage
  case object WorkflowStarted extends WorkflowMessage
  case object WorkflowCompleted extends WorkflowMessage
  case class WorkflowFailed(logRef: ActorRef) extends WorkflowMessage
  case class StepFailed(reason: String) extends WorkflowMessage
  case object WorkflowTimeout extends WorkflowMessage

  //FSM: States
  sealed trait WorkflowState
  case object AwaitingWorkflowStartCommand extends WorkflowState
  case object AwaitingAWSCredentials extends WorkflowState
  case object AwaitingLoadStackResponse extends WorkflowState
  case object AwaitingStackVerifier extends WorkflowState
  case object AwaitingStackLaunched extends WorkflowState
  case object AwaitingOldStackTearDown extends WorkflowState
  case object AwaitingDeleteStackResponse extends WorkflowState
  case object AwaitingRollBackCompleteResponse extends WorkflowState

  //FSM: Data
  sealed trait WorkflowData
  case object Uninitialized extends WorkflowData
  case class DeployData(deploy: Deploy) extends WorkflowData
  case class DeployDataWithCreds(deploy: Deploy, creds: AWSCredentials) extends WorkflowData
  case class DeployDataWithCredsWithSteps(deploy: Deploy, creds: AWSCredentials,
                                          stepData: Map[String, String] = Map.empty[String, String]) extends WorkflowData
  case class DeleteData(stackName: String) extends WorkflowData

  def props(args: Any*): Props = Props(classOf[WorkflowManager], args: _*)
}
