package fr.cytech.icc

import RoomListMessage.{CreateRoom, GetRoom, ListRooms}

import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.dispatch.sysmsg.Create
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.util.Timeout
import spray.json.*

import java.time.OffsetDateTime
import java.util.UUID
import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

case class PostInput(author: String, content: String)

case class RoomInput(name: String)

case class PostOutput(id: UUID, room: String, author: String, content: String, postedAt: OffsetDateTime)

object PostOutput {

  extension (post: Post) {

    def output(roomId: String): PostOutput = PostOutput(
      id = post.id,
      room = roomId,
      author = post.author,
      content = post.content,
      postedAt = post.postedAt
    )
  }
}

given Ordering[PostOutput] = Ordering.by(_.postedAt)

case class RoomOutput(name: String, posts: SortedSet[PostOutput])

case class Controller(
    rooms: ActorRef[RoomListMessage]
  )(using
    ExecutionContext,
    Timeout,
    Scheduler)
    extends Directives,
      SprayJsonSupport,
      DefaultJsonProtocol {

  import PostOutput.output

  given JsonFormat[UUID] = new JsonFormat[UUID] {
    def write(uuid: UUID): JsValue = JsString(uuid.toString)

    def read(value: JsValue): UUID = {
      value match {
        case JsString(uuid) => UUID.fromString(uuid)
        case _              => throw DeserializationException("Expected hexadecimal UUID string")
      }
    }
  }

  given JsonFormat[OffsetDateTime] = new JsonFormat[OffsetDateTime] {
    def write(dateTime: OffsetDateTime): JsValue = JsString(dateTime.toString)

    def read(value: JsValue): OffsetDateTime = {
      value match {
        case JsString(dateTime) => OffsetDateTime.parse(dateTime)
        case _                  => throw DeserializationException("Expected ISO 8601 OffsetDateTime string")
      }
    }
  }

  given JsonFormat[SortedSet[PostOutput]] = new JsonFormat[SortedSet[PostOutput]] {
    def write(posts: SortedSet[PostOutput]): JsValue = JsArray(posts.toVector.map(_.toJson))

    def read(value: JsValue): SortedSet[PostOutput] = {
      value match {
        case JsArray(posts) => SortedSet.from(posts.map(_.convertTo[PostOutput]))
        case _              => throw DeserializationException("Not an array")
      }
    }
  }

  given RootJsonFormat[PostInput] = {
    jsonFormat2(PostInput.apply)
  }

  given RootJsonFormat[RoomInput] = {
    jsonFormat1(RoomInput.apply)
  }

  given RootJsonFormat[PostOutput] = {
    jsonFormat5(PostOutput.apply)
  }

  given RootJsonFormat[RoomOutput] = {
    jsonFormat2(RoomOutput.apply)
  }

  val routes: Route = concat(
    path("rooms") {
      post {
        entity(as[RoomInput]) { payload => createRoom(payload) }
      } ~ get {
        complete(listRooms())
      }
    },
    path("rooms" / Segment) { roomId =>
      get {
        complete(getRoom(roomId))
      }
    },
    path("rooms" / Segment / "posts") { roomId =>
      post {
        entity(as[PostInput]) { payload => complete(createPost(roomId, payload)) }
      } ~ get {
        complete(listPosts(roomId))
      }
    },
    path("rooms" / Segment / "posts" / "latest") { roomId =>
      get {
        complete(getLatestPost(roomId))
      }
    },
    path("rooms" / Segment / "posts" / Segment) { (roomId, messageId) =>
      get {
        complete(getPost(roomId, messageId))
      }
    }
  )

  private def createRoom(input: RoomInput) = {
    rooms ! CreateRoom(input.name)
    complete(StatusCodes.Created)
  }

  private def listRooms(): Future[ToResponseMarshallable] =
    rooms.ask[Iterable[String]](ref => ListRooms(ref)).flatMap { rooms => Future.sequence(rooms.map(_getRoom)) }.map {
      rooms => rooms.map(_.get)
    }

  private def getRoom(roomId: String): Future[ToResponseMarshallable] = _getRoom(roomId).map {
    case Some(room) => StatusCodes.OK -> room
    case None       => StatusCodes.NotFound
  }

  private def _getRoom(roomId: String): Future[Option[RoomOutput]] =
    rooms.ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref)).flatMap {
      case Some(roomActorRef) =>
        roomActorRef.ask[SortedSet[Post]](ref => Message.ListPosts(ref)).map { posts =>
          Some(RoomOutput(roomId, posts.map(_.output(roomId))))
        }
      case None => Future.successful(None)
    }

  private def createPost(roomId: String, input: PostInput): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) =>
          Future.successful(Some(roomActorRef ! Message.CreatePost(input.author, input.content)))
        case None => Future.successful(None)
      }
      .map {
        case Some(_) =>
          StatusCodes.Created
        case None =>
          StatusCodes.NotFound
      }

  private def listPosts(roomId: String): Future[ToResponseMarshallable] = rooms
    .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
    .flatMap {
      case Some(roomActorRef) => roomActorRef.ask[SortedSet[Post]](ref => Message.ListPosts(ref)).map(Some(_))
      case None               => Future.successful(None)
    }
    .map {
      case Some(posts) => StatusCodes.OK -> posts.map(_.output(roomId)).toJson
      case None        => StatusCodes.NotFound
    }

  private def getLatestPost(roomId: String): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) => roomActorRef.ask[Option[Post]](ref => Message.LatestPost(ref))
        case None               => Future.successful(None)
      }
      .map {
        case Some(post) =>
          StatusCodes.OK -> post.output(roomId)
        case None =>
          StatusCodes.NotFound
      }

  private def getPost(roomId: String, messageId: String): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) =>
          roomActorRef.ask[Option[Post]](ref => Message.GetPost(UUID.fromString(messageId), ref))
        case None => Future.successful(None)
      }
      .map {
        case Some(post) =>
          StatusCodes.OK -> post.output(roomId)
        case None =>
          StatusCodes.NotFound
      }
}
