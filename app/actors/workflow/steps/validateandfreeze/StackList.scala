package actors.workflow.steps.validateandfreeze

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.ListStacksRequest

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class StackList(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.validateandfreeze.StackList._

  override def receive: Receive = {
    case query: ListNonDeletedStacksStartingWithName =>
      import com.amazonaws.services.cloudformation.model.StackStatus._

      //only consider stacks that are not delete_complete
      val stackStatusFilters = Seq(CREATE_IN_PROGRESS, CREATE_COMPLETE, CREATE_FAILED, ROLLBACK_IN_PROGRESS, ROLLBACK_FAILED, ROLLBACK_COMPLETE,
        DELETE_FAILED, DELETE_IN_PROGRESS, UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, UPDATE_IN_PROGRESS, UPDATE_COMPLETE, UPDATE_ROLLBACK_COMPLETE, UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS,
        UPDATE_ROLLBACK_FAILED, UPDATE_ROLLBACK_IN_PROGRESS)
      val listStackRequests = new ListStacksRequest()
        .withStackStatusFilters(stackStatusFilters.toArray: _*)

      val awsClient = new AmazonCloudFormationClient(credentials)
      val results = awsClient.listStacks(listStackRequests).getStackSummaries.asScala.toSeq
      val filteredResults = results.filter(p => p.getStackName.startsWith(query.stackName))
      val filteredStackNames = filteredResults.foldLeft(Seq.empty[String])((sum, i) => sum :+ i.getStackName)

      context.parent ! FilteredStacks(filteredStackNames)
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: ListNonDeletedStacksStartingWithName => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object StackList {

  case class ListNonDeletedStacksStartingWithName(stackName: String)

  case class FilteredStacks(stackList: Seq[String])

  def props(credentials: AWSCredentials): Props = Props(new StackList(credentials))
}
