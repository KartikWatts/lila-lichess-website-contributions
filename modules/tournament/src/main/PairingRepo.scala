package lila.tournament

import org.joda.time.DateTime
import reactivemongo.bson._
import reactivemongo.core.commands._

import BSONHandlers._
import lila.db.BSON._
import lila.db.Implicits._

object PairingRepo {

  private lazy val coll = Env.current.pairingColl

  private def selectId(id: String) = BSONDocument("_id" -> id)
  private def selectTour(tourId: String) = BSONDocument("tid" -> tourId)
  private def selectUser(userId: String) = BSONDocument("u" -> userId)
  private def selectTourUser(tourId: String, userId: String) = BSONDocument(
    "tid" -> tourId,
    "u" -> userId)
  private val selectPlaying = BSONDocument("s" -> BSONDocument("$lt" -> chess.Status.Mate.id))
  private val selectFinished = BSONDocument("s" -> BSONDocument("$gte" -> chess.Status.Mate.id))
  private val recentSort = BSONDocument("d" -> -1)
  private val chronoSort = BSONDocument("d" -> 1)

  def byId(id: String): Fu[Option[Pairing]] = coll.find(selectId(id)).one[Pairing]

  def recentByTour(tourId: String, nb: Int): Fu[List[Pairing]] =
    coll.find(selectTour(tourId)).sort(recentSort).cursor[Pairing]().collect[List](nb)

  def recentByTourAndUserIds(tourId: String, userIds: Iterable[String], nb: Int): Fu[List[Pairing]] =
    coll.find(
      selectTour(tourId) ++ BSONDocument("u" -> BSONDocument("$in" -> userIds))
    ).sort(recentSort).cursor[Pairing]().collect[List](nb)

  def removeByTour(tourId: String) = coll.remove(selectTour(tourId)).void

  def count(tourId: String): Fu[Int] =
    coll.count(selectTour(tourId).some)

  def removePlaying(tourId: String) = coll.remove(selectTour(tourId) ++ selectPlaying).void

  def findPlaying(tourId: String): Fu[List[Pairing]] =
    coll.find(selectTour(tourId) ++ selectPlaying).cursor[Pairing]().collect[List]()

  def findPlaying(tourId: String, userId: String): Fu[Option[Pairing]] =
    coll.find(selectTourUser(tourId, userId) ++ selectPlaying).one[Pairing]

  def finishedByPlayerChronological(tourId: String, userId: String): Fu[List[Pairing]] =
    coll.find(
      selectTourUser(tourId, userId) ++ selectFinished
    ).sort(chronoSort).cursor[Pairing]().collect[List]()

  def insert(pairing: Pairing) = coll.insert {
    pairingHandler.write(pairing) ++ BSONDocument("d" -> DateTime.now)
  }.void

  def finish(g: lila.game.Game) = coll.update(
    selectId(g.id),
    BSONDocument("$set" -> BSONDocument(
      "s" -> g.status.id,
      "w" -> g.winnerColor.map(_.white),
      "t" -> g.turns))).void

  def setBerserk(pairing: Pairing, userId: String, value: Int) = (userId match {
    case uid if pairing.user1 == uid => "b1".some
    case uid if pairing.user2 == uid => "b2".some
    case _                           => none
  }) ?? { field =>
    coll.update(
      selectId(pairing.id),
      BSONDocument("$set" -> BSONDocument(field -> value))).void
  }

  def playingUserIds(tour: Tournament): Fu[Set[String]] =
    coll.db.command(Aggregate(coll.name, Seq(
      Match(selectTour(tour.id) ++ selectPlaying),
      Project("u" -> BSONBoolean(true), "_id" -> BSONBoolean(false)),
      Unwind("u"),
      Group(BSONBoolean(true))("ids" -> AddToSet("u"))
    ))) map {
      _.headOption flatMap {
        _.getAs[Set[String]]("ids")
      } getOrElse Set.empty
    }

  def playingGameIds(tourId: String): Fu[List[String]] =
    coll.db.command(Aggregate(coll.name, Seq(
      Match(selectTour(tourId) ++ selectPlaying),
      Group(BSONBoolean(true))("ids" -> Push("_id"))
    ))) map {
      _.headOption flatMap {
        _.getAs[List[String]]("ids")
      } getOrElse Nil
    }
}
