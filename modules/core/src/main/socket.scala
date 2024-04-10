package lila.core
package socket

import play.api.libs.json.*
import alleycats.Zero
import _root_.chess.{ Color, Centis }

import lila.core.userId.UserId
import lila.core.data.LazyFu

opaque type IsOnline = UserId => Boolean
object IsOnline extends FunctionWrapper[IsOnline, UserId => Boolean]

opaque type OnlineIds = () => Set[UserId]
object OnlineIds extends FunctionWrapper[OnlineIds, () => Set[UserId]]

opaque type SocketVersion = Int
object SocketVersion extends OpaqueInt[SocketVersion]:
  given Zero[SocketVersion] = Zero(0)

opaque type SocketSend = String => Unit
object SocketSend extends FunctionWrapper[SocketSend, String => Unit]

type Channel     = String
type Parallelism = Int
type ChannelSend = Channel => SocketSend

trait ParallelSocketSend:
  def apply(msg: String): Unit
  def sticky(_id: String, msg: String): Unit
  def send = SocketSend(apply)
type ParallelChannelSend = (Channel, Parallelism) => ParallelSocketSend

type SocketHandler = PartialFunction[protocol.In, Unit]

case class GetVersion(promise: Promise[SocketVersion])
case class SendToFlag(flag: String, message: JsObject)
case class SendTo(userId: UserId, message: JsObject)
case class SendToOnlineUser(userId: UserId, message: LazyFu[JsObject])
object SendTo:
  def apply[A: Writes](userId: UserId, typ: String, data: A): SendTo =
    SendTo(userId, Json.obj("t" -> typ, "d" -> data))
case class SendTos(userIds: Set[UserId], message: JsObject)
object SendTos:
  def apply[A: Writes](userIds: Set[UserId], typ: String, data: A): SendTos =
    SendTos(userIds, Json.obj("t" -> typ, "d" -> data))

case class ApiUserIsOnline(userId: UserId, isOnline: Boolean)

// Socket Random Id - an unique identifier for a socket connection
// Generated by clients when initiating a connection:
// https://github.com/lichess-org/lila/blob/master/ui/site/src/component/sri.ts#L3-L6
opaque type Sri = String
object Sri extends OpaqueString[Sri]

case class Sris(sris: Set[Sri]) // pattern matched

type Subscribe = (Channel, protocol.In.Reader) => SocketHandler => Funit

case class SocketKit(
    subscribe: Subscribe,
    send: ChannelSend,
    baseHandler: SocketHandler
)
case class ParallelSocketKit(
    subscribe: (Channel, protocol.In.Reader, Parallelism) => SocketHandler => Funit,
    send: ParallelChannelSend,
    baseHandler: SocketHandler
)

def makeMessage[A](t: String, data: A)(using writes: Writes[A]): JsObject =
  JsObject(Map.Map2("t", JsString(t), "d", writes.writes(data)))

def makeMessage(t: String): JsObject = JsObject(Map.Map1("t", JsString(t)))

object protocol:

  type Args = String

  final class RawMsg(val path: String, val args: Args):
    def get(nb: Int)(f: PartialFunction[Array[String], Option[In]]): Option[In] =
      f.applyOrElse(args.split(" ", nb), (_: Array[String]) => None)
    def all = args.split(' ')

  object RawMsg:
    def unapply(msg: RawMsg): Option[(String, RawMsg)] = Some(msg.path -> msg)

  trait In
  object In:

    type Reader     = PartialFunction[RawMsg, Option[In]]
    type FullReader = RawMsg => Option[In]

    def commas(str: String): Array[String]    = if str == "-" then Array.empty else str.split(',')
    def boolean(str: String): Boolean         = str == "+"
    def optional(str: String): Option[String] = if str == "-" then None else Some(str)

    def tellSriMapper: PartialFunction[Array[String], Option[TellSri]] = { case Array(sri, user, payload) =>
      for
        obj <- Json.parse(payload).asOpt[JsObject]
        typ <- obj.str("t")
      yield TellSri(Sri(sri), UserId.from(optional(user)), typ, obj)
    }

    case object WsBoot                                                               extends In
    case class ConnectUser(userId: UserId)                                           extends In
    case class ConnectUsers(userIds: Iterable[UserId])                               extends In
    case class DisconnectUsers(userIds: Iterable[UserId])                            extends In
    case class ConnectSris(cons: Iterable[(Sri, Option[UserId])])                    extends In
    case class DisconnectSris(sris: Iterable[Sri])                                   extends In
    case class NotifiedBatch(userIds: Iterable[UserId])                              extends In
    case class Lag(userId: UserId, lag: Centis)                                      extends In
    case class Lags(lags: Map[UserId, Centis])                                       extends In
    case class TellSri(sri: Sri, userId: Option[UserId], typ: String, msg: JsObject) extends In
    case class TellUser(userId: UserId, typ: String, msg: JsObject)                  extends In
    case class ReqResponse(reqId: Int, response: String)                             extends In
    case class Ping(id: String)                                                      extends In

  object Out:

    def boot             = "boot"
    def pong(id: String) = s"pong $id"
    def tellSri(sri: Sri, payload: JsValue) =
      s"tell/sri $sri ${Json.stringify(payload)}"
    def tellSris(sris: Iterable[Sri], payload: JsValue) =
      s"tell/sris ${commas(sris)} ${Json.stringify(payload)}"

    def commas(strs: Iterable[Any]): String = if strs.isEmpty then "-" else strs.mkString(",")
    def boolean(v: Boolean): String         = if v then "+" else "-"
    def optional(str: Option[String])       = str.getOrElse("-")
    def color(c: Color): String             = c.fold("w", "b")
    def color(c: Option[Color]): String     = optional(c.map(_.fold("w", "b")))

object userLag:
  type GetLagRating = UserId => Option[Int]
  type Put          = (UserId, Centis) => Unit

type SocketRequest[R] = (Int => Unit, String => R) => Fu[R]
trait SocketRequester:
  def apply[R]: SocketRequest[R]

object remote:
  case class TellSriIn(sri: String, user: Option[UserId], msg: JsObject)
  case class TellSriOut(sri: String, payload: JsValue)
  case class TellSrisOut(sris: Iterable[String], payload: JsValue)
  case class TellUserIn(user: UserId, msg: JsObject)
