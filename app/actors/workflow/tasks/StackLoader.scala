package actors.workflow.tasks

import actors.workflow.AWSRestartableActor
import akka.actor.Props
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3Object
import org.apache.commons.io.IOUtils
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import utils.{AmazonS3Service, PropFactory}

class StackLoader(credentials: AWSCredentialsProvider, bucketName: String) extends AWSRestartableActor with AmazonS3Service {

  import actors.workflow.tasks.StackLoader._

  override def receive: Receive = {
    case msg: LoadStack =>
      val client = s3Client(credentials)
      val stackObject: S3Object = client.getObject(bucketName, s"chadash-stacks/${msg.stackPath}.json")
      val stackFileJson = Json.parse(IOUtils.toByteArray(stackObject.getObjectContent))

      context.parent ! StackLoaded(stackFileJson)
  }
}

object StackLoader extends PropFactory {
  case class LoadStack(stackPath: String)
  case class StackLoaded(stackJson: JsValue)

  override def props(args: Any*): Props = Props(classOf[StackLoader], args: _*)
}
