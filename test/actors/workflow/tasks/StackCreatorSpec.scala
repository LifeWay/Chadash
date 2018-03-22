package actors.workflow.tasks

import actors.DeploymentSupervisor
import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackCreator.{StackCreateCommand, StackCreateRequestCompleted}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{Capability, CreateStackRequest, Parameter}
import com.amazonaws.services.cloudformation.model.{Tag => AWSTag}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import play.api.libs.json.Json
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class StackCreatorSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                               with Matchers with BeforeAndAfterAll {

  val mockedClient  = Mockito.mock(classOf[AmazonCloudFormation])
  val appVersionTag = new AWSTag().withKey("ApplicationVersion").withValue("1.0")
  val projectTag = new AWSTag().withKey("Project").withValue("Chadash")
  val params        = Seq(
    new Parameter().withParameterKey("ImageId").withParameterValue("image-id"),
    new Parameter().withParameterKey("ApplicationVersion").withParameterValue("1.0")
  )
  val successReq    = new CreateStackRequest().withTemplateBody(Json.obj("someObject" -> "someBody").toString()).withTags(appVersionTag).withParameters(params: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("success-stack")
  val successReqWithTags = new CreateStackRequest().withTemplateBody(Json.obj("someObject" -> "someBody").toString()).withTags(appVersionTag, projectTag).withParameters(params: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("success-stack")
  val reqFail       = new CreateStackRequest().withTemplateBody(Json.obj("someObject" -> "someBody").toString()).withTags(appVersionTag).withParameters(params: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("fail-stack")
  val reqClientExc  = new CreateStackRequest().withTemplateBody(Json.obj("someObject" -> "someBody").toString()).withTags(appVersionTag).withParameters(params: _*).withCapabilities(Capability.CAPABILITY_IAM).withStackName("client-exception")

  //If we don't check Mock data response, we must have throw an exception if we didn't match the request.
  Mockito.doThrow(new IllegalArgumentException).when(mockedClient).createStack(ArgumentMatchers.any())
  Mockito.doReturn(null, Nil: _*).when(mockedClient).createStack(successReq)
  Mockito.doReturn(null, Nil: _*).when(mockedClient).createStack(successReqWithTags)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).createStack(reqFail)
  Mockito.doThrow(new AmazonClientException("connection problems")).doReturn(null, Nil: _*).when(mockedClient).createStack(reqClientExc)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A stack creator actor" should "return a stack create request completed if successful" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackCreator, system, TestActorFactory)

    probe.send(proxy, StackCreateCommand("success-stack", "image-id", DeploymentSupervisor.buildVersion("1.0"), Json.obj("someObject" -> "someBody"), None))
    probe.expectMsg(StackCreateRequestCompleted)
  }

  it should "return a stack create request completed if successful with tags" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackCreator, system, TestActorFactory)

    probe.send(proxy, StackCreateCommand("success-stack", "image-id", DeploymentSupervisor.buildVersion("1.0"), Json.obj("someObject" -> "someBody"), Some(Seq(Tag("Project", "Chadash")))))
    probe.expectMsg(StackCreateRequestCompleted)
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackCreator, system, TestActorFactory)

    probe.send(proxy, StackCreateCommand("fail-stack", "image-id", DeploymentSupervisor.buildVersion("1.0"), Json.obj("someObject" -> "someBody"), None))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackCreator, system, TestActorFactory)

    probe.send(proxy, StackCreateCommand("client-exception", "image-id", DeploymentSupervisor.buildVersion("1.0"), Json.obj("someObject" -> "someBody"), None))
    probe.expectMsg(StackCreateRequestCompleted)
  }

  val props = Props(new StackCreator(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentialsProvider): AmazonCloudFormation = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case StackCreator => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}
