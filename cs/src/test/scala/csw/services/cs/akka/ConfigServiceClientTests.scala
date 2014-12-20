package csw.services.cs.akka

import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.LazyLogging
import csw.services.apps.configServiceAnnex.ConfigServiceAnnexServer
import csw.services.cs.core.git.GitConfigManager
import org.scalatest.{ FunSuiteLike, BeforeAndAfterAll }
import java.io.{ File, FileNotFoundException, IOException }
import csw.services.cs.core.ConfigData
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }

/**
 * Tests the Config Service actor
 */
class ConfigServiceClientTests extends TestKit(ActorSystem("mySystem"))
    with ImplicitSender with FunSuiteLike with BeforeAndAfterAll with LazyLogging {

  import system.dispatcher

  implicit val timeout: Timeout = 30.seconds

  val path1 = new File("some/test1/TestConfig1")
  val path2 = new File("some/test2/TestConfig2")

  val contents1 = "Contents of some file...\n"
  val contents2 = "New contents of some file...\n"
  val contents3 = "Even newer contents of some file...\n"

  val comment1 = "create comment"
  val comment2 = "update 1 comment"
  val comment3 = "update 2 comment"

  test("Test the ConfigServiceClent, storing and retrieving some files") {
    val settings = ConfigServiceSettings(ConfigFactory.load("test.conf"))
    val settings2 = ConfigServiceSettings(ConfigFactory.load("test2.conf"))

    // Start the config service annex http server and wait for it to be ready for connections
    // (In normal operations, this server would already be running)
    val annexServer = Await.result(ConfigServiceAnnexServer.startup(), 5.seconds)

    val f = for {
      _ ← runTests(settings, oversize = false)
      _ ← runTests2(settings2, oversize = false)

      _ ← runTests(settings, oversize = true)
      x ← runTests2(settings2, oversize = true)
    } yield x
    Await.ready(f, 30.seconds)
    logger.info("Shutting down annex server")
    annexServer.shutdown()
  }

  // Runs the tests for the config service, using the given oversize option.
  def runTests(settings: ConfigServiceSettings, oversize: Boolean): Future[Unit] = {
    logger.info(s"--- Testing config service: oversize = $oversize ---")

    // create a test repository and use it to create the actor
    val manager = TestRepo.getConfigManager(settings)

    // Create the actor
    val csActor = system.actorOf(ConfigServiceActor.props(manager), name = "configService")
    val csClient = ConfigServiceClient(csActor)

    // Sequential, non-blocking for-comprehension
    for {
      // Try to update a file that does not exist (should fail)
      updateIdNull ← csClient.update(path1, ConfigData(contents2), comment2) recover {
        case e: FileNotFoundException ⇒ null
      }

      // Add, then update the file twice
      createId1 ← csClient.create(path1, ConfigData(contents1), oversize, comment1)
      createId2 ← csClient.create(path2, ConfigData(contents1), oversize, comment1)
      updateId1 ← csClient.update(path1, ConfigData(contents2), comment2)
      updateId2 ← csClient.update(path1, ConfigData(contents3), comment3)

      // Check that we can access each version
      result1 ← csClient.get(path1).flatMap(_.get.toFutureString)
      result2 ← csClient.get(path1, Some(createId1)).flatMap(_.get.toFutureString)
      result3 ← csClient.get(path1, Some(updateId1)).flatMap(_.get.toFutureString)
      result4 ← csClient.get(path1, Some(updateId2)).flatMap(_.get.toFutureString)
      result5 ← csClient.get(path2).flatMap(_.get.toFutureString)
      result6 ← csClient.get(path2, Some(createId2)).flatMap(_.get.toFutureString)

      // test history()
      historyList1 ← csClient.history(path1)
      historyList2 ← csClient.history(path2)

      // test list()
      list ← csClient.list()

      // Should throw exception if we try to create a file that already exists
      createIdNull ← csClient.create(path1, ConfigData(contents2), oversize, comment2) recover {
        case e: IOException ⇒ null
      }

      // Test default file features
      default1 ← manager.getDefault(path1).flatMap(_.get.toFutureString)
      _ ← manager.setDefault(path1, Some(updateId1))
      default2 ← manager.getDefault(path1).flatMap(_.get.toFutureString)
      _ ← manager.resetDefault(path1)
      default3 ← manager.getDefault(path1).flatMap(_.get.toFutureString)

    } yield {
      // At this point all of the above Futures have completed,so we can do some tests
      assert(updateIdNull == null)
      assert(result1 == contents3)
      assert(result2 == contents1)
      assert(result3 == contents2)
      assert(result4 == contents3)
      assert(result5 == contents1)
      assert(result6 == contents1)
      assert(createIdNull == null)

      assert(historyList1.size == 3)
      assert(historyList2.size == 1)
      assert(historyList1(0).comment == comment3)
      assert(historyList2(0).comment == comment1)
      assert(historyList1(1).comment == comment2)
      assert(historyList1(2).comment == comment1)

      assert(list.size == 2 + 1) // +1 for README file added when creating the bare rep
      for (info ← list) {
        info.path match {
          case this.path1 ⇒ assert(info.comment == this.comment3)
          case this.path2 ⇒ assert(info.comment == this.comment1)
          case x          ⇒ if (x.getName != "README") sys.error("Test failed for " + info)
        }
      }

      assert(default1 == contents3)
      assert(default2 == contents2)
      assert(default3 == contents3)

      system.stop(csActor)
    }
  }

  // Verify that a second config service can still see all the files that were checked in by the first
  def runTests2(settings: ConfigServiceSettings, oversize: Boolean): Future[Unit] = {
    logger.info(s"--- Verify config service: oversize = $oversize ---")

    // create a test repository and use it to create the actor
    val manager = GitConfigManager(settings.gitLocalRepository, settings.gitMainRepository, settings.name)

    // Create the actor
    val csActor = system.actorOf(ConfigServiceActor.props(manager), name = "configService")
    val csClient = ConfigServiceClient(csActor)

    // Sequential, non-blocking for-comprehension
    for {
      // Check that we can access each version
      result1 ← csClient.get(path1).flatMap(_.get.toFutureString)
      result5 ← csClient.get(path2).flatMap(_.get.toFutureString)

      // test history()
      historyList1 ← csClient.history(path1)
      historyList2 ← csClient.history(path2)

      // test list()
      list ← csClient.list()

      // Should throw exception if we try to create a file that already exists
      createIdNull ← csClient.create(path1, ConfigData(contents2), oversize, comment2) recover {
        case e: IOException ⇒ null
      }
    } yield {
      // At this point all of the above Futures have completed,so we can do some tests
      assert(result1 == contents3)
      assert(result5 == contents1)
      assert(createIdNull == null)

      assert(historyList1.size == 3)
      assert(historyList2.size == 1)
      assert(historyList1(0).comment == comment1)
      assert(historyList2(0).comment == comment1)
      assert(historyList1(1).comment == comment2)
      assert(historyList1(2).comment == comment3)

      assert(list.size == 2 + 1) // +1 for README file added when creating the bare rep
      for (info ← list) {
        info.path match {
          case this.path1 ⇒ assert(info.comment == this.comment3)
          case this.path2 ⇒ assert(info.comment == this.comment1)
          case x          ⇒ if (x.getName != "README") sys.error("Test failed for " + info)
        }
      }
      system.stop(csActor)
    }
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }
}
