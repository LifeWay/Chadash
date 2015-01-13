package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.DeleteStackRequest

class DeleteStack(credentials: AWSCredentials) extends Actor with AWSRestartableActor with ActorLogging {

  import actors.workflow.tasks.DeleteStack._

  override def receive: Receive = {

    case msg: DeleteStackCommand =>

      val delStackReq = new DeleteStackRequest()
        .withStackName(msg.stackName)

      val awsClient = new AmazonCloudFormationClient(credentials)
      awsClient.deleteStack(delStackReq)

      context.sender() ! StackDeletedResponse(msg.stackName)
  }
}

object DeleteStack {

  case class DeleteStackCommand(stackName: String)

  case class StackDeletedResponse(stackName: String)

  def props(credentials: AWSCredentials): Props = Props(new DeleteStack(credentials))
}
