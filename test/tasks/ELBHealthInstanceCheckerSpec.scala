package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSSupervisorStrategy
import actors.workflow.tasks.ELBHealthyInstanceChecker
import actors.workflow.tasks.ELBHealthyInstanceChecker.{ELBInstanceListAllHealthy, ELBInstanceListNotHealthy, ELBIsInstanceListHealthy}
import akka.actor._
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResult, Instance, InstanceState}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.{ActorFactory, PropFactory, TestConfiguration}

import scala.concurrent.duration._

class ELBHealthInstanceCheckerSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig))
                                           with FlatSpecLike with Matchers with MockitoSugar {

  val mockedClient             = mock[AmazonElasticLoadBalancing]
  val instance                 = new Instance("instance-1")
  val instancesNotAllHealthy   = Seq(instance, new Instance("instance-2"), new Instance("instance-3"))
  val instanceStates           = new InstanceState().withState("InService")
  val notHealthyInstanceStates = Seq(instanceStates, new InstanceState().withState("OutOfService"), new InstanceState().withState("OutOfService"))
  val failReq                  = new DescribeInstanceHealthRequest().withLoadBalancerName("fail-elb").withInstances(instance)
  val clientExceptionReq       = new DescribeInstanceHealthRequest().withLoadBalancerName("client-exception").withInstances(instance)
  val successReqAllHealthy     = new DescribeInstanceHealthRequest().withLoadBalancerName("test").withInstances(instance)
  val successReqNotAllHealthy  = new DescribeInstanceHealthRequest().withLoadBalancerName("test-not-healthy").withInstances(instancesNotAllHealthy: _*)
  val successResultAllHealthy  = new DescribeInstanceHealthResult().withInstanceStates(instanceStates)
  val successResultNotHealthy  = new DescribeInstanceHealthResult().withInstanceStates(notHealthyInstanceStates: _*)


  Mockito.when(mockedClient.describeInstanceHealth(successReqAllHealthy)).thenReturn(successResultAllHealthy)
  Mockito.when(mockedClient.describeInstanceHealth(successReqNotAllHealthy)).thenReturn(successResultNotHealthy)
  Mockito.when(mockedClient.describeInstanceHealth(failReq)).thenThrow(new AmazonServiceException("failed"))
  Mockito.when(mockedClient.describeInstanceHealth(clientExceptionReq)).thenThrow(new AmazonClientException("connection problems")).thenReturn(successResultAllHealthy)

  val props = Props(new ELBHealthyInstanceChecker(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def elasticLoadBalancingClient(credentials: AWSCredentials) = mockedClient
  })

  object TestActorFactory extends ActorFactory {
    def apply[T <: PropFactory](ref: T, context: ActorRefFactory, name: String, args: Any*): ActorRef = {
      ref match {
        case ELBHealthyInstanceChecker => context.actorOf(props)
        case _ => ActorFactory(ref, context, name, args)
      }
    }
  }

  "A ELBHealthInstanceChecker actor" should "return an all healthy message if all instance are healthy" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(props)
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ELBIsInstanceListHealthy("test", Seq("instance-1")))
    proxy.expectMsg(ELBInstanceListAllHealthy("test"))
  }

  it should "return an a not health message with the count of unhealthy instances if not all instances are healthy" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(props)
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ELBIsInstanceListHealthy("test-not-healthy", Seq("instance-1", "instance-2", "instance-3")))
    proxy.expectMsg(ELBInstanceListNotHealthy(2, "test-not-healthy"))
  }

  it should "throw an exception if AWS is down" in {
    val proxy = TestProbe()
    val grandparent = system.actorOf(Props(new Actor {
      val parent = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = TestActorFactory(ELBHealthyInstanceChecker, context, "", null)

        def receive = {
          case x => child forward x
        }
      }))

      def receive = {
        case x: LogMessage => proxy.ref forward x
        case x => parent forward x
      }
    }))

    proxy.send(grandparent, ELBIsInstanceListHealthy("fail-elb", Seq("instance-1")))
    val msg = proxy.expectMsgClass(classOf[LogMessage])
    msg.message should include("AmazonServiceException")
  }

  it should "support restarts if we had a client communication exception reaching AWS and the supervisor implements AWSSupervisorStrategy" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(props)
      context.watch(child)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))
    proxy.send(parent, ELBIsInstanceListHealthy("client-exception", Seq("instance-1")))
    proxy.expectMsg(ELBInstanceListAllHealthy("client-exception"))
  }
}
