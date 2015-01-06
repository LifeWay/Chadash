package actors.workflow.steps.validateandfreeze

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class GetASGName(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.validateandfreeze.GetASGName._

  override def receive: Receive = {
    case query: GetASGNameForStackName =>

      val describeStackRequest = new DescribeStacksRequest()
        .withStackName(query.stackName)

      val awsClient = new AmazonCloudFormationClient(credentials)
      val stacksResults = awsClient.describeStacks(describeStackRequest).getStacks.asScala.toSeq

      stacksResults.length match {
        case 1 =>
          val stackOutputs = stacksResults.seq(0).getOutputs.asScala.toSeq
          val asgOutput = stackOutputs.filter(p => p.getOutputKey.equals("ChadashASG"))
          asgOutput.length match {
            case 1 => context.parent ! ASGForStack(asgOutput(0).getOutputValue)
            case _ => throw new UnsupportedOperationException("missing ChadashASG output")
          }
        case _ => throw new UnsupportedOperationException("expected only one stack!")
      }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted because of ${reason.getMessage}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    message.get match {
      case x: GetASGNameForStackName => context.system.scheduler.scheduleOnce(15.seconds, self, x)
      case _ => log.warning("Actor restarting, but message is not being replayed.")
    }
  }
}

object GetASGName {

  case class GetASGNameForStackName(stackName: String)

  case class ASGForStack(asgName: String)

  def props(credentials: AWSCredentials): Props = Props(new GetASGName(credentials))
}

