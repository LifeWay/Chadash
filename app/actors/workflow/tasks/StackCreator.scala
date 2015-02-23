package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.model.{CreateStackRequest, Parameter, Tag}
import play.api.libs.json.JsValue
import utils.{AmazonCloudFormationService, PropFactory}

class StackCreator(credentials: AWSCredentials) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.StackCreator._

  override def receive: Receive = {
    case launchCommand: StackCreateCommand =>
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
                               .withStackName(launchCommand.stackName)
                               .withTags(appVersionTag)
                               .withParameters(params.toArray: _*)

      val awsClient = cloudFormationClient(credentials)
      awsClient.createStack(createStackRequest)

      context.parent ! StackCreateRequestCompleted
  }
}

object StackCreator extends PropFactory {
  case class StackCreateCommand(stackName: String, imageId: String, version: String, stackData: JsValue)
  case object StackCreateRequestCompleted

  override def props(args: Any*): Props = Props(classOf[StackCreator], args: _*)
}
