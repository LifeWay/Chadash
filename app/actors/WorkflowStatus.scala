package actors

import akka.actor.{Actor, ActorLogging, Props}

class WorkflowStatus(val totalSteps: Int) extends Actor with ActorLogging {

  import actors.WorkflowStatus._

  var stepsCompleted: Int = 0
  var logs = Seq.empty[String]

  override def receive: Receive = {
    case x: LogMessage => {
      log.debug(x.msg)
    }
    case x: ItemFinished => {
      log.debug(x.msg)
      stepsCompleted = stepsCompleted + 1
    }
    case GetStatus => {
      sender() ! Status(stepsCompleted / totalSteps, logs)
    }
  }
}

object WorkflowStatus {

  case class LogMessage(msg: String)

  case class ItemFinished(msg: String)

  case object GetStatus

  case class Status(percentComplete: Float, logMessages: Seq[String])

  def props(totalSteps: Int): Props = Props(new WorkflowStatus(totalSteps))
}
