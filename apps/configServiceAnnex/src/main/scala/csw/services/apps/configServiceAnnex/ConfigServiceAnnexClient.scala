package csw.services.apps.configServiceAnnex

import java.io.{File, FileOutputStream, IOException}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.stream.scaladsl.Sink
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import net.codejava.security.HashGeneratorUtils
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}
import scala.util.{Try, Failure, Success}

/**
 * Provides a client API to the ConfigServiceAnnexServer.
 */
object ConfigServiceAnnexClient {
  val logger = Logger(LoggerFactory.getLogger(ConfigServiceAnnexClient.getClass))

  // Note: We could take the actor system as an implicit argument from the caller,
  // but using a separate one was suggested, to avoid congestion and slowing down actor
  // messages while large files are being transferred.
  implicit val system = ActorSystem("ConfigServiceAnnexClient")

  import system.dispatcher

  val settings = ConfigServiceAnnexSettings(system)
  val host = settings.interface
  val port = settings.port
  implicit val askTimeout = settings.timeout

  /**
   * Downloads the file with the given id from the server and saves it to the given file.
   * Once the file is downloaded, the content is validated against the SHA-1 id.
   * @param id the id of the file to get (SHA-1 hash of the contents)
   * @param file the local file in which to store the contents returned from the server
   * @return a future containing the file, if successful, or the exception if there was an error
   */
  def get(id: String, file: File): Future[File] = {
    val uri = s"http://$host:$port/$id"
    logger.debug(s"Downloading $file from $uri")
    implicit val materializer = ActorMaterializer()

    val out = new FileOutputStream(file)
    val sink = Sink.foreach[ByteString] { bytes =>
      out.write(bytes.toArray)
    }

    val connection = Http().outgoingConnection(host, port)
    val request = HttpRequest(GET, uri = s"/$id")
    val result = sendRequest(request, connection)

    val status = Promise[File]()
    result onComplete {
      case Success(res) if res.status == StatusCodes.OK =>
        val materialized = res.entity.dataBytes.runWith(sink)
        // ensure the output file is closed and the system shutdown upon completion
        materialized.onComplete {
          case Success(_) =>
            Try(out.close())
            FileUtils.validate(id, file, status)
          case Failure(e) =>
            Try(out.close())
            logger.error(s"Failed to download $uri to $file: ${e.getMessage}")
            status.failure(e)
        }

      case Success(res) =>
        Try(out.close())
        val s = s"HTTP response code for $uri: ${res.status}"
        logger.error(s)
        status.failure(new IOException(s))

      case Failure(error) =>
        Try(out.close())
        logger.error(s"$error")
        status.failure(error)
    }

    status.future
  }

  /**
   * Uploads the given file to the server, storing it under the given SHA-1 hash of the contents.
   * @param file the local file to upload to the server
   * @return a future containing the file id (SHA-1), if successful, or the exception if there was an error
   */
  def post(file: File): Future[String] = {
    val id = HashGeneratorUtils.generateSHA1(file)
    val uri = s"http://$host:$port/$id"
    logger.debug(s"Uploading $file to $uri")
    implicit val materializer = ActorMaterializer()

    val mappedByteBuffer = FileUtils.mmap(file.toPath)
    val iterator = new FileUtils.ByteBufferIterator(mappedByteBuffer, settings.chunkSize)
    val chunks = Source.fromIterator(() => iterator).map(ChunkStreamPart.apply)
    val entity = HttpEntity.Chunked(MediaTypes.`application/octet-stream`, chunks)
    val connection = Http().outgoingConnection(host, port)
    val request = HttpRequest(method = POST, uri = s"/$id", entity = entity)
    val result = sendRequest(request, connection)

    val status = Promise[String]()
    result onComplete {
      case Success(res) if res.status == StatusCodes.OK =>
        status.success(id)

      case Success(res) =>
        val s = s"HTTP response code for $uri: ${res.status}"
        logger.error(s)
        status.failure(new IOException(s))

      case Failure(error) =>
        logger.error(s"$error")
        status.failure(error)
    }
    status.future
  }

  /**
   * Checks the existence of the file with the given id on the server
   * @param id the id of the file to check (SHA-1 hash of the contents)
   * @return a future boolean indicating the existence of the file, if successful,
   *         or the exception if there was an HTTP error
   */
  def head(id: String): Future[Boolean] = {
    val uri = s"http://$host:$port/$id"
    logger.debug(s"Checking existence of $uri")
    implicit val materializer = ActorMaterializer()

    val connection = Http().outgoingConnection(host, port)
    val request = HttpRequest(HEAD, uri = s"/$id")
    val result = sendRequest(request, connection)

    val status = Promise[Boolean]()
    result onComplete {
      case Success(res) =>
        status.success(res.status == StatusCodes.OK)

      case Failure(error) =>
        logger.error(s"$error")
        status.failure(error)
    }
    status.future
  }

  /**
   * Deletes the file with the given id on the server (Should normally only be used in tests).
   * @param id the id of the file to delete (SHA-1 hash of the contents)
   * @return a future boolean: true if the file could be deleted or did not exist,
   *         or the exception if the file could not be deleted or there was an HTTP error
   */
  def delete(id: String): Future[Boolean] = {
    val uri = s"http://$host:$port/$id"
    logger.debug(s"Deleting $uri")
    implicit val materializer = ActorMaterializer()

    val connection = Http().outgoingConnection(host, port)
    val request = HttpRequest(DELETE, uri = s"/$id")
    val result = sendRequest(request, connection)

    val status = Promise[Boolean]()
    result onComplete {
      case Success(res) =>
        status.success(res.status == StatusCodes.OK)

      case Failure(error) =>
        logger.error(s"$error")
        status.failure(error)
    }
    status.future
  }

  private def sendRequest(
    request:    HttpRequest,
    connection: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]
  )(implicit fm: ActorMaterializer): Future[HttpResponse] = {
    Source.single(request).via(connection).runWith(Sink.head)
  }

  def shutdown(): Unit = system.terminate()
}
