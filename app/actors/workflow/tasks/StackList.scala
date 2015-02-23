package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.model.ListStacksRequest
import utils.{AmazonCloudFormationService, PropFactory}

import scala.collection.JavaConverters._

class StackList(credentials: AWSCredentials) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.StackList._

  override def receive: Receive = {
    case query: ListNonDeletedStacksStartingWithName =>
      import com.amazonaws.services.cloudformation.model.StackStatus._

      //only consider stacks that are not in the set of: delete_complete, delete_failed
      val stackStatusFilters = Seq(CREATE_IN_PROGRESS, CREATE_COMPLETE, CREATE_FAILED, ROLLBACK_IN_PROGRESS, ROLLBACK_FAILED, ROLLBACK_COMPLETE,
        DELETE_IN_PROGRESS, UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, UPDATE_IN_PROGRESS, UPDATE_COMPLETE, UPDATE_ROLLBACK_COMPLETE, UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS,
        UPDATE_ROLLBACK_FAILED, UPDATE_ROLLBACK_IN_PROGRESS)
      val listStackRequests = new ListStacksRequest()
                              .withStackStatusFilters(stackStatusFilters.toArray: _*)

      val awsClient = cloudFormationClient(credentials)
      val results = awsClient.listStacks(listStackRequests).getStackSummaries.asScala.toSeq
      val filteredResults = results.filter(p => p.getStackName.startsWith(query.stackName))
      val filteredStackNames = filteredResults.foldLeft(Seq.empty[String])((sum, i) => sum :+ i.getStackName)

      context.parent ! FilteredStacks(filteredStackNames)
  }
}

object StackList extends PropFactory {
  case class ListNonDeletedStacksStartingWithName(stackName: String)
  case class FilteredStacks(stackList: Seq[String])

  override def props(args: Any*): Props = Props(classOf[StackList], args: _*)
}
