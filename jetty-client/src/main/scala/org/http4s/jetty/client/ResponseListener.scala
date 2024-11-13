/*
 * Copyright 2018 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package jetty
package client

import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.Stream._
import fs2._
import org.eclipse.jetty.client.Result
import org.eclipse.jetty.client.{Response => JettyResponse}
import org.eclipse.jetty.http.HttpFields
import org.eclipse.jetty.http.{HttpVersion => JHttpVersion}
import org.http4s.internal.CollectionCompat.CollectionConverters._
import org.http4s.jetty.client.ResponseListener.Item
import org.http4s.jetty.client.internal.loggingAsyncCallback
import org.log4s.getLogger

import java.nio.ByteBuffer

private[jetty] final case class ResponseListener[F[_]](
    queue: Queue[F, Option[Item]],
    cb: Callback[Resource[F, Response[F]]],
    dispatcher: Dispatcher[F],
)(implicit F: Async[F])
    extends JettyResponse.Listener {
  import ResponseListener.logger

  /* Needed to properly propagate client errors */
  private[this] var responseSent = false

  override def onHeaders(response: JettyResponse): Unit = {
    val r = Status
      .fromInt(response.getStatus)
      .map { s =>
        responseSent = true
        Resource.pure[F, Response[F]](
          Response(
            status = s,
            httpVersion = getHttpVersion(response.getVersion),
            headers = getHeaders(response.getHeaders),
            body = Stream.fromQueueNoneTerminated(queue).repeatPull {
              _.uncons1.flatMap {
                case None => Pull.pure(None)
                case Some((Item.Done, _)) => Pull.pure(None)
                case Some((Item.Buf(b), tl)) => Pull.output(Chunk.byteBuffer(b)).as(Some(tl))
                case Some((Item.Raise(t), _)) => Pull.raiseError[F](t)
              }
            },
          )
        )
      }
      .leftMap { t => abort(t, response); t }

    dispatcher.unsafeRunAndForget(
      F.delay(cb(r)).attempt.flatMap(loggingAsyncCallback[F, Unit](logger))
    )
  }

  private def getHttpVersion(version: JHttpVersion): HttpVersion =
    version match {
      case JHttpVersion.HTTP_1_1 => HttpVersion.`HTTP/1.1`
      case JHttpVersion.HTTP_2 => HttpVersion.`HTTP/2`
      case JHttpVersion.HTTP_1_0 => HttpVersion.`HTTP/1.0`
      case _ => HttpVersion.`HTTP/1.1`
    }

  private def getHeaders(headers: HttpFields): Headers =
    Headers(headers.asScala.map(h => h.getName -> h.getValue).toList)

  override def onContent(
      response: JettyResponse,
      content: ByteBuffer,
  ): Unit = {
    val copy = ByteBuffer.allocate(content.remaining())
    copy.put(content).flip()
    enqueueSync(Item.Buf(copy)) {
      case Right(_) =>
        F.unit
      case Left(e) =>
        F.delay(logger.error(e)("Error in asynchronous callback"))
    }
  }

  override def onFailure(response: JettyResponse, failure: Throwable): Unit =
    if (responseSent) enqueue(Item.Raise(failure))(_ => F.unit)
    else
      dispatcher.unsafeRunAndForget(
        F.delay(cb(Left(failure))).attempt.flatMap(loggingAsyncCallback[F, Unit](logger))
      )

  // the entire response has been received
  override def onSuccess(response: JettyResponse): Unit =
    closeStream()

  // the entire req/resp conversation has completed
  // (the request might complete after the response has been entirely received)
  override def onComplete(result: Result): Unit = ()

  private def abort(t: Throwable, response: JettyResponse): Unit = {
    import scala.compat.java8.FutureConverters._

    dispatcher.unsafeRunAndForget(
      Async[F]
        .fromFuture(F.delay(response.abort(t).toScala))
        .map { aborted =>
          if (!aborted)
            logger.error(t)("Failed to abort the response")
          else
            closeStream()
        }
        .attempt
        .flatMap(loggingAsyncCallback[F, Unit](logger))
    )
  }

  private def closeStream(): Unit =
    enqueue(Item.Done)(loggingAsyncCallback[F, Unit](logger))

  private def enqueue(item: Item)(cb: Either[Throwable, Unit] => F[Unit]): Unit =
    dispatcher.unsafeRunAndForget(queue.offer(item.some).attempt.flatMap(cb))

  private def enqueueSync(item: Item)(cb: Either[Throwable, Unit] => F[Unit]): Unit =
    dispatcher.unsafeRunSync(queue.offer(item.some).attempt.flatMap(cb))
}

private[jetty] object ResponseListener {
  sealed trait Item
  object Item {
    case object Done extends Item
    // scalafix:off Http4sGeneralLinters; bincompat until 1.0
    case class Raise(t: Throwable) extends Item
    case class Buf(b: ByteBuffer) extends Item
    // scalafix:on
  }

  private val logger = getLogger

  def apply[F[_]](
      cb: Callback[Resource[F, Response[F]]],
      dispatcher: Dispatcher[F],
  )(implicit F: Async[F]): F[ResponseListener[F]] =
    Queue
      .synchronous[F, Option[Item]]
      .map(q => ResponseListener(q, cb, dispatcher))
}
