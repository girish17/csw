package csw.services.cs.core.git

import java.io.{File, FileNotFoundException, IOException}
import java.net.URI
import java.nio.file.Files
import java.util.Date

import akka.actor.ActorRefFactory
import com.typesafe.scalalogging.LazyLogging
import csw.services.apps.configServiceAnnex.ConfigServiceAnnexClient
import csw.services.cs.core.{ConfigIdImpl, _}
import net.codejava.security.HashGeneratorUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib._
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * Used to initialize an instance of GitConfigManager with a given repository directory
 */
object GitConfigManager {

  private val tmpDir = System.getProperty("java.io.tmpdir")

  // $file.default holds the id of the default version of file
  private val defaultSuffix = ".default"

  // $file.sha1 holds the SHA-1 hash of oversize files that are stored on the config service annex http server
  private val sha1Suffix = ".sha1"

  /**
   * Creates and returns a GitConfigManager instance using the given directory as the
   * local Git repository root (directory containing .git dir) and the given
   * URI as the remote, central Git repository.
   * If the local repository already exists, it is opened, otherwise it is created.
   * An exception is thrown if the remote repository does not exist.
   *
   * @param gitWorkDir top level directory to use for storing configuration files and the local git repository (under .git)
   * @param remoteRepo the URI of the remote, main repository
   * @param name the name of this service
   * @return a new GitConfigManager configured to use the given local and remote repositories
   */
  def apply(gitWorkDir: File, remoteRepo: URI, name: String = "Config Service")(implicit context: ActorRefFactory): GitConfigManager = {
    // Init local repo
    val gitDir = new File(gitWorkDir, ".git")
    if (gitDir.exists()) {
      val git = new Git(new FileRepositoryBuilder().setGitDir(gitDir).build())
      val result = git.pull.call
      if (!result.isSuccessful) throw new IOException(result.toString)
      new GitConfigManager(git, name)
    } else {
      gitWorkDir.mkdirs()
      val git = Git.cloneRepository.setDirectory(gitWorkDir).setURI(remoteRepo.toString).call
      new GitConfigManager(git, name)
    }
  }

  /**
   * FOR TESTING: Deletes the contents of the given directory (recursively).
   * This is meant for use by tests that need to always start with an empty Git repository.
   */
  def deleteDirectoryRecursively(dir: File): Unit = {
    // just to be safe, don't delete anything that is not in /tmp/
    val p = dir.getPath
    if (!p.startsWith("/tmp/") && !p.startsWith(tmpDir))
      throw new RuntimeException(s"Refusing to delete $dir since not in /tmp/ or $tmpDir")

    if (dir.isDirectory) {
      dir.list.foreach {
        filePath =>
          val file = new File(dir, filePath)
          if (file.isDirectory) {
            deleteDirectoryRecursively(file)
          } else {
            file.delete()
          }
      }
      dir.delete()
    }
  }

  /**
   * Initializes a bare (main) repository in the given dir.
   * (A bare repository is one containing only the .git dir and no checked out files.)
   * This is used to create the main git repository that all the local repos point to.
   *
   * Note: We need to add at least one file after creating the main repository to avoid errors
   * later when pulling from an empty repo. Somehow adding the file also initializes
   * the master branch. The problem shows up when multiple clients create an instance
   * of GitConfigManager and try to pull from an empty, newly created git repo.
   *
   * @param dir directory to contain the new bare repository
   */
  def initBareRepo(dir: File)(implicit context: ActorRefFactory): Unit = {
    // Create the new main repo
    Git.init.setDirectory(dir).setBare(true).call

    // Add a README file to a temporary clone of the main repo and push it to the new repo.
    val tmpDir = Files.createTempDirectory("TempConfigServiceRepo").toFile
    val gm = GitConfigManager(tmpDir, dir.toURI)
    try {
      // XXX TODO: return Future[Unit] instead?
      Await.result(
        gm.create(new File("README"), ConfigData("This is the main Config Service Git repository.")),
        10.seconds
      )
    } finally {
      deleteDirectoryRecursively(tmpDir)
    }
  }

  //  // Sets the master repository (needed for git push/pull commands)
  //  private def trackMaster(git: Git): Unit = {
  //    git.branchCreate()
  //      .setName("master")
  //      .setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM)
  //      .setStartPoint("origin/master")
  //      .setForce(true)
  //      .call
  //  }
}

