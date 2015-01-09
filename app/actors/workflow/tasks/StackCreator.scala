package actors.workflow.tasks

import actors.workflow.RestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.{CreateStackRequest, Parameter, Tag}
import play.api.libs.json.JsValue

class StackCreator(credentials: AWSCredentials) extends Actor with RestartableActor with ActorLogging {

  import actors.workflow.tasks.StackCreator._

  override def receive: Receive = {
    case launchCommand: StackCreateCommand =>

      val stackNameWithVersion = s"${launchCommand.stackName}-v${launchCommand.version}"
      val updatedStackName = stackNamePattern.replaceAllIn(stackNameWithVersion, "-")
      val appVersionTag = new Tag()
        .withKey("ApplicationVersion")
        .withValue(launchCommand.version)

      val params = Seq(
        new Parameter()
          .withParameterKey("ImageId")
          .withParameterValue(launchCommand.imageId),
        new Parameter()
          .withParameterKey("ApplicationVersion")
          .withParameterValue(launchCommand.version)
      )

      val createStackRequest = new CreateStackRequest()
        .withTemplateBody(launchCommand.stackData.toString())
        .withStackName(updatedStackName)
        .withTags(appVersionTag)
        .withParameters(params.toArray: _*)


      val awsClient = new AmazonCloudFormationClient(credentials)
      awsClient.createStack(createStackRequest)

      context.sender() ! StackCreateRequestCompleted(updatedStackName)
  }
}

object StackCreator {

  case class StackCreateCommand(stackName: String, imageId: String, version: String, stackData: JsValue)

  case class StackCreateRequestCompleted(stackName: String)

  def props(credentials: AWSCredentials): Props = Props(new StackCreator(credentials))

  val stackNamePattern = "[^\\w-]".r
}
