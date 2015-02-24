package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackCreateCompleteMonitor.{StackCreateCompleted, Tick}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{DescribeStacksRequest, DescribeStacksResult, Stack, StackStatus}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class StackCreateCompleteMonitorSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig))
                                             with FlatSpecLike with Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonCloudFormation]
  val createCompleteReq  = new DescribeStacksRequest().withStackName("create-completed")
  val createCompleteReq2 = new DescribeStacksRequest().withStackName("create-completed-2")
  val stackFailReq       = new DescribeStacksRequest().withStackName("stack-fail")
  val stackBadTypeReq    = new DescribeStacksRequest().withStackName("bad-stack-status-type")
  val awsFailReq         = new DescribeStacksRequest().withStackName("aws-fail-stack")
  val clientExceptionReq = new DescribeStacksRequest().withStackName("client-exception-stack")
  val stackPending       = new Stack().withStackStatus(StackStatus.CREATE_IN_PROGRESS)
  val stackComplete      = new Stack().withStackStatus(StackStatus.CREATE_COMPLETE)
  val stackFailed        = new Stack().withStackStatus(StackStatus.CREATE_FAILED)
  val stackBadStatus     = new Stack().withStackStatus(StackStatus.ROLLBACK_COMPLETE)
  val stackPendingResp   = new DescribeStacksResult().withStacks(stackPending)
  val stackCompleteResp  = new DescribeStacksResult().withStacks(stackComplete)
  val stackFailedResp    = new DescribeStacksResult().withStacks(stackFailed)
  val stackBadStatusResp = new DescribeStacksResult().withStacks(stackBadStatus)


  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteReq)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(createCompleteReq2)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackFailedResp).when(mockedClient).describeStacks(stackFailReq)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackBadStatusResp).when(mockedClient).describeStacks(stackBadTypeReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).describeStacks(awsFailReq)
  Mockito.doThrow(new AmazonClientException("connection problems")).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(clientExceptionReq)


  "A StackCreateComplete Monitor actor" should "send a create complete message when the stack has reached CREATE_COMPLETE status" in {
    val props = Props(new StackCreateCompleteMonitor(null, "create-completed") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msgs = probe.receiveN(4)
    msgs(3) should equal(StackCreateCompleted("create-completed"))
  }

  it should "throw an exception if the stack goes to a CREATE_FAILED status" in {
    val props = Props(new StackCreateCompleteMonitor(null, "stack-fail") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msgs = probe.receiveN(3)
    msgs(2) should equal(LogMessage("java.lang.Exception: Failed to create the new stack!"))
  }

  it should "send LogMessages to the parent as it ticks during CREATE_IN_PROGRESS" in {
    val props = Props(new StackCreateCompleteMonitor(null, "create-completed-2") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msgs = probe.receiveN(4)
    msgs(0) shouldBe a[LogMessage]
    msgs(1) shouldBe a[LogMessage]
    msgs(2) should equal(LogMessage("create-completed-2 has not yet reached CREATE_COMPLETE status"))
    msgs(3) should equal(StackCreateCompleted("create-completed-2"))
  }

  it should "throw an exception for any other stack status type" in {
    val props = Props(new StackCreateCompleteMonitor(null, "bad-stack-status-type") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msgs = probe.receiveN(3)
    msgs(2) should equal(LogMessage("java.lang.Exception: unhandled stack status type"))
  }

  it should "throw an exception if AWS is down" in {
    val props = Props(new StackCreateCompleteMonitor(null, "aws-fail-stack") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val props = Props(new StackCreateCompleteMonitor(null, "client-exception-stack") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackCreateCompleteMonitor, system, factory)

    val msgs = probe.receiveN(2)
    msgs(0) shouldBe a[LogMessage]
    msgs(1) should equal(StackCreateCompleted("client-exception-stack"))
  }

  trait Override {
    this: StackCreateCompleteMonitor =>
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

    override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
  }

  class TestActorFactory(props: Props) extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case StackCreateCompleteMonitor => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

}
