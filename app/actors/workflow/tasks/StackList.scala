package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{StackSummary, ListStacksRequest}
import com.amazonaws.services.cloudformation.model.StackStatus._
import utils.{AmazonCloudFormationService, PropFactory}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

class StackList(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.StackList._

  override def receive: Receive = {
    case query: ListNonDeletedStacksStartingWithName =>

      val listStackRequest = new ListStacksRequest()
                              .withStackStatusFilters(stackStatusFilters.toArray: _*)

      val awsClient = cloudFormationClient(credentials)
      val results = getStackSummaries(awsClient, listStackRequest)
      val filteredResults = results.filter(p => p.getStackName.startsWith(query.stackName))
      val filteredStackNames = filteredResults.foldLeft(Seq.empty[String])((sum, i) => sum :+ i.getStackName)

      context.parent ! FilteredStacks(filteredStackNames)
  }

  def getStackSummaries(awsClient: AmazonCloudFormation, listStacksRequest: ListStacksRequest): Seq[StackSummary] = {
    @tailrec
    def impl(awsClient: AmazonCloudFormation, listStacksRequest: ListStacksRequest, sum: Seq[StackSummary]): Seq[StackSummary] = {
      val results = awsClient.listStacks(listStacksRequest)
      val pageSummaries = results.getStackSummaries.asScala.toSeq
      val totalSummaries = sum ++ pageSummaries
      if (results.getNextToken == null) {
        totalSummaries
      } else {
        val nextPageRequest = listStacksRequest.withNextToken(results.getNextToken)
        impl(awsClient, nextPageRequest, totalSummaries)
      }
    }
    impl(awsClient, listStacksRequest, Seq.empty[StackSummary])
  }
}

object StackList extends PropFactory {
  //only consider stacks that are not in the set of: delete_complete, delete_failed
  val stackStatusFilters = Seq(CREATE_IN_PROGRESS, CREATE_COMPLETE, CREATE_FAILED, ROLLBACK_IN_PROGRESS, ROLLBACK_FAILED, ROLLBACK_COMPLETE,
    DELETE_IN_PROGRESS, UPDATE_COMPLETE_CLEANUP_IN_PROGRESS, UPDATE_IN_PROGRESS, UPDATE_COMPLETE, UPDATE_ROLLBACK_COMPLETE, UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS,
    UPDATE_ROLLBACK_FAILED, UPDATE_ROLLBACK_IN_PROGRESS)

  case class ListNonDeletedStacksStartingWithName(stackName: String)
  case class FilteredStacks(stackList: Seq[String])

  override def props(args: Any*): Props = Props(classOf[StackList], args: _*)
}
