package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackInfo
import actors.workflow.tasks.StackInfo.{StackASGNameQuery, StackASGNameResponse, StackIdQuery, StackIdResponse}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{DescribeStacksRequest, DescribeStacksResult, Output, Stack}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class StackInfoSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                            with Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonCloudFormation]
  val asgSuccessReq      = new DescribeStacksRequest().withStackName("asg-name-query")
  val idSuccessReq       = new DescribeStacksRequest().withStackName("stack-id-query")
  val failReq            = new DescribeStacksRequest().withStackName("expect-fail")
  val clientExceptionReq = new DescribeStacksRequest().withStackName("client-exception")
  val asgOutput          = new Output().withOutputKey("ChadashASG").withOutputValue("some-asg-name")
  val asgStack           = new Stack().withOutputs(asgOutput)
  val asgSuccessResult   = new DescribeStacksResult().withStacks(asgStack)
  val idStack            = new Stack().withStackId("some-stack-id")
  val idSuccessResult    = new DescribeStacksResult().withStacks(idStack)


  Mockito.doReturn(asgSuccessResult).when(mockedClient).describeStacks(asgSuccessReq)
  Mockito.doReturn(idSuccessResult).when(mockedClient).describeStacks(idSuccessReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).describeStacks(failReq)
  Mockito.doThrow(new AmazonClientException("connection problems")).doReturn(idSuccessResult).when(mockedClient).describeStacks(clientExceptionReq)

  "A StackInfo actor" should "return the name of the ASG for a given stack" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackInfo, system, TestActorFactory)

    probe.send(proxy, StackASGNameQuery("asg-name-query"))
    probe.expectMsg(StackASGNameResponse("some-asg-name"))
  }

  it should "return the ID of a stack by stack name" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackInfo, system, TestActorFactory)

    probe.send(proxy, StackIdQuery("stack-id-query"))
    probe.expectMsg(StackIdResponse("some-stack-id"))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackInfo, system, TestActorFactory)

    probe.send(proxy, StackIdQuery("expect-fail"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackInfo, system, TestActorFactory)

    probe.send(proxy, StackIdQuery("client-exception"))
    probe.expectMsg(StackIdResponse("some-stack-id"))
  }

  val props = Props(new StackInfo(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case StackInfo => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}