/**
 * Uses JGit to manage versions of configuration files.
 * Special handling is available for large/binary files (oversize option in create).
 * Oversize files are stored on an "annex" server using the SHA-1 hash of the file
 * contents for the name (similar to the way Git stores file objects).
 *
 * Note that although the API is non-blocking, we need to be careful when dealing
 * with the file system (the local Git repo), which is static, and not attempt multiple
 * conflicting file read, write or Git operations at once. The remote (bare) Git repo should
 * be able to handle the concurrent usage, but not the local repo, which has files in the
 * working directory. Having the files checked out in working directory should help avoid
 * having to download them every time an application starts.
 *
 * Only one instance of this class should exist for a given local Git repository.
 *
 * @param git used to access Git
 * @param name the name of the service
 */
class GitConfigManager(val git: Git, override val name: String)(implicit context: ActorRefFactory)
    extends ConfigManager with LazyLogging {

  import context.dispatcher

  // used to access the http server that manages oversize files
  lazy val annex = ConfigServiceAnnexClient

  override def create(path: File, configData: ConfigData, oversize: Boolean, comment: String): Future[ConfigId] = {
    def createOversize(file: File): Future[ConfigId] = {
      val sha1File = shaFile(file)
      if (file.exists() || sha1File.exists()) {
        Future.failed(new IOException("File already exists in repository: " + file))
      } else for {
        _ <- configData.writeToFile(file)
        sha1 <- annex.post(file)
        configId <- create(shaFile(path), ConfigData(sha1), oversize = false, comment)
      } yield configId
    }

    logger.debug(s"$name: create $path")
    val file = fileForPath(path)
    if (oversize) {
      createOversize(file)
    } else {
      if (file.exists()) {
        Future.failed(new IOException("File already exists in repository: " + path))
      } else {
        put(path, configData, comment)
      }
    }

  }

  override def update(path: File, configData: ConfigData, comment: String): Future[ConfigId] = {
    def updateOversize(file: File): Future[ConfigId] = {
      for {
        _ <- configData.writeToFile(file)
        sha1 <- annex.post(file)
        configId <- update(shaFile(path), ConfigData(sha1), comment)
      } yield configId
    }

    logger.debug(s"$name: update $path")
    val file = fileForPath(path)
    Future(pull()).flatMap { _ =>
      if (isOversize(file)) {
        updateOversize(file)
      } else {
        if (!file.exists()) {
          Future.failed(new FileNotFoundException("File not found: " + path))
        } else {
          put(path, configData, comment)
        }
      }
    }
  }

  override def createOrUpdate(path: File, configData: ConfigData, oversize: Boolean, comment: String): Future[ConfigId] =
    for {
      exists <- exists(path)
      result <- if (exists) update(path, configData, comment) else create(path, configData, oversize, comment)
    } yield result

  override def exists(path: File): Future[Boolean] = Future {
    logger.debug(s"$name: exists $path")
    pull()
    val file = fileForPath(path)
    logger.debug(s"$name: exists $path: $file exists? ${file.exists} || oversize? ${isOversize(file)}")
    file.exists || isOversize(file)
  }

  override def delete(path: File, comment: String = "deleted"): Future[Unit] = {
    def deleteFile(path: File, comment: String = "deleted"): Unit = {
      logger.debug(s"$name: delete $path")
      val file = fileForPath(path)
      pull()
      if (isOversize(file)) {
        deleteFile(shaFile(path), comment)
        if (file.exists()) file.delete()
      } else {
        if (!file.exists) {
          throw new FileNotFoundException("Can't delete " + path + " because it does not exist")
        }
        git.rm.addFilepattern(path.getPath).call()
        git.commit().setMessage(comment).call
        git.push.call()
        if (file.exists()) file.delete()
      }
    }

    Future {
      deleteFile(path, comment)
    }
  }

  override def get(path: File, id: Option[ConfigId]): Future[Option[ConfigData]] = {

    // Get oversize files that are stored in the annex server
    def getOversize(file: File): Future[Option[ConfigData]] = {
      for {
        opt <- get(shaFile(path), id)
        data <- getData(file, opt)
      } yield data

    }
    // Gets the actual file data using the SHA-1 value contained in the checked in file
    def getData(file: File, opt: Option[ConfigData]): Future[Option[ConfigData]] = {
      opt match {
        case None => Future(None)
        case Some(configData) =>
          for {
            sha1 <- configData.toFutureString
            configDataOpt <- getIfNeeded(file, sha1)
          } yield configDataOpt
      }
    }

    // If the file matches the SHA-1 hash, return a future for it, otherwise get it from the annex server
    def getIfNeeded(file: File, sha1: String): Future[Option[ConfigData]] = {
      if (!file.exists() || (sha1 != HashGeneratorUtils.generateSHA1(file))) {
        annex.get(sha1, file).map {
          _ => Some(ConfigData(file))
        }
      } else {
        Future(Some(ConfigData(file)))
      }
    }

    // Returns the contents of the given version of the file, if found
    def getConfigData(file: File): Option[ConfigData] = {
      if (!file.exists) {
        // assumes git pull was done, so file should be in working dir
        None
      } else {
        if (id.isDefined) {
          // return the file for the given id
          val objId = ObjectId.fromString(id.get.asInstanceOf[ConfigIdImpl].id)
          Some(ConfigData(git.getRepository.open(objId).getBytes))
        } else {
          // return the latest version of the file from the working dir
          Some(ConfigData(file))
        }
      }
    }

    logger.debug(s"$name: get $path")
    val file = fileForPath(path)

    Future(pull()).flatMap { _ =>
      if (isOversize(file)) {
        getOversize(file)
      } else {
        Future(getConfigData(file))
      }
    }
  }

  override def list(): Future[List[ConfigFileInfo]] = Future {

    // Returns a list containing all known configuration files by walking the Git tree recursively and
    // collecting the resulting file info.
    @tailrec
    def list(treeWalk: TreeWalk, result: List[ConfigFileInfo]): List[ConfigFileInfo] = {
      if (treeWalk.next()) {
        val pathStr = treeWalk.getPathString
        if (pathStr.endsWith(GitConfigManager.defaultSuffix)) {
          list(treeWalk, result)
        } else {
          val path = new File(pathStr)
          val origPath = origFile(path)
          val objectId = treeWalk.getObjectId(0).name
          val info = new ConfigFileInfo(origPath, ConfigIdImpl(objectId), hist(origPath, 1).head.comment)
          list(treeWalk, info :: result)
        }
      } else {
        result
      }
    }

    logger.debug(s"$name: list")
    pull()
    val repo = git.getRepository

    // Resolve the revision specification
    val id = repo.resolve("HEAD")

    // Get the commit object for that revision
    val walk = new RevWalk(repo)
    val commit = walk.parseCommit(id)

    // Get the commit's file tree
    val tree = commit.getTree

    val treeWalk = new TreeWalk(repo)
    treeWalk.setRecursive(true)
    treeWalk.addTree(tree)

    val result = list(treeWalk, List())
    walk.dispose()
    result
  }

  override def history(path: File, maxResults: Int = Int.MaxValue): Future[List[ConfigFileHistory]] =
    Future(hist(path, maxResults))

  private def hist(path: File, maxResults: Int = Int.MaxValue): List[ConfigFileHistory] = {
    // XXX Should .sha1 files have the .sha1 suffix removed in the result?
    logger.debug(s"$name: history $path")
    pull()
    // Check sha1 file history first (may have been deleted, so don't check if it exists)
    val shaPath = shaFile(path)
    val logCommand = git.log
      .add(git.getRepository.resolve(Constants.HEAD))
      .addPath(shaPath.getPath)
    val result = hist(shaPath, logCommand.call.iterator(), List(), maxResults)
    if (result.nonEmpty) {
      result
    } else {
      val logCommand = git.log
        .add(git.getRepository.resolve(Constants.HEAD))
        .addPath(path.getPath)
      hist(path, logCommand.call.iterator(), List(), maxResults)
    }
  }

  // Returns a list of all known versions of a given path by recursively walking the Git history tree
  @tailrec
  private def hist(
    path:       File,
    it:         java.util.Iterator[RevCommit],
    result:     List[ConfigFileHistory],
    maxResults: Int
  ): List[ConfigFileHistory] = {
    if (it.hasNext && result.size < maxResults) {
      val revCommit = it.next()
      val tree = revCommit.getTree
      val treeWalk = TreeWalk.forPath(git.getRepository, path.getPath, tree)
      if (treeWalk == null) {
        hist(path, it, result, maxResults)
      } else {
        val objectId = treeWalk.getObjectId(0)
        // TODO: Should comments be allowed to contain newlines? Might want to use longMessage?
        val comment = revCommit.getShortMessage
        val time = new Date(revCommit.getCommitTime * 1000L)
        val info = new ConfigFileHistory(ConfigIdImpl(objectId.name), comment, time)
        hist(path, it, result :+ info, maxResults)
      }
    } else {
      result
    }
  }

  /**
   * Creates or updates a config file with the given path and data and optional comment.
   *
   * @param path the config file path
   * @param configData the contents of the file
   * @param comment an optional comment to associate with this file
   * @return a future unique id that can be used to refer to the file
   */
  private def put(path: File, configData: ConfigData, comment: String = ""): Future[ConfigId] = {
    val file = fileForPath(path)
    for {
      _ <- configData.writeToFile(file)
      configId <- Future {
        val dirCache = git.add.addFilepattern(path.getPath).call()
        // git.commit().setCommitter(name, email) // XXX using defaults from ~/.gitconfig for now
        git.commit().setOnly(path.getPath).setMessage(comment).call
        git.push.call()
        ConfigId(dirCache.getEntry(path.getPath).getObjectId.getName)
      }
    } yield configId
  }

  /**
   * Does a "git pull" to update the local repo with any changes made from the outside
   */
  private def pull(): Unit = {
    val result = git.pull.setStrategy(MergeStrategy.THEIRS).call
    if (!result.isSuccessful) throw new IOException(result.toString)
  }

  // Returns the absolute path of the file in the Git repository working tree
  private def fileForPath(path: File): File = new File(git.getRepository.getWorkTree, path.getPath)

  // File used to store the SHA-1 of the actual file, if oversized.
  private def shaFile(file: File): File =
    new File(s"${file.getPath}${GitConfigManager.sha1Suffix}")

  // Inverse of shaFile
  private def origFile(file: File): File =
    if (file.getPath.endsWith(GitConfigManager.sha1Suffix)) new File(file.getPath.dropRight(5)) else file

  // True if the .sha1 file exists, meaning the file needs special oversize handling.
  // Note: We only check if it exists in the working directory, not the repository.
  // Since the constructor does a git pull already, we assume all files that were not deleted are in the working dir.
  private def isOversize(file: File): Boolean = shaFile(file).exists

  // --- Default version handling ---

  // Returns the current version of the file, if known
  private def getCurrentVersion(path: File): Option[ConfigId] = {
    hist(path, 1).headOption.map(_.id)
  }

  // File used to store the id of the default version of the file.
  private def defaultFile(file: File): File =
    new File(s"${file.getPath}${GitConfigManager.defaultSuffix}")

  // True if the .default file exists, meaning the file has a default version set.
  // Note: We only check if it exists in the working directory, not the repository.
  // Since the constructor and most methods do a git pull already,
  // we assume all files that were not deleted are in the working dir.
  private def hasDefault(file: File): Boolean = defaultFile(file).exists

  def setDefault(path: File, id: Option[ConfigId] = None): Future[Unit] = {
    logger.debug(s"$name: setDefault $path $id")
    (if (id.isDefined) id else getCurrentVersion(path)) match {
      case Some(configId) =>
        create(defaultFile(path), ConfigData(configId.id)).map(_ => ())
      case None =>
        Future.failed(new RuntimeException(s"Unknown path $path"))
    }
  }

  def resetDefault(path: File): Future[Unit] = {
    logger.debug(s"$name: resetDefault $path")
    delete(defaultFile(path))
  }

  def getDefault(path: File): Future[Option[ConfigData]] = {
    logger.debug(s"$name: getDefault $path")
    val currentId = getCurrentVersion(path)
    if (currentId.isEmpty)
      Future(None)
    else for {
      d <- get(defaultFile(path))
      id <- if (d.isDefined) d.get.toFutureString else Future(currentId.get.id)
      result <- get(path, Some(ConfigId(id)))
    } yield result
  }
}
