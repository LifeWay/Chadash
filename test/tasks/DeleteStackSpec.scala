package tasks

import actors.WorkflowLog.LogMessage
import actors.workflow.AWSSupervisorStrategy
import actors.workflow.tasks.DeleteStack
import actors.workflow.tasks.DeleteStack.{DeleteStackCommand, StackDeleteRequested}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import utils.TestConfiguration

import scala.concurrent.duration._

class DeleteStackSpec extends TestKit(ActorSystem("TestKit", TestConfiguration.testConfig)) with FlatSpecLike
                              with Matchers
                              with MockitoSugar {

  val mockedClient       = mock[AmazonCloudFormation]
  val failReq            = new DeleteStackRequest().withStackName("fail-stack")
  val clientExceptionReq = new DeleteStackRequest().withStackName("client-exception-stack")

  Mockito.when(mockedClient.deleteStack(failReq)).thenThrow(new AmazonServiceException("failed"))
  Mockito.doThrow(new AmazonClientException("connection problems")).doNothing().when(mockedClient).deleteStack(clientExceptionReq)

  val props = Props(new DeleteStack(null) {
    override def pauseTime(): FiniteDuration = 5.milliseconds

    override def cloudFormationClient(credentials: AWSCredentials): AmazonCloudFormation = mockedClient
  })

  "A DeleteStack actor" should "request to delete the stack and return a response" in {
    val proxy = TestProbe()
    val parent = system.actorOf(Props(new Actor with AWSSupervisorStrategy {
      val child = context.actorOf(props)

      def receive = {
        case x if sender() == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    proxy.send(parent, DeleteStackCommand("test-stack-name"))
    proxy.expectMsg(StackDeleteRequested)
  }

  it should "throw an exception if AWS is down" in {
    val proxy = TestProbe()
    val bigBoss = system.actorOf(Props(new Actor {
      val childBoss = context.actorOf(Props(new Actor with AWSSupervisorStrategy {
        val child = context.actorOf(props)

        def receive = {
          case x => child forward x
        }
      }))

      def receive = {
        case x: LogMessage => proxy.ref forward x
        case x => childBoss forward x
      }
    }))

    proxy.send(bigBoss, DeleteStackCommand("fail-stack"))
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
    proxy.send(parent, DeleteStackCommand("client-exception-stack"))
    proxy.expectMsg(StackDeleteRequested)
  }
}
