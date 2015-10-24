package csw.services.event

import akka.testkit.{ ImplicitSender, TestKit }
import akka.actor._
import csw.util.cfg.Events.ObserveEvent
import csw.util.cfg.StandardKeys._
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike }
import com.typesafe.scalalogging.slf4j.LazyLogging
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class EventPubSubTests extends TestKit(ActorSystem("Test"))
    with ImplicitSender with FunSuiteLike with LazyLogging with BeforeAndAfterAll {

  val settings = EventServiceSettings(system)
  if (settings.useEmbeddedHornetq) {
    // Start an embedded HornetQ server, so no need to have it running externally!
    EventService.startEmbeddedHornetQ()
  }

  val numSecs = 20
  // number of seconds to run
  val subscriber = system.actorOf(Props(classOf[Subscriber], "Subscriber-1"))
  val publisher = system.actorOf(Props(classOf[Publisher], self, numSecs))

  test("Test subscriber") {
    within((numSecs + 2).seconds) {
      expectMsg("done")
      subscriber ! "done"
      val count = expectMsgType[Int]
      val msgPerSec = count * 1.0 / numSecs
      logger.info(s"Recieved $count events in $numSecs seconds ($msgPerSec per second)")
      assert(count > 0)
    }
    Thread.sleep(1000) // wait for any messages still in the queue for the subscriber
  }

  override def afterAll(): Unit = {
    system.terminate()
  }
}

// A test class that publishes events
private case class Publisher(caller: ActorRef, numSecs: Int) extends Actor with ActorLogging {
  val channel = "tcs.mobie.red.dat.exposureInfo"
  val expTime = 1
  var nextId = 0
  var done = false

  import context.dispatcher

  val settings = EventServiceSettings(context.system)
  val eventService = EventService(settings)

  context.system.scheduler.scheduleOnce(numSecs.seconds) {
    caller ! "done"
    done = true
  }

  while (!done) {
    val event = nextEvent()
    eventService.publish(event)
    Thread.`yield`() // don't want to hog the cpu here
    if (nextId % 10000 == 0)
      log.info(s"Published $nextId events so far: $event")
  }

  // Returns the next event to publish
  def nextEvent(): Event = {
    //    val time = System.currentTimeMillis()
    nextId = nextId + 1
    ObserveEvent(channel).set(exposureTime, 1.0).set(repeats, 2)
  }

  override def receive: Receive = {
    case x ⇒ log.error(s"Unexpected message $x")
  }
}

// A test class that subscribes to events
private case class Subscriber(name: String) extends Actor with ActorLogging with EventSubscriber {
  implicit val execContext: ExecutionContext = context.dispatcher
  implicit val actorSytem = context.system
  var count = 0

  //  val prefix = "tcs.mobie.red.dat.exposureInfo"
  val prefix = "tcs.mobie.red.dat.*" // using wildcard
  subscribe(prefix)

  override def receive: Receive = {
    case event: ObserveEvent ⇒
      count = count + 1
      if (count % 10000 == 0)
        log.info(s"Received $count events so far: $event")

    case "done" ⇒
      sender() ! count
      unsubscribe(prefix)

    case x ⇒ log.error(s"Unexpected message $x")
  }
}
