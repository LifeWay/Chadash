package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import utils.AmazonCloudFormationService

class DeleteStack(credentials: AWSCredentials) extends AWSRestartableActor with AmazonCloudFormationService {

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

object DeleteStack {
  case class DeleteStackCommand(stackName: String)
  case object StackDeleteRequested

  def props(credentials: AWSCredentials): Props = Props(new DeleteStack(credentials))
}
