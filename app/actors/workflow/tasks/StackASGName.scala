package actors.workflow.tasks

import actors.workflow.RestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest

import scala.collection.JavaConverters._

class StackASGName(credentials: AWSCredentials) extends Actor with RestartableActor with ActorLogging {

  import actors.workflow.tasks.StackASGName._

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
            case 1 => context.sender() ! StackASGNameReponse(asgOutput(0).getOutputValue)
            case _ => throw new UnsupportedOperationException("missing ChadashASG output")
          }
        case _ => throw new UnsupportedOperationException("expected only one stack!")
      }
  }
}

object StackASGName {

  case class StackASGNameQuery(stackName: String)

  case class StackASGNameReponse(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new StackASGName(credentials))
}

