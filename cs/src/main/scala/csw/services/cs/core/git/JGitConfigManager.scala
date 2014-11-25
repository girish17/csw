package csw.services.cs.core.git

import java.io.File
import java.net.URI
import java.{ lang, util }

import csw.services.cs.JConfigManager
import csw.services.cs.core.{ ConfigData, ConfigFileHistory, ConfigFileInfo, ConfigId }

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Await }

/**
 * Uses JGit to manage versions of configuration files.
 *
 * Note: This version is for use by Java applications. Scala applications should use
 * [[csw.services.cs.core.git.GitConfigManager]].
 */
case class JGitConfigManager(gitWorkDir: File, remoteRepo: URI)(implicit dispatcher: ExecutionContextExecutor)
    extends JConfigManager {

  private val manager = GitConfigManager(gitWorkDir, remoteRepo)

  // XXX For now, wait for results in the Java version.
  // Later, if needed, we could convert to Java futures (maybe in Scala 2.12, when Java8 support is better)
  private val timeout = 10.seconds

  override def create(path: File, configData: ConfigData, oversize: lang.Boolean, comment: String): ConfigId = {
    Await.result(manager.create(path, configData, oversize, comment), timeout)
  }

  override def update(path: File, configData: ConfigData, comment: String): ConfigId = {
    Await.result(manager.update(path, configData, comment), timeout)
  }

  override def get(path: File): ConfigData = {
    Await.result(manager.get(path), timeout).orNull
  }

  override def get(path: File, id: ConfigId): ConfigData = {
    Await.result(manager.get(path, Some(id)), timeout).orNull
  }

  override def exists(path: File): Boolean = {
    Await.result(manager.exists(path), timeout)
  }

  override def delete(path: File): Unit = {
    Await.result(manager.delete(path), timeout)
  }

  override def delete(path: File, comment: String): Unit = {
    Await.result(manager.delete(path, comment), timeout)
  }

  override def list(): util.List[ConfigFileInfo] = {
    Await.result(manager.list().map(_.asJava), timeout)
  }

  override def history(path: File): util.List[ConfigFileHistory] = {
    Await.result(manager.history(path).map(_.asJava), timeout)
  }
}
