package fr.cytech.icc

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

enum RoomListMessage {
  case CreateRoom(name: String)
  case GetRoom(name: String, replyTo: ActorRef[Option[ActorRef[Message]]])
  case ListRooms(replyTo: ActorRef[Iterable[String]])
}

object RoomListActor {

  import RoomListMessage.*

  def apply(rooms: Map[String, ActorRef[Message]] = Map.empty): Behavior[RoomListMessage] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case CreateRoom(name) => {
          if (rooms.contains(name)) {
            Behaviors.same
          } else {
            val ref = context.spawn(RoomActor(name), s"RoomActor-$name")
            RoomListActor(rooms + (name -> ref))
          }
        }
        case GetRoom(name, replyTo) => {
          replyTo ! rooms.get(name)
          Behaviors.same
        }
        case ListRooms(replyTo) => {
          replyTo ! rooms.keys
          Behaviors.same
        }
      }
    }
  }
}
