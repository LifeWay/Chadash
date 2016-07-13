package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.StackList.{FilteredStacks, ListNonDeletedStacksStartingWithName}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.StackStatus._
import com.amazonaws.services.cloudformation.model.{ListStacksRequest, ListStacksResult, StackSummary}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class StackListSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                            with Matchers with MockitoSugar with BeforeAndAfterAll {

  val mockedClient          = mock[AmazonCloudFormation]
  val failMockedClient      = mock[AmazonCloudFormation]
  val req                   = new ListStacksRequest().withStackStatusFilters(actors.workflow.tasks.StackList.stackStatusFilters: _*)
  val successStackSummaries = Seq(new StackSummary().withStackName("some-stack-id-1"), new StackSummary().withStackName("some-stack-id-2"))
  val successResp           = new ListStacksResult().withStackSummaries(successStackSummaries: _*)

  Mockito.doThrow(new AmazonServiceException("failed")).when(failMockedClient).listStacks(req)
  Mockito.doReturn(successResp)
  .doThrow(new AmazonClientException("connection problems"))
  .doReturn(successResp)
  .when(mockedClient).listStacks(req)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A StackList actor" should "retrieve filtered stacks" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackList, system, new TestActorFactory(props))

    probe.send(proxy, ListNonDeletedStacksStartingWithName("some-stack"))
    probe.expectMsg(FilteredStacks(Seq("some-stack-id-1", "some-stack-id-2")))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackList, system, new TestActorFactory(failProps))

    probe.send(proxy, ListNonDeletedStacksStartingWithName("expect-fail"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, StackList, system, new TestActorFactory(props))

    probe.send(proxy, ListNonDeletedStacksStartingWithName("some-stack"))
    probe.expectMsg(FilteredStacks(Seq("some-stack-id-1", "some-stack-id-2")))
  }

  val props = Props(new StackList(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentialsProvider): AmazonCloudFormation = mockedClient
  })

  val failProps = Props(new StackList(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentialsProvider): AmazonCloudFormation = failMockedClient
  })

  class TestActorFactory(prop: Props) extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case StackList => context.actorOf(prop)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}
