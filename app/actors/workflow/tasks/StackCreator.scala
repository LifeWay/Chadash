package actors.workflow.tasks

import actors.DeploymentSupervisor.{StackAndAppVersion, AppVersion, Version}
import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.model.{Capability, CreateStackRequest, Parameter}
import com.amazonaws.services.cloudformation.model.{Tag => AWSTag}
import play.api.libs.json.JsValue
import utils.{AmazonCloudFormationService, PropFactory}

class StackCreator(credentials: AWSCredentialsProvider) extends AWSRestartableActor with AmazonCloudFormationService {

  import actors.workflow.tasks.StackCreator._

  override def receive: Receive = {
    case launchCommand: StackCreateCommand =>
      val appVersionTag = new AWSTag()
                          .withKey("ApplicationVersion")
                          .withValue(launchCommand.version.appVersion)

      val additionalTags: Seq[AWSTag] = launchCommand.tags.getOrElse(Seq.empty[Tag]).map(t => new AWSTag().withKey(t.key).withValue(t.value))

      val tags = (launchCommand.version match {
        case _:AppVersion => Seq(appVersionTag)
        case x:StackAndAppVersion => Seq(appVersionTag, new AWSTag().withKey("StackVersion").withValue(x.stackVersion))
      }) ++ additionalTags

      val params = Seq(
        new Parameter()
        .withParameterKey("ImageId")
        .withParameterValue(launchCommand.imageId),
        new Parameter()
        .withParameterKey("ApplicationVersion")
        .withParameterValue(launchCommand.version.appVersion)
      )

      val createStackRequest = new CreateStackRequest()
                               .withTemplateBody(launchCommand.stackData.toString())
                               .withStackName(launchCommand.stackName)
                               .withTags(tags.toArray: _*)
                               .withParameters(params.toArray: _*)
                               .withCapabilities(Capability.CAPABILITY_IAM)

      val awsClient = cloudFormationClient(credentials)
      awsClient.createStack(createStackRequest)

      context.parent ! StackCreateRequestCompleted
  }
}

object StackCreator extends PropFactory {
  case class StackCreateCommand(stackName: String, imageId: String, version: Version, stackData: JsValue, tags: Option[Seq[Tag]])
  case object StackCreateRequestCompleted

  override def props(args: Any*): Props = Props(classOf[StackCreator], args: _*)
}
