package actors.workflow.steps

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.HealthyInstanceSupervisor.{HealthyInstanceData, HealthyInstanceMonitorStates}
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import actors.workflow.tasks.ELBHealthyInstanceChecker.{ELBInstanceListAllHealthy, ELBInstanceListNotHealthy, ELBIsInstanceListHealthy}
import actors.workflow.tasks.{ASGInfo, ELBHealthyInstanceChecker}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.FSM.Failure
import akka.actor._
import com.amazonaws.auth.AWSCredentials

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class HealthyInstanceSupervisor(credentials: AWSCredentials, expectedInstances: Int, asgName: String) extends FSM[HealthyInstanceMonitorStates, HealthyInstanceData] with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.HealthyInstanceSupervisor._

  startWith(AwaitingCheckHealthRequest, Uninitialized)

  when(AwaitingCheckHealthRequest) {
    case Event(CheckHealth, _) =>
      val asgInfo = context.actorOf(ASGInfo.props(credentials), "asgInfo")
      context.watch(asgInfo)
      asgInfo ! ASGInServiceInstancesAndELBSQuery(asgName)
      goto(MonitoringASGHealth)
  }

  when(MonitoringASGHealth) {
    case Event(msg: ASGInServiceInstancesAndELBSResult, _) =>
      context.unwatch(sender())
      context.stop(sender())

      msg.instanceIds.length match {
        case i if i >= expectedInstances =>
          if(msg.elbNames.size > 0) {
            stop(Failure("Chadash only supports scenarios with an ASG bound to a single ASG"))
          }
          val elb = msg.elbNames(0)
          val healthChecker = context.actorOf(ELBHealthyInstanceChecker.props(credentials), s"elb-instance-health-$elb")
          context.watch(healthChecker)
          healthChecker ! ELBIsInstanceListHealthy(elb, msg.instanceIds)
          goto(MonitoringELBHealth)

        case _ =>
          context.parent ! LogMessage("Waiting on ASG for all instances to be In Service")
          context.system.scheduler.scheduleOnce(10.seconds, self, CheckHealth)
          goto(AwaitingCheckHealthRequest) using Uninitialized
      }
  }

  when(MonitoringELBHealth) {
    case Event(msg: ELBInstanceListNotHealthy, _) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"${msg.unhealthyInstances} unhealthy instances still exist on ELB: ${msg.elbName}")
      context.system.scheduler.scheduleOnce(10.seconds, self, CheckHealth)
      goto(AwaitingCheckHealthRequest) using Uninitialized

    case Event(msg: ELBInstanceListAllHealthy, _) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"All instances are reporting healthy on ELB: ${msg.elbName}")
      context.parent ! HealthStatusMet
      stop()
  }

  whenUnhandled {
    case Event(msg: Log, _) =>
      context.parent forward msg
      stay()

    case Event(Terminated(actorRef), _) =>
      context.parent ! LogMessage(s"Child of ${this.getClass.getSimpleName} has died unexpectedly. Child Actor: ${actorRef.path.name}")
      context.parent ! WorkflowManager.StepFailed("Failed to monitor health")
      stop()

    case Event(msg: Any, _) =>
      log.debug(s"Unhandled message: ${msg.toString}")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Failure(cause), state, data) =>
      log.error(s"FSM has failed... $cause $state $data")
    case StopEvent(_, _, _) =>
      log.debug("FSM has shutdown")
  }

  initialize()
}

object HealthyInstanceSupervisor {
  //Interaction Messages: None
  sealed trait HealthyInstanceSupervisorMessage
  case object CheckHealth extends HealthyInstanceSupervisorMessage
  case object HealthStatusMet extends HealthyInstanceSupervisorMessage

  //FSM: States
  sealed trait HealthyInstanceMonitorStates
  case object AwaitingCheckHealthRequest extends HealthyInstanceMonitorStates
  case object MonitoringASGHealth extends HealthyInstanceMonitorStates
  case object MonitoringELBHealth extends HealthyInstanceMonitorStates

  //FSM: Data
  sealed trait HealthyInstanceData
  case object Uninitialized extends HealthyInstanceData

  def props(credentials: AWSCredentials, expectedInstances: Int, asgName: String): Props = Props(new HealthyInstanceSupervisor(credentials, expectedInstances, asgName))
}