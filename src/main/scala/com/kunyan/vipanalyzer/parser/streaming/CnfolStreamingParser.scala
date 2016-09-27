package com.kunyan.vipanalyzer.parser.streaming

import java.util.Date
import com.kunyan.vipanalyzer.util.{StringUtil, RedisUtil, DBUtil}
import com.kunyan.vipanalyzer.config.Platform
import com.kunyan.vipanalyzer.db.LazyConnections
import com.kunyan.vipanalyzer.logger.VALogger
import org.jsoup.Jsoup
import scala.util.control.Breaks._

/**
  * Created by niujiaojiao on 2016/6/3.
  * 中金博客解析
  */
object CnfolStreamingParser {

  /**
    * 解析获取信息
    *
    * @param html 将要解析的字符串
    */
  def parse(pageUrl: String, html: String, lazyConn: LazyConnections, topic: String) = {

    val doc = Jsoup.parse(html, "UTF-8")
    val list = doc.select("div#Tab1 div.TabItem ul li")
    val cstmt = lazyConn.mysqlConn.prepareCall("{call proc_InsertCNFOLNewArticle(?,?,?,?,?,?,?,?)}")

    val urlSql = lazyConn.mysqlConn.prepareStatement("select * from article_info where url = ?")

    val lastTitle = lazyConn.jedisHget(RedisUtil.REDIS_HASH_NAME, pageUrl)
    val timeStamp = new Date().getTime

    breakable {

      for (i <- 0 until list.size) {

        val child = list.get(i)
        val title = child.select("div.UserBox a.Tit").text()

        if (i == 0) {

          if (title != lastTitle) {
            lazyConn.jedisHset(RedisUtil.REDIS_HASH_NAME, pageUrl, title)
          } else {
            break()
          }

        }

        if (title == lastTitle)
          break()

        try {

          val userId = child.select("div.UserBox a.Name").attr("href").split("/").last
          val recommended = child.select("div.UserBox a.Tit i.TuiJian").size().toShort
          val reproduce = child.select("div.HandleBox span:nth-of-type(2)").text.split(" ").last.toInt
          val comment = child.select("div.HandleBox span:nth-of-type(3)").text.split(" ").last.toInt
          val url = child.select("div.UserBox a.Tit").attr("href")

          //查询url是否重复
          urlSql.setString(1,url)
          val blFlag = urlSql.executeQuery().next()

          if(blFlag){
            break()
          }

          val sqlFlag = DBUtil.insertCall(cstmt, userId, title, recommended, reproduce, comment, url, timeStamp, "")

          if (sqlFlag) {
            lazyConn.sendTask(topic, StringUtil.toJson(Platform.CNFOL.id.toString, 0, url))
          } else {
            VALogger.warn("MYSQL data has exception, stop topic for :  " + url)
          }

        } catch {
          case e: Exception =>
            VALogger.exception(e)
        }

      }

    }

    cstmt.close()
  }

}
