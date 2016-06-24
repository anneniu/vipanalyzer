package com.kunyan.vipanalyzer.parser.streaming

import java.sql.CallableStatement
import java.text.SimpleDateFormat
import com.kunyan.vipanalyzer.config.Platform
import com.kunyan.vipanalyzer.db.LazyConnections
import com.kunyan.vipanalyzer.logger.VALogger
import com.kunyan.vipanalyzer.util.{StringUtil, DBUtil, RedisUtil}
import scala.util.control.Breaks._
import scala.util.parsing.json.JSON

/**
  * Created by niujiaojiao on 2016/6/7.
  * 淘股吧
  */
object TaogubaStreamingParser {
  /**
    * 解析获取信息
    *
    * @param html 将要解析的字符串
    */
  def parse(pageUrl: String, html: String, lazyConn: LazyConnections, topic: String) = {

    val jsonInfo = JSON.parseFull(html)

    val cstmt = lazyConn.mysqlConn.prepareCall("{call proc_InsertTaogubaNewArticle(?,?,?,?,?,?,?)}")

    val lastTitle = lazyConn.jedisHget(RedisUtil.REDIS_HASH_NAME, pageUrl)

    try {

      if (jsonInfo.isEmpty) {

        VALogger.error("\"JSON parse value is empty,please have a check!\"")

      } else {

        jsonInfo match {

          case Some(mapInfo: Map[String, AnyVal]) =>

            val recordValue = mapInfo.getOrElse("record", "").asInstanceOf[List[Map[String, AnyVal]]]

            breakable {

              for (i <- recordValue.indices) {

                val value = recordValue(i).asInstanceOf[Map[String, String]]
                val title = value.getOrElse("objectName", "")

                if (i == 0) {

                  if (title != lastTitle) {
                    lazyConn.jedisHset(RedisUtil.REDIS_HASH_NAME, pageUrl, title)
                  } else {
                    break()
                  }

                }

                if (title == lastTitle)
                  break()

                val date = value.getOrElse("actionDate", "")
                val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm")
                val timeStamp = fm.parse(date).getTime
                val userID = value.getOrElse("userID", "")
                val objectID = value.getOrElse("objectID", "")
                val otherID = value.getOrElse("OtherID", "")

                if (otherID.toInt != 0) {
                  //过滤掉此类ID

                  val url = "http://www.taoguba.com.cn/Article" + "/" + objectID + "/" + otherID
                  val stock = ""

                  VALogger.warn(StringUtil.toJson(Platform.TAOGUBA.id.toString, 1, url))

                  VALogger.warn("Taoguba inserts data to MYSQL")

                  val sqlFlag = DBUtil.insertCall(cstmt, userID, title, 0, 0, url, timeStamp, stock)

                  if (sqlFlag) {
                    VALogger.warn("Taoguba sends task")
                    lazyConn.sendTask(topic, StringUtil.toJson(Platform.TAOGUBA.id.toString, 1, url))
                  } else {
                    VALogger.warn("MYSQL data has exception, stop send topic for :  " + url)
                  }

                } else {
                  VALogger.warn("error report: platform taoguba" + "OtherID:   " + otherID + "html:  " + html.toString)
                }

              }

            }

          case None => VALogger.error("Parsing failed!")
          case other => VALogger.error("Unknown data structure :" + other)
        }

      }

    } catch {

      case e: Exception =>
        VALogger.exception(e)
    }

    cstmt.close()
  }

}
