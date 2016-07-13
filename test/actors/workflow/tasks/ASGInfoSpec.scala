package actors.workflow.tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import utils._

import scala.concurrent.duration._

class ASGInfoSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike with Matchers
                          with MockitoSugar with BeforeAndAfterAll {

  val mockedClient            = mock[AmazonAutoScaling]
  val describeASGRequest      = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("test-asg-name")
  val describeASGReqFail      = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("expect-fail")
  val describeASGReqClientExc = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("client-exception")
  val instance                = new Instance().withLifecycleState(LifecycleState.InService).withInstanceId("test-instance-id")
  val asg                     = new AutoScalingGroup().withLoadBalancerNames("test-elb-name").withInstances(instance)
  val describeASGResult       = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)

  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGRequest)).thenReturn(describeASGResult)
  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReqFail)).thenThrow(new AmazonServiceException("failed"))
  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReqClientExc)).thenThrow(new AmazonClientException("connection problems")).thenReturn(describeASGResult)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An ASGInfo fetcher" should "return a valid response if AWS is up" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGInfo, system, TestActorFactory)

    probe.send(proxy, ASGInServiceInstancesAndELBSQuery("test-asg-name"))
    probe.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }

  it should "throw an exception if AWS is down" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGInfo, system, TestActorFactory)

    probe.send(proxy, ASGInServiceInstancesAndELBSQuery("expect-fail"))
    val msg = probe.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val probe = TestProbe()
    val proxy = TaskProxyBuilder(probe, ASGInfo, system, TestActorFactory)

    probe.send(proxy, ASGInServiceInstancesAndELBSQuery("client-exception"))
    probe.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }

  val asgInfoProps = Props(new ASGInfo(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case ASGInfo => context.actorOf(asgInfoProps, "asgInfo")
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }
}