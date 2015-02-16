package actors

import akka.actor._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object WorkflowLog {

  sealed trait Log {
    def message: String
  }
  case class LogMessage(message: String) extends Log
  case object WorkflowFailed extends Log {
    def message = "WorkflowFailed"
  }
  case object WorkflowSucceeded extends Log {
    def message = "WorkflowSucceeded"
  }

  case class SubscribeToMe(ref: ActorRef)
  case object WatchMePlease
  case class DeployStatusSubscribeRequest(stackPath: String, appVersion: String)
  case object DeployStatusSubscribeConfirm

  case object TimeToDie
  case object ClearLogAndAvoidDeath

  def props(): Props = Props(new WorkflowLog)
}

/**
 * This actor is intended to be created by your workflow supervisor. Your supervisor will manage this as one of its
 * children, and send any log data to it based on the actions you take inside of your supervisor.
 */
class WorkflowLog extends Actor with ActorLogging {

  import actors.WorkflowLog._

  var workflow: Option[ActorRef] = None
  var logs = Seq.empty[Log]
  var subscribers = Seq.empty[ActorRef]
  var getOutOfDeathFreeCards = 0

  override def receive: Receive = {

    case logItem: LogMessage => logger(logItem)

    case msg @ WorkflowFailed =>
      for (x <- workflow) yield context.unwatch(x)
      context.system.scheduler.scheduleOnce(15.minutes, self, TimeToDie)
      logger(msg)

    case msg @ WorkflowSucceeded =>
      for (x <- workflow) yield context.unwatch(x)
      context.system.scheduler.scheduleOnce(15.minutes, self, TimeToDie)
      logger(msg)

    case x: DeployStatusSubscribeRequest => sender() ! SubscribeToMe(self)

    case DeployStatusSubscribeConfirm =>
      subscribers = subscribers :+ sender()
      context.watch(sender())
      logs.map(sender() ! _)

    case WatchMePlease =>
      workflow = Some(sender())
      context.watch(sender())

    case ClearLogAndAvoidDeath =>
      logs = Seq.empty[Log]
      subscribers.map(_ ! LogMessage("---- WORKFLOW RESTARTING ----"))
      getOutOfDeathFreeCards = getOutOfDeathFreeCards + 1

    case TimeToDie =>
      getOutOfDeathFreeCards match {
        case cards if cards > 0 => getOutOfDeathFreeCards = getOutOfDeathFreeCards - 1
        case _ => context.stop(self)
      }

    case Terminated(actorRef) =>
      for (x <- workflow) yield if (x == actorRef) {
        self ! LogMessage("Workflow actor died unexpectedly")
        self ! WorkflowFailed
        context.system.scheduler.scheduleOnce(15.minutes, self, TimeToDie)
      } else {
        subscribers = subscribers.filter(p => !p.equals(actorRef))
      }
  }

  def logger(logItem: Log) = {
    logs = logs :+ logItem
    log.debug(logItem.message)
    subscribers.map(_ ! logItem)
  }
}