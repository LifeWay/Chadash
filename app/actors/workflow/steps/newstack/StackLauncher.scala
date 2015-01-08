package actors.workflow.steps.newstack

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.{CreateStackRequest, Parameter, Tag}
import play.api.libs.json.JsValue

class StackLauncher(credentials: AWSCredentials) extends Actor with ActorLogging {

  import actors.workflow.steps.newstack.StackLauncher._

  override def receive: Receive = {
    case launchCommand: LaunchStack =>

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
      val result = awsClient.createStack(createStackRequest)

      context.parent ! StackLaunched(updatedStackName, result.getStackId)
  }
}

object StackLauncher {

  case class LaunchStack(stackName: String, imageId: String, version: String, stackData: JsValue)

  case class StackLaunched(stackName: String, stackId: String)

  def props(credentials: AWSCredentials): Props = Props(new StackLauncher(credentials))

  val stackNamePattern = "[^\\w-]".r
}
