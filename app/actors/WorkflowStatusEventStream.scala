package actors

import actors.WorkflowLog._
import akka.actor._
import models.Event._
import play.api.libs.iteratee.{Concurrent, Enumerator}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WorkflowStatusEventStream(workflowLog: ActorRef) extends Actor {

  import actors.WorkflowStatusEventStream._

  val (enumerator, channel) = Concurrent.broadcast[Event]
  context.watch(workflowLog)

  override def receive: Receive = {
    case FeedRequest =>
      sender ! FeedEnumerator(enumerator)
    case StartFeed =>
      context.system.scheduler.scheduleOnce(1.second, workflowLog, DeployStatusSubscribeConfirm)
    case x: Log =>
      x match {
        case LogMessage(logMessage) => logSender(logMessage, Log)
        case msg @ WorkflowFailed =>
          logSender(msg.message, Fail)
          self ! PoisonPill
        case msg @ WorkflowCompleted =>
          logSender(msg.message, Success)
          self ! PoisonPill
      }

    case Terminated(actor) =>
      logSender("LoggerDied: If this is not expected, this is an error state.", Fail)
      context.stop(self)
  }

  override def postStop() = {
    channel.eofAndEnd()
  }

  def logSender(msg: String, msgType: MsgType) = {
    channel.push(new Event(msgType.name, msg))
  }
}

object WorkflowStatusEventStream {
  sealed trait MsgType {
    val name: String
  }
  case object Success extends MsgType {
    val name = "Success"
  }
  case object Fail extends MsgType {
    val name = "Fail"
  }
  case object Log extends MsgType {
    val name = "Log"
  }

  case object FeedRequest
  case object StartFeed
  case class FeedEnumerator(enumerator: Enumerator[Event])

  def props(workflowStatus: ActorRef) = Props(new WorkflowStatusEventStream(workflowStatus))
}