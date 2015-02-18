package tasks

import actors.workflow.tasks.ASGInfo
import actors.workflow.tasks.ASGInfo.{ASGInServiceInstancesAndELBSQuery, ASGInServiceInstancesAndELBSResult}
import actors.workflow.{AWSRestartablePause, AWSSupervisorStrategy}
import akka.actor.{Actor, ActorSystem, Props, Terminated}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{AmazonAutoScalingComponent, TestConfiguration}

import scala.concurrent.duration._

class ASGInfoSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike with Matchers with MockitoSugar {

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

  trait AWSTestablePause extends AWSRestartablePause {
    override def pauseTime(): FiniteDuration =  5.milliseconds
  }

  trait MockedClient extends AmazonAutoScalingComponent {
    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  }

  val asgInfoProps = Props(new ASGInfo(null) with AWSTestablePause with MockedClient)

  "An ASGInfo fetcher" should "return a valid response if AWS is up" in {
    //Fabricate a parent so we can test messages coming back to the parent.
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgInfoProps, "asgInfo")

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, ASGInServiceInstancesAndELBSQuery("test-asg-name"))
    proxy.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }

  it should "throw an exception if AWS is down" in {
    //Fabricate a parent so we can test messages coming back to the parent.
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgInfoProps, "asgInfo")
      context.watch(child)

      def receive = {
        case Terminated(actorRef) => proxy.ref forward "terminated"
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, ASGInServiceInstancesAndELBSQuery("expect-fail"))
    proxy.expectMsg("terminated")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    //Fabricate a parent so we can test messages coming back to the parent.
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgInfoProps, "asgInfo")
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ASGInServiceInstancesAndELBSQuery("client-exception"))
    proxy.expectMsg(ASGInServiceInstancesAndELBSResult(Seq("test-elb-name"), Seq("test-instance-id")))
  }
}