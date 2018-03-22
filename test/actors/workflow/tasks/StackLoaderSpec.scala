package actors.workflow.tasks

import java.io.ByteArrayInputStream

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackLoader.{LoadStack, StackLoaded}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{AmazonS3Exception, S3Object}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import play.api.libs.json.{JsString, Json}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class StackLoaderSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                              with Matchers with BeforeAndAfterAll {

  val mockedClient    = Mockito.mock(classOf[AmazonS3])
  val s3successObject = new S3Object()
  s3successObject.setBucketName("test-bucket-name")
  s3successObject.setKey("chadash-stacks/test-success.json")
  s3successObject.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

  val s3successObjectWithTags = new S3Object()
  s3successObjectWithTags.setBucketName("test-bucket-name")
  s3successObjectWithTags.setKey("chadash-stacks/test-success-with-tags.json")
  s3successObjectWithTags.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

  val s3successTags = new S3Object()
  s3successTags.setBucketName("test-bucket-name")
  s3successTags.setKey("chadash-stacks/test-success-with-tags.tags.json")
  s3successTags.setObjectContent(new ByteArrayInputStream(
    Json.arr(
      Json.obj("Key" -> JsString("Project"), "Value" -> JsString("Chadash")),
      Json.obj("Key" -> JsString("Owner"), "Value" -> JsString("LifeWay"))
    ).toString().getBytes("UTF-8")
  ))

  val s3restartObject = new S3Object()
  s3restartObject.setBucketName("test-bucket-name")
  s3restartObject.setKey("chadash-stacks/test-aws-restart.json")
  s3restartObject.setObjectContent(new ByteArrayInputStream(Json.obj("test" -> JsString("success")).toString().getBytes("UTF-8")))

  Mockito.doReturn(s3successObject, Nil: _*).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-success.json")
  Mockito.doThrow(new AmazonS3Exception("not found")).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-success.tags.json")
  Mockito.doReturn(s3successObjectWithTags, Nil: _*).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-success-with-tags.json")
  Mockito.doReturn(s3successTags, Nil: _*).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-success-with-tags.tags.json")
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-aws-down.json")
  Mockito.doThrow(new AmazonClientException("connection problems")).doReturn(s3restartObject, Nil: _*).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-aws-restart.json")
  Mockito.doThrow(new AmazonS3Exception("not found")).when(mockedClient).getObject("test-bucket-name", "chadash-stacks/test-aws-restart.tags.json")

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A StackLoader actor" should "return a JSON value for a valid stack and no tags" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackLoader, system, TestActorFactory)

    probe.send(proxy, LoadStack("test-success"))
    probe.expectMsg(StackLoaded(Json.obj("test" -> JsString("success")), None))
  }

  "A StackLoader actor" should "return a JSON value for a valid stack with tags when provided" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackLoader, system, TestActorFactory)

    probe.send(proxy, LoadStack("test-success-with-tags"))
    probe.expectMsg(StackLoaded(Json.obj("test" -> JsString("success")), Some(Seq(Tag("Project", "Chadash"), Tag("Owner", "LifeWay")))))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackLoader, system, TestActorFactory)

    probe.send(proxy, LoadStack("test-aws-down"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackLoader, system, TestActorFactory)

    probe.send(proxy, LoadStack("test-aws-restart"))
    probe.expectMsg(StackLoaded(Json.obj("test" -> JsString("success")), None))
  }

  val props = Props(new StackLoader(null, "test-bucket-name") {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def s3Client(credentials: AWSCredentialsProvider): AmazonS3 = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case StackLoader => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}
