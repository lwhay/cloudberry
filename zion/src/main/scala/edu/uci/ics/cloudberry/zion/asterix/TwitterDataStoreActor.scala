package edu.uci.ics.cloudberry.zion.asterix

import edu.uci.ics.cloudberry.zion.actor.DataStoreActor
import edu.uci.ics.cloudberry.zion.common.Config
import edu.uci.ics.cloudberry.zion.model._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsArray, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.{ExecutionContext, Future}

class TwitterDataStoreActor(conn: AsterixConnection, config: Config)(implicit ec: ExecutionContext)
  extends DataStoreActor(TwitterDataStoreActor.Name) {

  import TwitterDataStoreActor._

  // TODO use the Visitor pattern to generate the AQL instead of this hacking code
  override def query(query: DBQuery): Future[Response] = {
    query match {
      case q: SampleQuery =>
        conn.post(generateSampleAQL(name, q)).map(handleSampleResponse)
      case q: DBQuery =>
        conn.post(generateAQL(name, q)).map(handleAllInOneWSResponse)
    }
  }
}

object TwitterDataStoreActor {
  val DataVerse = "twitter"
  val DataSet = "ds_tweet"
  val DataType = "typeTweet"
  val Name = s"$DataVerse.$DataSet"

  val FieldPrimaryKey = "id"
  val FieldStateID = "geo_tag.stateID"
  val FieldCountyID = "geo_tag.countyID"
  val FieldCityID = "geo_tag.cityID"

  val FieldCreateAt = "create_at"
  val FieldKeyword = "text"
  val FieldHashTag = "hash_tag"

  import SpatialLevels._
  import TimeLevels._

  val SpatialLevelMap = Map[SpatialLevels.Value, String](State -> FieldStateID, County -> FieldCountyID, City -> FieldCityID)

  val TimeFormatMap = Map[TimeLevels.Value, String](Year -> "YYYY", Month -> "YYYY-MM", Day -> "YYYY-MM-DD",
                                                    Hour -> "YYYY-MM-DD hh", Minute -> "YYYY-MM-DD hh:mm", Second -> "YYYY-MM-DD hh:mm:ss")

  def mergeResult(viewCount: SpatialTimeCount, sourceCount: SpatialTimeCount): Response = {
    SpatialTimeCount(
      KeyCountPair.keyCountMerge(viewCount.map, sourceCount.map),
      KeyCountPair.keyCountMerge(viewCount.time, sourceCount.time),
      KeyCountPair.keyCountMerge(viewCount.hashtag, sourceCount.hashtag)
    )
  }

  def handleAllInOneWSResponse(response: WSResponse): Response = {
    //TODO different aggregation cost differently, it may be better to split the query, which makes the coding easier.
    val seq = Json.parse(response.body.replaceAll(" \\]\n\\[", " ,\n")).as[Seq[Seq[KeyCountPair]]]
    SpatialTimeCount(seq(0), seq(1), seq(2))
  }

  def handleSampleResponse(wSResponse: WSResponse): Response = {
    SampleList(wSResponse.json.as[Seq[SampleTweet]])
  }

  def handleKeyCountResponse(jsArray: JsArray): Seq[KeyCountPair] = {
    jsArray.apply(0).as[Seq[KeyCountPair]]
  }

  def generateAQL(name: String, query: DBQuery): String = {
    val aqlVisitor = AQLVisitor(name)
    val predicate = query.predicates.map(p => aqlVisitor.visitPredicate("t", p)).mkString("\n")
    val common =
      s"""
         |use dataverse $DataVerse
         |let $$common := (
         |for $$t in dataset $name
         |$predicate
         |return $$t
         |)
         |""".stripMargin

    s"""
       |
       |$common
       |let $$map := (
       |for $$t in $$common
       |${byMap(query.summaryLevel.spatialLevel)}
       |)
       |return $$map
       |
       |$common
       |let $$time := (
       |for $$t in $$common
       |${byTime(query.summaryLevel.timeLevel)}
       |)
       |return $$time
       |
       |$common
       |let $$hashtag := (
       |for $$t in $$common
       |where not(is-null($$t.hashtags))
       |${byHashTag()}
       |)
       |return $$hashtag
       |
       |""".stripMargin
  }

  def generateSampleAQL(name: String, query: SampleQuery): String = {
    val aqlVisitor = AQLVisitor(name)
    val predicate = query.predicates.map(p => aqlVisitor.visitPredicate("t", p)).mkString("\n")
    s"""
       |use dataverse $DataVerse
       |for $$t in dataset $name
       |$predicate
       |limit ${query.limit} offset ${query.offset}
       |return { "uid": string($$t.user.id), "msg": $$t."text", "tid": string($$t.id) }
     """.stripMargin
  }

  //TODO move this hacking code to visitor
  private def byMap(level: SpatialLevels.Value): String = {
    s"""
       |group by $$c := $$t.${SpatialLevelMap.getOrElse(level, FieldStateID)} with $$t
       |return { "key": string($$c) , "count": count($$t) }
       |""".stripMargin
  }

  private def byTime(level: TimeLevels.Value): String = {
    s"""
       |group by $$c := print-datetime($$t.create_at, "${TimeFormatMap.getOrElse(level, "YYYY-MM-DD")}") with $$t
       |let $$count := count($$t)
       |return { "key" : $$c , "count": $$count }
       |""".stripMargin
  }

  private def byHashTag(): String = {
    s"""
       |for $$h in $$t.hashtags
       |group by $$tag := $$h with $$h
       |let $$c := count($$h)
       |order by $$c desc
       |limit 50
       |return { "key": $$tag, "count" : $$c}
       |""".stripMargin
  }

}








