package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackDeleteCompleteMonitor
import actors.workflow.tasks.StackDeleteCompleteMonitor.{StackDeleteCompleted, Tick}
import akka.actor._
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

class StackDeleteCompleteMonitorSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig))
                                             with FlatSpecLike with Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonCloudFormation]
  val deleteCompleteReq  = new DescribeStacksRequest().withStackName("delete-completed")
  val deleteCompleteReq2 = new DescribeStacksRequest().withStackName("delete-completed-2")
  val stackFailReq       = new DescribeStacksRequest().withStackName("stack-fail")
  val stackBadTypeReq    = new DescribeStacksRequest().withStackName("bad-stack-status-type")
  val awsFailReq         = new DescribeStacksRequest().withStackName("aws-fail-stack")
  val clientExceptionReq = new DescribeStacksRequest().withStackName("client-exception-stack")
  val stackPending       = new Stack().withStackStatus(StackStatus.DELETE_IN_PROGRESS)
  val stackComplete      = new Stack().withStackStatus(StackStatus.DELETE_COMPLETE)
  val stackFailed        = new Stack().withStackStatus(StackStatus.DELETE_FAILED)
  val stackBadStatus     = new Stack().withStackStatus(StackStatus.ROLLBACK_COMPLETE)
  val stackPendingResp   = new DescribeStacksResult().withStacks(stackPending)
  val stackCompleteResp  = new DescribeStacksResult().withStacks(stackComplete)
  val stackFailedResp    = new DescribeStacksResult().withStacks(stackFailed)
  val stackBadStatusResp = new DescribeStacksResult().withStacks(stackBadStatus)

  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(deleteCompleteReq2)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackFailedResp).when(mockedClient).describeStacks(stackFailReq)
  Mockito.doReturn(stackPendingResp).doReturn(stackPendingResp).doReturn(stackBadStatusResp).when(mockedClient).describeStacks(stackBadTypeReq)
  Mockito.doThrow(new AmazonServiceException("failed")).when(mockedClient).describeStacks(awsFailReq)
  Mockito.doThrow(new AmazonClientException("connection problems")).doReturn(stackPendingResp).doReturn(stackCompleteResp).when(mockedClient).describeStacks(clientExceptionReq)

  "A StackDeleteComplete Monitor actor" should "send a delete complete message when the stack has reached DELETE_COMPLETE status" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "delete-completed", "delete-completed") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msgs = probe.receiveN(4)
    msgs(3) should equal(StackDeleteCompleted("delete-completed"))
  }

  it should "throw an exception if the stack goes to a DELETE_FAILED status" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "stack-fail", "stack-fail") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msgs = probe.receiveN(3)
    msgs(2) should equal(LogMessage("java.lang.Exception: Failed to delete the old stack!"))
  }

  it should "send LogMessages to the parent as it ticks during DELETE_IN_PROGRESS" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "delete-completed-2", "delete-completed-2") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msgs = probe.receiveN(4)
    msgs(0) shouldBe a[LogMessage]
    msgs(1) shouldBe a[LogMessage]
    msgs(2) should equal(LogMessage("delete-completed-2 has not yet reached DELETE_COMPLETE status"))
    msgs(3) should equal(StackDeleteCompleted("delete-completed-2"))
  }

  it should "throw an exception for any other stack status type" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "bad-stack-status-type", "bad-stack-status-type") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msgs = probe.receiveN(3)
    msgs(2) should equal(LogMessage("java.lang.Exception: unhandled stack status type"))
  }

  it should "throw an exception if AWS is down" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "aws-fail-stack", "aws-fail-stack") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val props = Props(new StackDeleteCompleteMonitor(null, "client-exception-stack", "client-exception-stack") with Override)
    val factory = new TestActorFactory(props)

    val probe = TestProbe()
    TaskProxyBuilder(probe, StackDeleteCompleteMonitor, system, factory)

    val msgs = probe.receiveN(2)
    msgs(0) shouldBe a[LogMessage]
    msgs(1) should equal(StackDeleteCompleted("client-exception-stack"))
  }

  trait Override {
    this: StackDeleteCompleteMonitor =>
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient

    override def scheduleTick() = context.system.scheduler.scheduleOnce(5.milliseconds, self, Tick)
  }

  class TestActorFactory(props: Props) extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      //Match on actor classes you care about, pass the rest onto the "prod" factory.
      ref match {
        case StackDeleteCompleteMonitor => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

}
