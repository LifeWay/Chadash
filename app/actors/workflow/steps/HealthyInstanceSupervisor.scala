package actors.workflow.steps

import java.util.UUID

import actors.WorkflowStatus.LogMessage
import actors.workflow.AWSSupervisorStrategy
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import actors.workflow.tasks.ELBHealthyInstanceChecker.{ELBInstanceListAllHealthy, ELBInstanceListNotHealthy, ELBIsInstanceListHealthy}
import actors.workflow.tasks.{ASGInfo, ELBHealthyInstanceChecker}
import akka.actor.{Terminated, Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class HealthyInstanceSupervisor(credentials: AWSCredentials, expectedInstances: Int, asgName: String) extends Actor with ActorLogging with AWSSupervisorStrategy {

  import actors.workflow.steps.HealthyInstanceSupervisor._

  var elbs: Seq[String] = Seq.empty[String]
  var healthyElbs: Int = 0
  var unhealthyElbs: Int = 0

  override def receive: Receive = {
    case MonitorASGForELBHealth =>
      val asgInfo = context.actorOf(ASGInfo.props(credentials), "asgInfo")
      context.watch(asgInfo)
      context.parent ! LogMessage("Getting ASG Info...")
      asgInfo ! ASGInServiceInstancesAndELBSQuery(asgName)
      context.become(inProcess())
  }

  def inProcess(): Receive = {
    case msg: ASGInServiceInstancesAndELBSResult =>
      context.stop(sender())
      context.unwatch(sender())

      msg.instanceIds.length match {
        case i if i >= expectedInstances =>
          elbs = msg.elbNames
          elbs.map { elb =>
            val healthChecker = context.actorOf(ELBHealthyInstanceChecker.props(credentials), s"elb-instance-health-$elb")
            context.watch(healthChecker)
            healthChecker ! ELBIsInstanceListHealthy(elb, msg.instanceIds)
          }
        case _ =>
          context.parent ! LogMessage("Waiting on ASG for all instances to be In Service")
          resetAndRestart()
      }

    case msg: ELBInstanceListNotHealthy =>
      unhealthyElbs = unhealthyElbs + 1
      context.unwatch(sender())
      context.stop(sender())
      context.parent ! LogMessage(s"An ELB still has ${msg.unhealthyInstances} unhealthy instances")
      checkAndMaybeRestart()

    case ELBInstanceListAllHealthy =>
      healthyElbs = healthyElbs + 1
      context.unwatch(sender())
      context.stop(sender())
      checkAndMaybeRestart()

    case Terminated(actorRef) =>
      context.parent ! LogMessage(s"Child Actor died unexpectedly. Restarting process and killing my children.")
      context.children.map { child =>
        context.unwatch(child)
        context.stop(child)
      }
      resetAndRestart()
  }

  def checkAndMaybeRestart() = {
    if ((unhealthyElbs + healthyElbs) == elbs.size) {
      (unhealthyElbs, healthyElbs) match {
        case (0, _) => context.parent ! HealthStatusMet
        case (_, _) => resetAndRestart()
      }
    }
  }

  def resetAndRestart(): Unit = {
    healthyElbs = 0
    unhealthyElbs = 0
    context.unbecome()
    context.system.scheduler.scheduleOnce(5.seconds, self, MonitorASGForELBHealth)
  }
}

object HealthyInstanceSupervisor {

  case object MonitorASGForELBHealth

  case object HealthStatusMet

  def props(credentials: AWSCredentials, expectedInstances: Int, asgName: String): Props = Props(new HealthyInstanceSupervisor(credentials, expectedInstances, asgName))
}