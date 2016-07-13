package actors.workflow.steps

import java.util.concurrent.TimeUnit

import actors.WorkflowLog.{Log, LogMessage}
import actors.workflow.steps.HealthyInstanceSupervisor.{HealthyInstanceData, HealthyInstanceMonitorStates}
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import actors.workflow.tasks.ELBHealthyInstanceChecker.{ELBInstanceListAllHealthy, ELBInstanceListNotHealthy, ELBIsInstanceListHealthy}
import actors.workflow.tasks.{ASGInfo, ELBHealthyInstanceChecker}
import actors.workflow.{AWSSupervisorStrategy, WorkflowManager}
import akka.actor.FSM.Failure
import akka.actor._
import com.amazonaws.auth.AWSCredentialsProvider
import com.typesafe.config.ConfigFactory
import utils.ConfigHelpers._
import utils.{ActorFactory, PropFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class HealthyInstanceSupervisor(credentials: AWSCredentialsProvider, expectedInstances: Int, asgName: String,
                                actorFactory: ActorFactory) extends FSM[HealthyInstanceMonitorStates, HealthyInstanceData]
                                                                    with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.HealthyInstanceSupervisor._

  val config                           = ConfigFactory.load()
  val configDelay                      = for (x <- config.getOptLong("chadash.intervals.healthcheckmilliseconds")) yield FiniteDuration(x, TimeUnit.MILLISECONDS)
  val healthCheckDelay: FiniteDuration = configDelay.getOrElse(10.seconds)

  startWith(AwaitingCheckHealthRequest, Uninitialized)

  when(AwaitingCheckHealthRequest) {
    case Event(CheckHealth, _) =>
      val asgInfo = actorFactory(ASGInfo, context, "asgInfo", credentials)
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
          if (msg.elbNames.size > 0) {
            stop(Failure("Chadash only supports scenarios with an ASG bound to a single ASG"))
          }
          val elb = msg.elbNames(0)
          val healthChecker = actorFactory(ELBHealthyInstanceChecker, context, s"elb-instance-health-$elb", credentials)
          context.watch(healthChecker)
          healthChecker ! ELBIsInstanceListHealthy(elb, msg.instanceIds)
          goto(MonitoringELBHealth)

        case _ =>
          context.parent ! LogMessage("Waiting on ASG for all instances to be In Service")
          context.system.scheduler.scheduleOnce(healthCheckDelay, self, CheckHealth)
          goto(AwaitingCheckHealthRequest) using Uninitialized
      }
  }

  when(MonitoringELBHealth) {
    case Event(msg: ELBInstanceListNotHealthy, _) =>
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"${msg.unhealthyInstances} unhealthy instances still exist on ELB: ${msg.elbName}")
      context.system.scheduler.scheduleOnce(healthCheckDelay, self, CheckHealth)
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
  }

  initialize()
}

object HealthyInstanceSupervisor extends PropFactory {
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


  override def props(args: Any*): Props = Props(classOf[HealthyInstanceSupervisor], args: _*)
}
