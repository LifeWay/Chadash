package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import utils.{AmazonCloudFormationService, PropFactory}

class DeleteStack(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.DeleteStack._

  override def receive: Receive = {
    case msg: DeleteStackCommand =>
      val delStackReq = new DeleteStackRequest()
                        .withStackName(msg.stackName)

      val awsClient = cloudFormationClient(credentials)
      awsClient.deleteStack(delStackReq)

      context.parent ! StackDeleteRequested
  }
}

object DeleteStack extends PropFactory {
  case class DeleteStackCommand(stackName: String)
  case object StackDeleteRequested

  override def props(args: Any*): Props = Props(classOf[DeleteStack], args: _*)
}
