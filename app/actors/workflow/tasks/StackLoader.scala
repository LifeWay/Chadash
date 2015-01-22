package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3Object
import org.apache.commons.io.IOUtils
import play.api.Logger
import play.api.libs.json.{JsValue, Json}

class StackLoader(credentials: AWSCredentials, bucketName: String) extends Actor with AWSRestartableActor with ActorLogging {

  import actors.workflow.tasks.StackLoader._

  override def receive: Receive = {
    case msg: LoadStack =>
      val s3Client = new AmazonS3Client(credentials)
      val stackObject: S3Object = s3Client.getObject(bucketName, s"chadash-stacks/${msg.stackPath}.json")
      val stackFileJson = Json.parse(IOUtils.toByteArray(stackObject.getObjectContent))

      context.sender() ! StackLoaded(stackFileJson)
  }
}

object StackLoader {

  case class LoadStack(stackPath: String)

  case class StackLoaded(stackJson: JsValue)

  def props(creds: AWSCredentials, bucketName: String): Props = Props(new StackLoader(creds, bucketName))
}
