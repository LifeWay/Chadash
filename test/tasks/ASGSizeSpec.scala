package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSSupervisorStrategy
import actors.workflow.tasks.ASGSize
import actors.workflow.tasks.ASGSize.{ASGDesiredSizeQuery, ASGDesiredSizeResult, ASGDesiredSizeSet, ASGSetDesiredSizeCommand}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult, SetDesiredCapacityRequest}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.TestConfiguration

import scala.concurrent.duration._

class ASGSizeSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike with
                          Matchers with MockitoSugar {

  val mockedClient       = mock[AmazonAutoScaling]
  val describeASGReq     = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("test-asg-name")
  val failReq            = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("expect-fail")
  val clientExceptionReq = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames("client-exception")
  val asg                = new AutoScalingGroup().withDesiredCapacity(10)
  val describeASGResult  = new DescribeAutoScalingGroupsResult().withAutoScalingGroups(asg)


  Mockito.when(mockedClient.describeAutoScalingGroups(describeASGReq)).thenReturn(describeASGResult)
  Mockito.when(mockedClient.describeAutoScalingGroups(failReq)).thenThrow(new AmazonServiceException("failed"))
  Mockito.when(mockedClient.describeAutoScalingGroups(clientExceptionReq)).thenThrow(new AmazonClientException("connection problems")).thenReturn(describeASGResult)

  val asgSizeProps = Props(new ASGSize(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def autoScalingClient(credentials: AWSCredentials): AmazonAutoScaling = mockedClient
  })

  "An ASGSize actor" should "return an ASG size response if an ASG is queried" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgSizeProps)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, ASGDesiredSizeQuery("test-asg-name"))
    proxy.expectMsg(ASGDesiredSizeResult(10))
  }

  it should "set the desired size of an ASG and return a success message" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgSizeProps)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, ASGSetDesiredSizeCommand("test-asg-name", 5))
    proxy.expectMsg(ASGDesiredSizeSet(5))
  }

  it should "throw an exception if AWS is down" in {
    val proxy = TestProbe()
    val bigBoss = system.actorOf(Props(new Actor {
      val childBoss = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = context.actorOf(asgSizeProps)

        def receive = {
          case x => child forward x
        }
      }))

      def receive = {
        case x: LogMessage => proxy.ref forward x
        case x => childBoss forward x
      }
    }))

    proxy.send(bigBoss, ASGDesiredSizeQuery("expect-fail"))
    val msg = proxy.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(asgSizeProps, "asgInfo")
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ASGDesiredSizeQuery("client-exception"))
    proxy.expectMsg(ASGDesiredSizeResult(10))
  }

}
