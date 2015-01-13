package actors.workflow.tasks

import actors.workflow.RestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest

import scala.collection.JavaConverters._

class StackInfo(credentials: AWSCredentials) extends Actor with RestartableActor with ActorLogging {

  import actors.workflow.tasks.StackInfo._

  override def receive: Receive = {
    case query: StackASGNameQuery =>
      val describeStackRequest = new DescribeStacksRequest()
        .withStackName(query.stackName)

      val awsClient = new AmazonCloudFormationClient(credentials)
      val stacksResults = awsClient.describeStacks(describeStackRequest).getStacks.asScala.toSeq

      stacksResults.length match {
        case 1 =>
          val stackOutputs = stacksResults.seq(0).getOutputs.asScala.toSeq
          val asgOutput = stackOutputs.filter(p => p.getOutputKey.equals("ChadashASG"))
          asgOutput.length match {
            case 1 => context.sender() ! StackASGNameResponse(asgOutput(0).getOutputValue)
            case _ => throw new UnsupportedOperationException("missing ChadashASG output")
          }
        case _ => throw new UnsupportedOperationException("expected only one stack!")
      }

    case msg: StackIdQuery =>
      val describeStacksRequest = new DescribeStacksRequest()
        .withStackName(msg.stackName)

      val awsClient = new AmazonCloudFormationClient(credentials)
      val stacksResults = awsClient.describeStacks(describeStacksRequest).getStacks.asScala.toSeq

      stacksResults.length match {
        case 1 =>
          val stackId = stacksResults.seq(0).getStackId
          context.sender() ! StackIdResponse(stackId)
        case _ => throw new UnsupportedOperationException("expected only one stack!")
      }
  }
}

object StackInfo {

  case class StackASGNameQuery(stackName: String)

  case class StackASGNameResponse(asgName: String)

  case class StackIdQuery(stackName: String)

  case class StackIdResponse(stackId: String)

  def props(credentials: AWSCredentials): Props = Props(new StackInfo(credentials))
}

