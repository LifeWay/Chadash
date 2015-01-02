package actors.workflow.steps.stackloader

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3Object
import org.apache.commons.io.IOUtils
import play.api.libs.json.{JsValue, Json}

class StackLoader(credentials: AWSCredentials, bucketName: String) extends Actor with ActorLogging {

  import actors.workflow.steps.stackloader.StackLoader._

  override def receive: Receive = {
    case msg: LoadStack =>
      val s3Client = new AmazonS3Client(credentials)
      val stackObject: S3Object = s3Client.getObject(bucketName, s"chadash-asg-stacks/${msg.env}/${msg.stackName}.json")
      val stackFileJson = Json.parse(IOUtils.toByteArray(stackObject.getObjectContent))

      context.sender() !  StackLoaded(stackFileJson)
  }
}

object StackLoader {

  case class LoadStack(env: String, stackName: String)

  case class StackLoaded(stackJson: JsValue)

  def props(creds: AWSCredentials, bucketName: String): Props = Props(new StackLoader(creds, bucketName))
}
