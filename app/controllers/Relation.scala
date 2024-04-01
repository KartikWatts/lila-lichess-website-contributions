package controllers

import play.api.libs.json.{ Json, Writes }
import play.api.mvc.Result
import views.*

import lila.app.{ *, given }

import scalalib.paginator.{ AdapterLike, Paginator }
import lila.common.{ LightUser, config }
import lila.relation.Related
import lila.relation.RelationStream.*
import lila.user.User as UserModel

final class Relation(env: Env, apiC: => Api) extends LilaController(env):

  val api = env.relation.api

  private def renderActions(username: UserName, mini: Boolean)(using ctx: Context) = for
    user       <- env.user.lightUserApi.asyncFallbackName(username)
    relation   <- ctx.userId.so(api.fetchRelation(_, user.id))
    followable <- ctx.isAuth.so(env.pref.api.followable(user.id))
    blocked    <- ctx.userId.so(api.fetchBlocks(user.id, _))
    res <- negotiate(
      Ok.page:
        if mini
        then html.relation.mini(user.id, blocked = blocked, followable = followable, relation)
        else html.relation.actions(user, relation, blocked = blocked, followable = followable)
      ,
      Ok:
        Json.obj(
          "followable" -> followable,
          "following"  -> relation.contains(true),
          "blocking"   -> relation.contains(false)
        )
    )
  yield res

  private val FollowLimitPerUser = lila.memo.RateLimit[UserId](
    credits = 150,
    duration = 72.hour,
    key = "follow.user"
  )

  private def RatelimitWith(
      str: UserStr
  )(f: LightUser => Fu[Result])(using me: Me)(using Context): Fu[Result] =
    Found(env.user.lightUserApi.async(str.id)): user =>
      FollowLimitPerUser(me, rateLimited):
        f(user)

  def follow(username: UserStr) = AuthOrScoped(_.Follow.Write, _.Web.Mobile) { ctx ?=> me ?=>
    RatelimitWith(username): user =>
      api.reachedMaxFollowing(me).flatMap {
        if _ then
          val msg = lila.msg.MsgPreset.maxFollow(me.username, env.relation.maxFollow.value)
          env.msg.api.postPreset(me, msg) >> rateLimited(msg.name)
        else
          api.follow(me, user.id).recoverDefault >> negotiate(
            renderActions(user.name, getBool("mini")),
            jsonOkResult
          )
      }
  }
  def followBc = follow

  def unfollow(username: UserStr) = AuthOrScoped(_.Follow.Write, _.Web.Mobile) { ctx ?=> me ?=>
    RatelimitWith(username): user =>
      api.unfollow(me, user.id).recoverDefault >> negotiate(
        renderActions(user.name, getBool("mini")),
        jsonOkResult
      )
  }
  def unfollowBc = unfollow

  def block(username: UserStr) = Auth { ctx ?=> me ?=>
    RatelimitWith(username): user =>
      api.block(me, user.id).recoverDefault >> renderActions(user.name, getBool("mini"))
  }

  def unblock(username: UserStr) = Auth { ctx ?=> me ?=>
    RatelimitWith(username): user =>
      api.unblock(me, user.id).recoverDefault >> renderActions(user.name, getBool("mini"))
  }

  def following(username: UserStr, page: Int) = Open:
    Reasonable(page, Max(20)):
      Found(meOrFetch(username)): user =>
        RelatedPager(api.followingPaginatorAdapter(user.id), page).flatMap: pag =>
          negotiate(
            if ctx.is(user) || isGrantedOpt(_.CloseAccount)
            then Ok.page(html.relation.bits.friends(user, pag))
            else Found(ctx.me)(me => Redirect(routes.Relation.following(me.username))),
            Ok(jsonRelatedPaginator(pag))
          )

  def followers(username: UserStr, page: Int) = Open:
    negotiateJson:
      Reasonable(page, Max(20)):
        RelatedPager(api.followersPaginatorAdapter(username.id), page).flatMap: pag =>
          Ok(jsonRelatedPaginator(pag))

  def apiFollowing = Scoped(_.Follow.Read, _.Web.Mobile) { ctx ?=> me ?=>
    apiC.jsonDownload:
      env.relation.stream
        .follow(me, Direction.Following, MaxPerSecond(30))
        .map(env.api.userApi.one(_, None))
  }

  private def jsonRelatedPaginator(pag: Paginator[Related]) =
    given Writes[UserModel.WithPerfs] = lila.user.JsonView.nameWrites
    import lila.relation.JsonView.given
    import lila.common.Json.paginatorWrite
    Json.obj("paginator" -> pag.mapResults: r =>
      Json.toJsObject(r) ++ Json
        .obj:
          "perfs" -> r.user.perfs.bestRatedPerf.map:
            lila.user.JsonView.perfTypedJson
        .add("online" -> env.socket.isOnline(r.user.id)))

  def blocks(page: Int) = Auth { ctx ?=> me ?=>
    Reasonable(page, Max(20)):
      Ok.pageAsync:
        RelatedPager(api.blockingPaginatorAdapter(me), page).map {
          html.relation.bits.blocks(me, _)
        }
  }

  private def RelatedPager(adapter: AdapterLike[UserId], page: Int)(using Context) =
    Paginator(
      adapter = adapter.mapFutureList(followship),
      currentPage = page,
      maxPerPage = MaxPerPage(30)
    )

  private def followship(userIds: Seq[UserId])(using ctx: Context): Fu[List[Related]] = for
    users       <- env.user.api.listWithPerfs(userIds.toList)
    followables <- ctx.isAuth.so(env.pref.api.followableIds(users.map(_.id)))
    rels <- users.traverse: u =>
      ctx.userId
        .so(api.fetchRelation(_, u.id))
        .map: rel =>
          lila.relation.Related(u, none, followables(u.id), rel)
  yield rels
