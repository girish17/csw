package csw.services.ccs

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.LazyLogging
import csw.services.ccs.CommandStatus2.{CommandStatus2, Completed, Error}
import csw.services.ccs.CurrentStateReceiver.{AddCurrentStateHandler, AddPublisher, RemovePublisher}
import csw.util.config.Configurations.ConfigKey
import csw.util.config.IntKey
import csw.util.config.UnitsOfMeasure.encoder
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, ShouldMatchers}
import csw.util.config.StateVariable.{CurrentState, DemandState}
import akka.pattern.{ask, pipe}
import csw.services.ccs.StateMatchers.{DemandMatcher, MultiStateMatcherActor, PresenceMatcher, SingleStateMatcherActor}

import scala.language.reflectiveCalls
import scala.concurrent.duration._

/**
  * TMT Source Code: 9/1/16.
  */
class StateMatcherTests extends TestKit(ActorSystem("TromboneAssemblyCommandHandlerTests")) with ImplicitSender
  with FunSpecLike with ShouldMatchers with BeforeAndAfterAll with LazyLogging {
  override def afterAll = TestKit.shutdownActorSystem(system)

  // Needed for futures
  import system.dispatcher

  def stateReceiver = system.actorOf(CurrentStateReceiver.props)

  val movePrefix = s"WFOS.filter.move"
  val moveCK:ConfigKey = movePrefix
  val posKey = IntKey("position")
  def moveCS(pos: Int) = CurrentState(moveCK).add(posKey -> pos withUnits encoder)

  val datumPrefix = s"WFOS.filter.datum"
  val datumCK:ConfigKey = datumPrefix
  // IF a dataum cs is found in the CurrentData then it's successful
  def datumCS() = CurrentState(datumCK)

  val listOfPosStates:List[CurrentState] = ((0 to 500 by 10).map(moveCS).toList :+ CurrentState(datumCK)) ++ (510 to 1000 by 10).map(moveCS).toList

  def writeStates(states: List[CurrentState], receiver: ActorRef, delay: Int = 5) = {
    val fakePublisher = TestProbe()
    receiver ! AddPublisher(fakePublisher.ref)
    states.foreach { s =>
      receiver ! s
      Thread.sleep(delay)
    }
    receiver ! RemovePublisher(fakePublisher.ref)
    listOfPosStates
  }

  def movingPosMatcher(demand: DemandState, current: CurrentState): Boolean =
    demand.prefix == current.prefix && demand(posKey).head == current(posKey).head

  def singleMatcher(currentStateReceiver: ActorRef, timeout: Timeout = Timeout(10.seconds)):ActorRef = {
    val props = SingleStateMatcherActor.props(currentStateReceiver, timeout)
    val stateMatcherActor = system.actorOf(props)
    stateMatcherActor
  }

  def multiMatcher(currentStateReceiver: ActorRef, timeout: Timeout = Timeout(10.seconds)):ActorRef = {
    val props = MultiStateMatcherActor.props(currentStateReceiver, timeout)
    val stateMatcherActor = system.actorOf(props)
    stateMatcherActor
  }

  describe("testing single item matcher") {
    // Needed for future
    implicit val timeout = Timeout(5.seconds)

    it("should allow setup with fake current states") {
      val sr = stateReceiver

      val fakeMatcher = TestProbe()
      sr ! AddCurrentStateHandler(fakeMatcher.ref)

      writeStates(listOfPosStates, sr)

      val msgs = fakeMatcher.receiveN(listOfPosStates.size)
      msgs should equal(listOfPosStates)

      // Cleanup
      system.stop(sr)
    }

    /**
      * TestDescription: This test creates a single state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns a Completed when it matches
      */
    it("single item match works") {
      import csw.services.ccs.StateMatchers.SingleStateMatcherActor.StartMatch

      val sr = stateReceiver

      val fakeSender = TestProbe()

      val testPosition = 600
      val ds = DemandState(moveCK).add(posKey -> testPosition)
      val matcher = singleMatcher(sr)

      (matcher ? StartMatch(DemandMatcher(ds))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Completed)

      // Cleanup
      system.stop(sr)
    }

    /**
      * TestDescription: This test creates a single state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns an error when it times out.
      * This is the only way that a matcher looking at current state can fail at this point
      */
    it("single item times out with a failure when pos not found") {
      import csw.services.ccs.StateMatchers.SingleStateMatcherActor.StartMatch
      val sr = stateReceiver

      val fakeSender = TestProbe()

      val testPosition = 901
      val ds = DemandState(moveCK).add(posKey -> testPosition)
      val matcher = singleMatcher(sr, 2.seconds)

      (matcher ? StartMatch(DemandMatcher(ds))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Error("Current state matching timed out"))

      // Cleanup
      system.stop(sr)
    }

    /**
      * TestDescription: This test creates a multi state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns a Completed when it matches a single current state
      */
    it("single item match works with multi matcher") {
      import csw.services.ccs.StateMatchers.MultiStateMatcherActor.StartMatch

      val sr = stateReceiver

      val fakeSender = TestProbe()

      val testPosition = 200
      val ds = DemandState(moveCK).add(posKey -> testPosition)
      val matcher = multiMatcher(sr)

      (matcher ? StartMatch(List(DemandMatcher(ds)))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Completed)

      // Cleanup
      system.stop(sr)
    }

    /**
      * TestDescription: This test creates a multi state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns a Completed when it matches multiple current states
      * in the same stream
      */
    it("multi item match works with multi matcher") {
      import csw.services.ccs.StateMatchers.MultiStateMatcherActor.StartMatch

      val sr = stateReceiver

      val fakeSender = TestProbe()

      // Note that these are using the same prefix.
      val testPosition = 20
      val testPosition2 = 900
      val ds = DemandState(moveCK).add(posKey -> testPosition)
      val ds2 = DemandState(moveCK).add(posKey -> testPosition2)

      val matcher = multiMatcher(sr)

      (matcher ? StartMatch(List(DemandMatcher(ds2), DemandMatcher(ds)))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Completed)

      // Cleanup
      system.stop(sr)
    }


    /**
      * TestDescription: This test creates a multi state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns a Completed when it matches multiple current states
      * in the same stream
      */
    it("multi item match works with multi matcher diffrent prefixes") {
      import csw.services.ccs.StateMatchers.MultiStateMatcherActor.StartMatch

      val sr = stateReceiver

      val fakeSender = TestProbe()

      val testPosition = 750
      val ds = DemandState(moveCK).madd(posKey -> testPosition)

      val matcher = multiMatcher(sr)

      (matcher ? StartMatch(List(DemandMatcher(ds), PresenceMatcher(datumCK.prefix)))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Completed)

      // Cleanup
      system.stop(sr)
    }


    /**
      * TestDescription: This test creates a multi state matcher, feeds it a set of
      * fake CurrentStates and tests that it returns a Completed when a single matcher with
      * units enabled is used
      */
    it("multi item match works with multi matcher with units") {
      import csw.services.ccs.StateMatchers.MultiStateMatcherActor.StartMatch

      val sr = stateReceiver

      val fakeSender = TestProbe()

      // Note that these are using the same prefix.
      val testPosition = 750

      val ds = DemandState(moveCK).madd(posKey -> testPosition withUnits encoder)

      val matcher = multiMatcher(sr)

      (matcher ? StartMatch(List(DemandMatcher(ds, true)))).mapTo[CommandStatus2].pipeTo(fakeSender.ref)

      writeStates(listOfPosStates, sr)

      val msgOut = fakeSender.expectMsgClass(classOf[CommandStatus2])

      msgOut should be(Completed)

      // Cleanup
      system.stop(sr)
    }
  }

}



