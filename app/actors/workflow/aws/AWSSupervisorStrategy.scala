package actors.workflow.aws

import actors.WorkflowStatus.LogMessage
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorLogging, OneForOneStrategy}
import com.amazonaws.{AmazonClientException, AmazonServiceException}

import scala.concurrent.duration._

trait AWSSupervisorStrategy extends Actor with ActorLogging {
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 20, withinTimeRange = 5.minutes, loggingEnabled = false) {
    case ex: AmazonServiceException=> {
      context.parent ! LogMessage(ex.toString)
      log.error(ex, "Unrecoverable Amazon Exception")
      Stop
    }
    case _: AmazonClientException => Restart
    case ex: Exception => {
      context.parent ! LogMessage(ex.toString)
      log.error(ex, "Catch-all Exception Handler.")
      Stop
    }
  }
}
